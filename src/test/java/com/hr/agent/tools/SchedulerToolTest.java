package com.hr.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.Interview;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.InterviewRepository;
import com.hr.agent.repository.JobPostingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerToolTest {

    @Mock InterviewRepository interviewRepository;
    @Mock CandidateRepository candidateRepository;
    @Mock JobPostingRepository jobPostingRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolResultContext toolResultContext = new ToolResultContext();
    private SchedulerTool schedulerTool;

    @BeforeEach
    void setUp() {
        schedulerTool = new SchedulerTool(
                interviewRepository, candidateRepository, jobPostingRepository,
                objectMapper, toolResultContext);
    }

    @AfterEach
    void tearDown() {
        toolResultContext.retrieve();
    }

    // ── scheduleInterview ─────────────────────────────────────────────────────

    @Test
    void scheduleInterview_savesInterviewAndUpdatesStatus() throws Exception {
        Candidate candidate = candidate(1L, "Alice");
        JobPosting job = job(1L, "Java Developer");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(interviewRepository.existsConflict(any(), any())).thenReturn(false);
        when(interviewRepository.save(any())).thenAnswer(inv -> {
            Interview i = inv.getArgument(0);
            i.setId(10L);
            return i;
        });

        String result = schedulerTool.scheduleInterview(
                1L, 1L, "2026-06-15 10:00", "TECHNICAL", "John Smith");

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("interview_scheduled");
        assertThat(json.get("candidate_name").asText()).isEqualTo("Alice");
        assertThat(json.get("job_title").asText()).isEqualTo("Java Developer");
        assertThat(json.get("interview_type").asText()).isEqualTo("TECHNICAL");
        assertThat(json.get("interviewer").asText()).isEqualTo("John Smith");

        assertThat(candidate.getStatus()).isEqualTo(Candidate.CandidateStatus.INTERVIEW_SCHEDULED);
        verify(interviewRepository).save(any(Interview.class));
        verify(candidateRepository).save(candidate);
    }

    @Test
    void scheduleInterview_savesInterviewWithCorrectDateTime() {
        Candidate candidate = candidate(1L, "Alice");
        JobPosting job = job(1L, "Java Developer");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(interviewRepository.existsConflict(any(), any())).thenReturn(false);
        when(interviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        schedulerTool.scheduleInterview(1L, 1L, "2026-06-15 14:30", "HR", "Jane Doe");

        ArgumentCaptor<Interview> captor = ArgumentCaptor.forClass(Interview.class);
        verify(interviewRepository).save(captor.capture());
        assertThat(captor.getValue().getScheduledAt())
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 14, 30));
    }

    @Test
    void scheduleInterview_returnsConflictMessageWhenSlotTaken() {
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate(1L, "Alice")));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job(1L, "Java Developer")));
        when(interviewRepository.existsConflict(any(), any())).thenReturn(true);

        String result = schedulerTool.scheduleInterview(
                1L, 1L, "2026-06-15 10:00", "TECHNICAL", "John");

        assertThat(result).contains("conflict");
        verify(interviewRepository, never()).save(any());
    }

    @Test
    void scheduleInterview_returnsErrorForInvalidDateFormat() {
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate(1L, "Alice")));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job(1L, "Java Developer")));

        String result = schedulerTool.scheduleInterview(
                1L, 1L, "15/06/2026", "TECHNICAL", "John");

        assertThat(result).contains("Invalid date format");
        verify(interviewRepository, never()).save(any());
    }

    // ── getUpcomingInterviews ─────────────────────────────────────────────────

    @Test
    void getUpcomingInterviews_returnsInterviewList() throws Exception {
        Interview interview = interview(1L, "Alice", "Java Developer", "2026-06-15 10:00");
        when(interviewRepository.findUpcoming(any(LocalDateTime.class)))
                .thenReturn(List.of(interview));

        String result = schedulerTool.getUpcomingInterviews();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("interview_list");
        assertThat(json.get("count").asInt()).isEqualTo(1);
        assertThat(json.get("interviews").get(0).get("candidate_name").asText()).isEqualTo("Alice");
        assertThat(json.get("interviews").get(0).get("job_title").asText()).isEqualTo("Java Developer");
    }

    @Test
    void getUpcomingInterviews_returnsEmptyList() throws Exception {
        when(interviewRepository.findUpcoming(any())).thenReturn(List.of());

        String result = schedulerTool.getUpcomingInterviews();

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(0);
    }

    // ── cancelInterview ───────────────────────────────────────────────────────

    @Test
    void cancelInterview_cancelsAndRevertsStatusToShortlisted() {
        Interview interview = interview(1L, "Alice", "Java Developer", "2026-06-15 10:00");
        Candidate candidate = interview.getCandidate();
        candidate.setStatus(Candidate.CandidateStatus.INTERVIEW_SCHEDULED);
        when(interviewRepository.findByIdWithAssociations(1L)).thenReturn(Optional.of(interview));

        String result = schedulerTool.cancelInterview(1L, "Candidate withdrew");

        assertThat(interview.getStatus()).isEqualTo(Interview.InterviewStatus.CANCELLED);
        assertThat(interview.getNotes()).contains("Candidate withdrew");
        assertThat(candidate.getStatus()).isEqualTo(Candidate.CandidateStatus.SHORTLISTED);
        assertThat(result).contains("cancelled");
        verify(interviewRepository).save(interview);
        verify(candidateRepository).save(candidate);
    }

    // ── getCandidateInterviews ────────────────────────────────────────────────

    @Test
    void getCandidateInterviews_returnsAllInterviewsForCandidate() throws Exception {
        List<Interview> interviews = List.of(
                interview(1L, "Alice", "Java Developer", "2026-06-10 09:00"),
                interview(2L, "Alice", "Java Developer", "2026-06-17 14:00"));
        when(interviewRepository.findByCandidateId(1L)).thenReturn(interviews);

        String result = schedulerTool.getCandidateInterviews(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("candidate_id").asLong()).isEqualTo(1L);
        assertThat(json.get("count").asInt()).isEqualTo(2);
    }

    @Test
    void getCandidateInterviews_returnsEmptyList() throws Exception {
        when(interviewRepository.findByCandidateId(99L)).thenReturn(List.of());

        String result = schedulerTool.getCandidateInterviews(99L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate candidate(Long id, String name) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setFullName(name);
        c.setEmail(name.toLowerCase() + "@example.com");
        c.setStatus(Candidate.CandidateStatus.SHORTLISTED);
        return c;
    }

    private JobPosting job(Long id, String title) {
        JobPosting j = new JobPosting();
        j.setId(id);
        j.setTitle(title);
        return j;
    }

    private Interview interview(Long id, String candidateName, String jobTitle, String dateStr) {
        Interview i = new Interview();
        i.setId(id);
        i.setCandidate(candidate(id, candidateName));
        i.setJobPosting(job(id, jobTitle));
        i.setScheduledAt(LocalDateTime.parse(dateStr,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        i.setInterviewType("TECHNICAL");
        i.setInterviewerName("HR Team");
        i.setDurationMinutes(60);
        i.setStatus(Interview.InterviewStatus.SCHEDULED);
        return i;
    }
}
