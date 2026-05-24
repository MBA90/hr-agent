package com.hr.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.Interview;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.InterviewRepository;
import com.hr.agent.repository.JobPostingRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerTool {

    private final InterviewRepository interviewRepository;
    private final CandidateRepository candidateRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Tool("Schedule an interview for a candidate. " +
          "Input: candidateId (Long), jobId (Long), scheduledAt (String: 'yyyy-MM-dd HH:mm'), " +
          "interviewType (String: TECHNICAL|HR|FINAL), interviewerName (String).")
    public String scheduleInterview(Long candidateId, Long jobId,
                                    String scheduledAt, String interviewType,
                                    String interviewerName) {
        log.info("Scheduling interview for candidateId={} jobId={} at={}", candidateId, jobId, scheduledAt);

        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(scheduledAt, FMT);
        } catch (Exception e) {
            return "Invalid date format. Use: yyyy-MM-dd HH:mm (e.g. 2025-06-15 10:00)";
        }

        boolean conflict = interviewRepository.existsConflict(
                dateTime.minusMinutes(59), dateTime.plusMinutes(59));
        if (conflict) {
            return "Time conflict detected at " + scheduledAt + ". Please choose a different slot.";
        }

        Interview interview = Interview.builder()
                .candidate(candidate)
                .jobPosting(job)
                .scheduledAt(dateTime)
                .interviewType(interviewType != null ? interviewType : "TECHNICAL")
                .interviewerName(interviewerName)
                .durationMinutes(60)
                .status(Interview.InterviewStatus.SCHEDULED)
                .build();

        interviewRepository.save(interview);

        candidate.setStatus(Candidate.CandidateStatus.INTERVIEW_SCHEDULED);
        candidateRepository.save(candidate);

        log.info("Interview scheduled: interviewId={}", interview.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "interview_scheduled");
        result.put("interview_id", interview.getId());
        result.put("candidate_id", candidateId);
        result.put("candidate_name", candidate.getFullName());
        result.put("job_id", jobId);
        result.put("job_title", job.getTitle());
        result.put("scheduled_at", scheduledAt);
        result.put("interview_type", interviewType);
        result.put("interviewer", interviewerName);
        return toJson(result);
    }

    @Tool("List all upcoming scheduled interviews. Returns interview ID, candidate, job, date, and type.")
    public String getUpcomingInterviews() {
        List<Interview> upcoming = interviewRepository.findUpcoming(LocalDateTime.now());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "interview_list");
        result.put("count", upcoming.size());
        result.put("interviews", upcoming.stream().map(this::interviewSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("Cancel an interview by its ID. Input: interviewId (Long), reason (String).")
    public String cancelInterview(Long interviewId, String reason) {
        Interview interview = interviewRepository.findByIdWithAssociations(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + interviewId));

        interview.setStatus(Interview.InterviewStatus.CANCELLED);
        interview.setNotes("Cancelled: " + reason);
        interviewRepository.save(interview);

        Candidate candidate = interview.getCandidate();
        candidate.setStatus(Candidate.CandidateStatus.SHORTLISTED);
        candidateRepository.save(candidate);

        return "Interview " + interviewId + " cancelled for " + candidate.getFullName() + ". Reason: " + reason;
    }

    @Tool("Get all interviews for a specific candidate. Input: candidateId (Long).")
    public String getCandidateInterviews(Long candidateId) {
        List<Interview> interviews = interviewRepository.findByCandidateId(candidateId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "interview_list");
        result.put("candidate_id", candidateId);
        result.put("count", interviews.size());
        result.put("interviews", interviews.stream().map(this::interviewSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> interviewSummary(Interview i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("candidate_id", i.getCandidate().getId());
        m.put("candidate_name", i.getCandidate().getFullName());
        m.put("job_id", i.getJobPosting().getId());
        m.put("job_title", i.getJobPosting().getTitle());
        m.put("scheduled_at", i.getScheduledAt().format(FMT));
        m.put("interview_type", i.getInterviewType());
        m.put("interviewer", i.getInterviewerName());
        m.put("duration_minutes", i.getDurationMinutes());
        m.put("status", i.getStatus() != null ? i.getStatus().name() : null);
        return m;
    }

    private String toJson(Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            toolResultContext.capture(objectMapper.readTree(json));
            return json;
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }
}
