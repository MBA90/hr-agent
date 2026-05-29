package com.hr.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandidateTool {

    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Value("${hr.agent.min-score-threshold:60}") 
    private double minScoreThreshold;

    @Tool("Get the top scored candidates for a specific job. " +
          "Input: jobId (Long), minScore (Double, optional — defaults to threshold). " +
          "Returns list of applications with candidate name, score, status, and applicationId.")
    public String getTopCandidates(Long jobId, Double minScore) {
        double threshold = (minScore != null) ? minScore : minScoreThreshold;
        List<Application> applications = applicationRepository.findTopApplications(jobId, threshold);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "candidate_list");
        result.put("job_id", jobId);
        result.put("count", applications.size());
        result.put("candidates", applications.stream().map(this::applicationSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("Get all candidates who applied for a specific job posting. Input: jobId (Long).")
    public String getCandidatesByJob(Long jobId) {
        List<Application> applications = applicationRepository.findByJobPostingIdWithDetails(jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "candidate_list");
        result.put("job_id", jobId);
        result.put("count", applications.size());
        result.put("candidates", applications.stream().map(this::applicationSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("Get details of a single candidate by their ID. Input: candidateId (Long).")
    public String getCandidateDetails(Long candidateId) {
        return candidateRepository.findById(candidateId)
                .map(c -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "candidate");
                    result.put("candidate", candidateDetail(c));
                    return toJson(result);
                })
                .orElse("{\"type\":\"error\",\"message\":\"Candidate not found: " + candidateId + "\"}");
    }

    @Tool("Get details of a specific application. Input: applicationId (Long).")
    public String getApplicationDetails(Long applicationId) {
        return applicationRepository.findByIdWithDetails(applicationId)
                .map(a -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "application");
                    result.put("application", applicationDetail(a));
                    return toJson(result);
                })
                .orElse("{\"type\":\"error\",\"message\":\"Application not found: " + applicationId + "\"}");
    }

    @Tool("List all open job postings. Returns job IDs, titles, departments, and location.")
    public String listOpenJobs() {
        List<JobPosting> jobs = jobPostingRepository.findByStatus(JobPosting.JobStatus.OPEN);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "job_list");
        result.put("count", jobs.size());
        result.put("jobs", jobs.stream().map(this::jobSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("Get full details of a job posting. Input: jobId (Long).")
    public String getJobDetails(Long jobId) {
        return jobPostingRepository.findById(jobId)
                .map(j -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "job");
                    result.put("job", jobDetail(j));
                    return toJson(result);
                })
                .orElse("{\"type\":\"error\",\"message\":\"Job not found: " + jobId + "\"}");
    }

    @Tool("Update the status of a specific application. " +
          "Input: applicationId (Long), newStatus (String: APPLIED|CV_REVIEWED|SHORTLISTED|INTERVIEW_SCHEDULED|OFFER_SENT|HIRED|REJECTED).")
    public String updateApplicationStatus(Long applicationId, String newStatus) {
        Application application = applicationRepository.findByIdWithDetails(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
        try {
            application.setStatus(Application.ApplicationStatus.valueOf(newStatus.toUpperCase()));
            applicationRepository.save(application);
            return "Status of " + application.getCandidate().getFullName()
                    + "'s application for " + application.getJobPosting().getTitle()
                    + " updated to " + newStatus;
        } catch (IllegalArgumentException e) {
            return "Invalid status: " + newStatus;
        }
    }

    @Tool("Count how many candidates have applied for a job. Input: jobId (Long).")
    public String countApplicants(Long jobId) {
        long count = applicationRepository.countByJobPostingId(jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "count");
        result.put("job_id", jobId);
        result.put("count", count);
        return toJson(result);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> applicationSummary(Application a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("application_id", a.getId());
        m.put("candidate_id", a.getCandidate().getId());
        m.put("name", a.getCandidate().getFullName());
        m.put("email", a.getCandidate().getEmail());
        m.put("phone", a.getCandidate().getPhone());
        m.put("nationality", a.getCandidate().getNationality());
        m.put("score", a.getScore());
        m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
        m.put("skills", a.getCandidate().getSkills());
        return m;
    }

    private Map<String, Object> applicationDetail(Application a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("application_id", a.getId());
        m.put("candidate_id", a.getCandidate().getId());
        m.put("name", a.getCandidate().getFullName());
        m.put("email", a.getCandidate().getEmail());
        m.put("phone", a.getCandidate().getPhone());
        m.put("nationality", a.getCandidate().getNationality());
        m.put("skills", a.getCandidate().getSkills());
        m.put("experience_years", a.getCandidate().getExperienceYears());
        m.put("education", a.getCandidate().getEducation());
        m.put("current_role", a.getCandidate().getCurrentRole());
        m.put("job_id", a.getJobPosting().getId());
        m.put("job_title", a.getJobPosting().getTitle());
        m.put("score", a.getScore());
        m.put("score_reason", a.getScoreReason());
        m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
        m.put("applied_at", a.getAppliedAt() != null ? a.getAppliedAt().toString() : null);
        return m;
    }

    private Map<String, Object> candidateDetail(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getFullName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("nationality", c.getNationality());
        m.put("skills", c.getSkills());
        m.put("experience_years", c.getExperienceYears());
        m.put("education", c.getEducation());
        m.put("current_role", c.getCurrentRole());
        if (c.getApplications() != null && !c.getApplications().isEmpty()) {
            m.put("applications", c.getApplications().stream()
                    .map(a -> {
                        Map<String, Object> app = new LinkedHashMap<>();
                        app.put("application_id", a.getId());
                        app.put("job_id", a.getJobPosting() != null ? a.getJobPosting().getId() : null);
                        app.put("job_title", a.getJobPosting() != null ? a.getJobPosting().getTitle() : null);
                        app.put("status", a.getStatus() != null ? a.getStatus().name() : null);
                        app.put("score", a.getScore());
                        return app;
                    })
                    .collect(java.util.stream.Collectors.toList()));
        }
        return m;
    }

    private Map<String, Object> jobSummary(JobPosting j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("title", j.getTitle());
        m.put("department", j.getDepartment());
        m.put("location", j.getLocation());
        m.put("experience_years", j.getExperienceYears());
        m.put("salary_min", j.getSalaryMin());
        m.put("salary_max", j.getSalaryMax());
        m.put("status", j.getStatus() != null ? j.getStatus().name() : null);
        return m;
    }

    private Map<String, Object> jobDetail(JobPosting j) {
        Map<String, Object> m = jobSummary(j);
        m.put("required_skills", j.getRequiredSkills());
        m.put("description", j.getDescription());
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