package com.hr.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobPostingToolTest {

    @Mock JobPostingRepository jobPostingRepository;
    @Mock CandidateRepository candidateRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolResultContext toolResultContext = new ToolResultContext();
    private JobPostingTool jobPostingTool;

    @BeforeEach
    void setUp() {
        jobPostingTool = new JobPostingTool(
                jobPostingRepository, candidateRepository, objectMapper, toolResultContext);
    }

    @AfterEach
    void tearDown() {
        toolResultContext.retrieve();
    }

    // ── createJobPosting ──────────────────────────────────────────────────────

    @Test
    void createJobPosting_savesAndReturnsCreatedJob() throws Exception {
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> {
            JobPosting j = inv.getArgument(0);
            j.setId(10L);
            return j;
        });

        String result = jobPostingTool.createJobPosting(
                "Backend Engineer", "Engineering", "Build APIs",
                "Java, Spring Boot", 3, "Dubai", 10000.0, 15000.0);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job_created");
        assertThat(json.get("job").get("title").asText()).isEqualTo("Backend Engineer");
        assertThat(json.get("job").get("id").asLong()).isEqualTo(10L);
        assertThat(json.get("job").get("required_skills").asText()).isEqualTo("Java, Spring Boot");
        verify(jobPostingRepository).save(any(JobPosting.class));

        JsonNode captured = toolResultContext.retrieve();
        assertThat(captured.get("type").asText()).isEqualTo("job_created");
    }

    @Test
    void createJobPosting_setsStatusToOpen() throws Exception {
        when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = jobPostingTool.createJobPosting(
                "Data Analyst", null, null, null, null, null, null, null);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("job").get("status").asText()).isEqualTo("OPEN");
    }

    // ── updateJobPosting ──────────────────────────────────────────────────────

    @Test
    void updateJobPosting_updatesOnlyNonNullFields() throws Exception {
        JobPosting existing = jobPosting(1L, "Old Title", "Engineering", "Dubai");
        existing.setExperienceYears(3);
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = jobPostingTool.updateJobPosting(
                1L, "New Title", null, null, null, 5, null, null, null);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job_updated");
        assertThat(existing.getTitle()).isEqualTo("New Title");
        assertThat(existing.getDepartment()).isEqualTo("Engineering"); // unchanged
        assertThat(existing.getExperienceYears()).isEqualTo(5);
        verify(jobPostingRepository).save(existing);
    }

    @Test
    void updateJobPosting_throwsWhenJobNotFound() {
        when(jobPostingRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> jobPostingTool.updateJobPosting(99L, "X", null, null, null, null, null, null, null));
    }

    // ── updateJobStatus ───────────────────────────────────────────────────────

    @Test
    void updateJobStatus_updatesStatusAndSaves() throws Exception {
        JobPosting job = jobPosting(1L, "Dev Role", "IT", "Remote");
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = jobPostingTool.updateJobStatus(1L, "CLOSED");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job_status_updated");
        assertThat(json.get("new_status").asText()).isEqualTo("CLOSED");
        assertThat(job.getStatus()).isEqualTo(JobPosting.JobStatus.CLOSED);
        verify(jobPostingRepository).save(job);
    }

    @Test
    void updateJobStatus_acceptsCaseInsensitiveInput() throws Exception {
        JobPosting job = jobPosting(1L, "Dev Role", "IT", "Remote");
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String result = jobPostingTool.updateJobStatus(1L, "filled");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("new_status").asText()).isEqualTo("FILLED");
    }

    @Test
    void updateJobStatus_returnsErrorForInvalidStatus() throws Exception {
        JobPosting job = jobPosting(1L, "Dev Role", "IT", "Remote");
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));

        String result = jobPostingTool.updateJobStatus(1L, "INVALID");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("error");
        assertThat(json.get("message").asText()).contains("INVALID");
        verify(jobPostingRepository, never()).save(any());
    }

    // ── searchJobsByKeyword ───────────────────────────────────────────────────

    @Test
    void searchJobsByKeyword_returnsMatchingJobs() throws Exception {
        List<JobPosting> jobs = List.of(
                jobPosting(1L, "Senior Java Developer", "Engineering", "Dubai"),
                jobPosting(2L, "Junior Java Developer", "Engineering", "Abu Dhabi"));
        when(jobPostingRepository.findByTitleContaining("Java")).thenReturn(jobs);

        String result = jobPostingTool.searchJobsByKeyword("Java");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(2);
        assertThat(json.get("keyword").asText()).isEqualTo("Java");
        assertThat(json.get("jobs").get(0).get("title").asText()).isEqualTo("Senior Java Developer");
    }

    @Test
    void searchJobsByKeyword_returnsEmptyListWhenNoMatch() throws Exception {
        when(jobPostingRepository.findByTitleContaining("XYZ")).thenReturn(List.of());

        String result = jobPostingTool.searchJobsByKeyword("XYZ");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(0);
        assertThat(json.get("jobs").isEmpty()).isTrue();
    }

    // ── listJobsByDepartment ──────────────────────────────────────────────────

    @Test
    void listJobsByDepartment_withStatus_usesStatusFilter() throws Exception {
        List<JobPosting> jobs = List.of(jobPosting(1L, "Dev", "Engineering", "Dubai"));
        when(jobPostingRepository.findByDepartmentAndStatus("Engineering", JobPosting.JobStatus.OPEN))
                .thenReturn(jobs);

        String result = jobPostingTool.listJobsByDepartment("Engineering", "OPEN");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(1);
        assertThat(json.get("department").asText()).isEqualTo("Engineering");
    }

    @Test
    void listJobsByDepartment_withoutStatus_returnsAllInDepartment() throws Exception {
        List<JobPosting> allJobs = List.of(
                jobPosting(1L, "Dev", "Engineering", "Dubai"),
                jobPosting(2L, "QA", "Engineering", "Abu Dhabi"),
                jobPosting(3L, "PM", "Product", "Dubai"));
        when(jobPostingRepository.findAll()).thenReturn(allJobs);

        String result = jobPostingTool.listJobsByDepartment("Engineering", null);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(2);
    }

    @Test
    void listJobsByDepartment_returnsErrorForInvalidStatus() throws Exception {
        String result = jobPostingTool.listJobsByDepartment("Engineering", "WRONG");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("error");
    }

    // ── getJobStats ───────────────────────────────────────────────────────────

    @Test
    void getJobStats_returnsCountsForAllStatusesAndTotal() throws Exception {
        when(jobPostingRepository.countByStatus(JobPosting.JobStatus.OPEN)).thenReturn(5L);
        when(jobPostingRepository.countByStatus(JobPosting.JobStatus.CLOSED)).thenReturn(2L);
        when(jobPostingRepository.countByStatus(JobPosting.JobStatus.ON_HOLD)).thenReturn(1L);
        when(jobPostingRepository.countByStatus(JobPosting.JobStatus.FILLED)).thenReturn(3L);

        String result = jobPostingTool.getJobStats();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job_stats");
        assertThat(json.get("total").asLong()).isEqualTo(11L);
        assertThat(json.get("open").asLong()).isEqualTo(5L);
        assertThat(json.get("closed").asLong()).isEqualTo(2L);
        assertThat(json.get("on_hold").asLong()).isEqualTo(1L);
        assertThat(json.get("filled").asLong()).isEqualTo(3L);

        JsonNode captured = toolResultContext.retrieve();
        assertThat(captured.get("total").asLong()).isEqualTo(11L);
    }

    // ── deleteJobPosting ──────────────────────────────────────────────────────

    @Test
    void deleteJobPosting_deletesWhenNoCandidates() throws Exception {
        JobPosting job = jobPosting(1L, "Old Role", "IT", "Remote");
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(candidateRepository.countByJobPostingId(1L)).thenReturn(0L);

        String result = jobPostingTool.deleteJobPosting(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("job_deleted");
        assertThat(json.get("job_id").asLong()).isEqualTo(1L);
        assertThat(json.get("title").asText()).isEqualTo("Old Role");
        verify(jobPostingRepository).delete(job);
    }

    @Test
    void deleteJobPosting_blockedWhenCandidatesExist() throws Exception {
        JobPosting job = jobPosting(1L, "Dev", "IT", "Remote");
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(candidateRepository.countByJobPostingId(1L)).thenReturn(3L);

        String result = jobPostingTool.deleteJobPosting(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("error");
        assertThat(json.get("message").asText()).contains("3");
        verify(jobPostingRepository, never()).delete(any());
    }

    @Test
    void deleteJobPosting_throwsWhenJobNotFound() {
        when(jobPostingRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> jobPostingTool.deleteJobPosting(99L));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JobPosting jobPosting(Long id, String title, String department, String location) {
        JobPosting j = new JobPosting();
        j.setId(id);
        j.setTitle(title);
        j.setDepartment(department);
        j.setLocation(location);
        j.setExperienceYears(3);
        j.setStatus(JobPosting.JobStatus.OPEN);
        return j;
    }
}