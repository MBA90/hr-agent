package com.hr.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
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
    private final JobPostingRepository jobPostingRepository;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Value("${hr.agent.min-score-threshold:60}")
    private double minScoreThreshold;

    @Tool("Get the top scored candidates for a specific job. " +
          "Input: jobId (Long), minScore (Double, optional — defaults to threshold). " +
          "Returns list of candidates with name, score, and status.")
    public String getTopCandidates(Long jobId, Double minScore) {
        double threshold = (minScore != null) ? minScore : minScoreThreshold;
        List<Candidate> candidates = candidateRepository.findTopCandidates(jobId, threshold);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "candidate_list");
        result.put("job_id", jobId);
        result.put("count", candidates.size());
        result.put("candidates", candidates.stream().map(this::candidateSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("Get all candidates who applied for a specific job posting. Input: jobId (Long).")
    public String getCandidatesByJob(Long jobId) {
        List<Candidate> list = candidateRepository.findByJobPostingId(jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "candidate_list");
        result.put("job_id", jobId);
        result.put("count", list.size());
        result.put("candidates", list.stream().map(this::candidateSummary).collect(Collectors.toList()));
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

    @Tool("Update the status of a candidate. " +
          "Input: candidateId (Long), newStatus (String: APPLIED|CV_REVIEWED|SHORTLISTED|INTERVIEW_SCHEDULED|HIRED|REJECTED).")
    public String updateCandidateStatus(Long candidateId, String newStatus) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        try {
            candidate.setStatus(Candidate.CandidateStatus.valueOf(newStatus.toUpperCase()));
            candidateRepository.save(candidate);
            return "Status of " + candidate.getFullName() + " updated to " + newStatus;
        } catch (IllegalArgumentException e) {
            return "Invalid status: " + newStatus;
        }
    }

    @Tool("Count how many candidates have applied for a job. Input: jobId (Long).")
    public String countApplicants(Long jobId) {
        long count = candidateRepository.countByJobPostingId(jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "count");
        result.put("job_id", jobId);
        result.put("count", count);
        return toJson(result);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> candidateSummary(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getFullName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("nationality", c.getNationality());
        m.put("score", c.getScore());
        m.put("status", c.getStatus() != null ? c.getStatus().name() : null);
        m.put("skills", c.getSkills());
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
        m.put("score", c.getScore());
        m.put("score_reason", c.getScoreReason());
        m.put("status", c.getStatus() != null ? c.getStatus().name() : null);
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
