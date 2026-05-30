package com.hr.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateToolTest {

    @Mock CandidateRepository candidateRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock JobPostingRepository jobPostingRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolResultContext toolResultContext = new ToolResultContext();
    private CandidateTool candidateTool;

    @BeforeEach
    void setUp() {
        candidateTool = new CandidateTool(
                candidateRepository, applicationRepository, jobPostingRepository,
                objectMapper, toolResultContext);
        ReflectionTestUtils.setField(candidateTool, "minScoreThreshold", 60.0);
    }

    @AfterEach
    void tearDown() {
        toolResultContext.retrieve();
    }

    // ── listOpenJobs ──────────────────────────────────────────────────────────

    @Test
    void listOpenJobs_returnsJobList() throws Exception {
        JobPosting job = jobPosting(1L, "Senior Java Developer", "Engineering", "Dubai");
        when(jobPostingRepository.findByStatus(JobPosting.JobStatus.OPEN)).thenReturn(List.of(job));

        String result = candidateTool.listOpenJobs();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job_list");
        assertThat(json.get("count").asInt()).isEqualTo(1);
        assertThat(json.get("jobs").get(0).get("title").asText()).isEqualTo("Senior Java Developer");

        JsonNode captured = toolResultContext.retrieve();
        assertThat(captured.get("count").asInt()).isEqualTo(1);
    }

    @Test
    void listOpenJobs_returnsEmptyListWhenNoOpenJobs() throws Exception {
        when(jobPostingRepository.findByStatus(JobPosting.JobStatus.OPEN)).thenReturn(List.of());

        String result = candidateTool.listOpenJobs();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(0);
        assertThat(json.get("jobs").isEmpty()).isTrue();
    }

    // ── getJobDetails ─────────────────────────────────────────────────────────

    @Test
    void getJobDetails_returnsFullJobInfo() throws Exception {
        JobPosting job = jobPosting(1L, "DevOps Engineer", "IT", "Abu Dhabi");
        job.setRequiredSkills("Docker, Kubernetes");
        job.setDescription("Build and maintain CI/CD pipelines");
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));

        String result = candidateTool.getJobDetails(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job");
        assertThat(json.get("job").get("required_skills").asText()).isEqualTo("Docker, Kubernetes");
        assertThat(json.get("job").get("description").asText()).contains("CI/CD");
    }

    @Test
    void getJobDetails_returnsErrorWhenNotFound() throws Exception {
        when(jobPostingRepository.findById(99L)).thenReturn(Optional.empty());

        String result = candidateTool.getJobDetails(99L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("error");
        assertThat(json.get("message").asText()).contains("99");
    }

    // ── getTopCandidates ──────────────────────────────────────────────────────

    @Test
    void getTopCandidates_usesDefaultThresholdWhenMinScoreNull() throws Exception {
        Application app = application(1L, "Alice", 85.0, Application.ApplicationStatus.SHORTLISTED);
        when(applicationRepository.findTopApplications(1L, 60.0)).thenReturn(List.of(app));

        String result = candidateTool.getTopCandidates(1L, null);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(1);
        assertThat(json.get("candidates").get(0).get("score").asDouble()).isEqualTo(85.0);
        verify(applicationRepository).findTopApplications(1L, 60.0);
    }

    @Test
    void getTopCandidates_usesProvidedMinScore() {
        when(applicationRepository.findTopApplications(1L, 75.0)).thenReturn(List.of());

        candidateTool.getTopCandidates(1L, 75.0);

        verify(applicationRepository).findTopApplications(1L, 75.0);
    }

    // ── getCandidatesByJob ────────────────────────────────────────────────────

    @Test
    void getCandidatesByJob_returnsAllCandidates() throws Exception {
        List<Application> apps = List.of(
                application(1L, "Alice", 85.0, Application.ApplicationStatus.SHORTLISTED),
                application(2L, "Bob", null, Application.ApplicationStatus.APPLIED));
        when(applicationRepository.findByJobPostingIdWithDetails(1L)).thenReturn(apps);

        String result = candidateTool.getCandidatesByJob(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(2);
        assertThat(json.get("candidates").get(1).get("name").asText()).isEqualTo("Bob");
        assertThat(json.get("candidates").get(1).get("score").isNull()).isTrue();
    }

    // ── getCandidateDetails ───────────────────────────────────────────────────

    @Test
    void getCandidateDetails_returnsIdentityWithPerApplicationProfile() throws Exception {
        Candidate c = candidate(1L, "Alice");
        c.setEmail("alice@example.com");
        // Profile is now snapshotted per application, not on the candidate.
        Application app = application(1L, "Alice", 80.0, Application.ApplicationStatus.CV_REVIEWED);
        app.setSkills("Java, Spring Boot");
        app.setExperienceYears(6);
        app.setEducation("BSc Computer Science");
        app.setCurrentRole("Senior Developer");
        c.setApplications(java.util.List.of(app));
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(c));

        String result = candidateTool.getCandidateDetails(1L);

        JsonNode json = objectMapper.readTree(result);
        JsonNode cd = json.get("candidate");
        assertThat(cd.get("name").asText()).isEqualTo("Alice");
        // Candidate root is identity-only now.
        assertThat(cd.has("skills")).isFalse();
        JsonNode appNode = cd.get("applications").get(0);
        assertThat(appNode.get("skills").asText()).isEqualTo("Java, Spring Boot");
        assertThat(appNode.get("experience_years").asInt()).isEqualTo(6);
    }

    @Test
    void getCandidateDetails_returnsErrorWhenNotFound() throws Exception {
        when(candidateRepository.findById(99L)).thenReturn(Optional.empty());

        String result = candidateTool.getCandidateDetails(99L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("error");
    }

    // ── updateApplicationStatus ───────────────────────────────────────────────

    @Test
    void updateApplicationStatus_updatesAndSaves() {
        Application app = application(1L, "Alice", 85.0, Application.ApplicationStatus.SHORTLISTED);
        when(applicationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(app));

        String result = candidateTool.updateApplicationStatus(1L, "HIRED");

        assertThat(app.getStatus()).isEqualTo(Application.ApplicationStatus.HIRED);
        assertThat(result).contains("HIRED");
        verify(applicationRepository).save(app);
    }

    @Test
    void updateApplicationStatus_returnsErrorForInvalidStatus() {
        Application app = application(1L, "Alice", 85.0, Application.ApplicationStatus.SHORTLISTED);
        when(applicationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(app));

        String result = candidateTool.updateApplicationStatus(1L, "FLYING");

        assertThat(result).contains("Invalid status");
        verify(applicationRepository, never()).save(any());
    }

    // ── countApplicants ───────────────────────────────────────────────────────

    @Test
    void countApplicants_returnsCount() throws Exception {
        when(applicationRepository.countByJobPostingId(1L)).thenReturn(7L);

        String result = candidateTool.countApplicants(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asLong()).isEqualTo(7L);
        assertThat(json.get("job_id").asLong()).isEqualTo(1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate candidate(Long id, String name) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setFullName(name);
        c.setEmail(name.toLowerCase() + "@example.com");
        return c;
    }

    private Application application(Long id, String candidateName, Double score,
                                    Application.ApplicationStatus status) {
        Candidate c = candidate(id, candidateName);
        JobPosting j = jobPosting(1L, "Senior Java Developer", "Engineering", "Dubai");
        Application a = new Application();
        a.setId(id);
        a.setCandidate(c);
        a.setJobPosting(j);
        a.setScore(score);
        a.setStatus(status);
        return a;
    }

    private JobPosting jobPosting(Long id, String title, String department, String location) {
        JobPosting j = new JobPosting();
        j.setId(id);
        j.setTitle(title);
        j.setDepartment(department);
        j.setLocation(location);
        j.setExperienceYears(4);
        j.setStatus(JobPosting.JobStatus.OPEN);
        return j;
    }
}