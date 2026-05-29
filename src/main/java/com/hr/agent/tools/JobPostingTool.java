package com.hr.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.repository.JobPostingRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobPostingTool {

    private final JobPostingRepository jobPostingRepository;
    private final ApplicationRepository applicationRepository;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Tool("Create a new job posting. " +
          "Input: title (String, required), department, description, requiredSkills (comma-separated), " +
          "experienceYears (Integer), location, salaryMin (Double), salaryMax (Double). " +
          "Returns the created job with its assigned ID.")
    public String createJobPosting(String title, String department, String description,
                                   String requiredSkills, Integer experienceYears,
                                   String location, Double salaryMin, Double salaryMax) {
        log.info("Creating job posting: title={}, department={}", title, department);
        JobPosting job = JobPosting.builder()
                .title(title)
                .department(department)
                .description(description)
                .requiredSkills(requiredSkills)
                .experienceYears(experienceYears)
                .location(location)
                .salaryMin(salaryMin)
                .salaryMax(salaryMax)
                .status(JobPosting.JobStatus.OPEN)
                .build();
        jobPostingRepository.save(job);
        log.info("Job posting created: id={}", job.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "job_created");
        result.put("job", jobDetail(job));
        return toJson(result);
    }

    @Tool("Update fields of an existing job posting. Only non-null values overwrite existing data. " +
          "Input: jobId (Long, required); optionally title, department, description, " +
          "requiredSkills, experienceYears (Integer), location, salaryMin (Double), salaryMax (Double).")
    public String updateJobPosting(Long jobId, String title, String department, String description,
                                   String requiredSkills, Integer experienceYears,
                                   String location, Double salaryMin, Double salaryMax) {
        log.info("Updating job posting: jobId={}", jobId);
        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (title != null)          job.setTitle(title);
        if (department != null)     job.setDepartment(department);
        if (description != null)    job.setDescription(description);
        if (requiredSkills != null) job.setRequiredSkills(requiredSkills);
        if (experienceYears != null) job.setExperienceYears(experienceYears);
        if (location != null)       job.setLocation(location);
        if (salaryMin != null)      job.setSalaryMin(salaryMin);
        if (salaryMax != null)      job.setSalaryMax(salaryMax);
        jobPostingRepository.save(job);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "job_updated");
        result.put("job", jobDetail(job));
        return toJson(result);
    }

    @Tool("Change the status of a job posting. " +
          "Input: jobId (Long), newStatus (String: OPEN|CLOSED|ON_HOLD|FILLED).")
    public String updateJobStatus(Long jobId, String newStatus) {
        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        try {
            JobPosting.JobStatus status = JobPosting.JobStatus.valueOf(newStatus.toUpperCase());
            job.setStatus(status);
            jobPostingRepository.save(job);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "job_status_updated");
            result.put("job_id", jobId);
            result.put("title", job.getTitle());
            result.put("new_status", status.name());
            return toJson(result);
        } catch (IllegalArgumentException e) {
            return "{\"type\":\"error\",\"message\":\"Invalid status: " + newStatus
                    + ". Valid values are OPEN, CLOSED, ON_HOLD, FILLED.\"}";
        }
    }

    @Tool("Search job postings by a keyword in the job title. " +
          "Input: keyword (String). Returns all matches regardless of status.")
    public String searchJobsByKeyword(String keyword) {
        List<JobPosting> jobs = jobPostingRepository.findByTitleContaining(keyword);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "job_list");
        result.put("keyword", keyword);
        result.put("count", jobs.size());
        result.put("jobs", jobs.stream().map(this::jobDetail).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("List job postings filtered by department and optionally by status. " +
          "Input: department (String), status (String: OPEN|CLOSED|ON_HOLD|FILLED — omit for all statuses in that department).")
    public String listJobsByDepartment(String department, String status) {
        List<JobPosting> jobs;
        if (status != null && !status.isBlank()) {
            try {
                jobs = jobPostingRepository.findByDepartmentAndStatus(
                        department, JobPosting.JobStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return "{\"type\":\"error\",\"message\":\"Invalid status: " + status + "\"}";
            }
        } else {
            jobs = jobPostingRepository.findAll().stream()
                    .filter(j -> department.equalsIgnoreCase(j.getDepartment()))
                    .collect(Collectors.toList());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "job_list");
        result.put("department", department);
        result.put("status_filter", status);
        result.put("count", jobs.size());
        result.put("jobs", jobs.stream().map(this::jobSummary).collect(Collectors.toList()));
        return toJson(result);
    }

    @Tool("Get a dashboard summary of all job postings: total count and breakdown by status (OPEN, CLOSED, ON_HOLD, FILLED).")
    public String getJobStats() {
        long open   = jobPostingRepository.countByStatus(JobPosting.JobStatus.OPEN);
        long closed = jobPostingRepository.countByStatus(JobPosting.JobStatus.CLOSED);
        long onHold = jobPostingRepository.countByStatus(JobPosting.JobStatus.ON_HOLD);
        long filled = jobPostingRepository.countByStatus(JobPosting.JobStatus.FILLED);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "job_stats");
        result.put("total", open + closed + onHold + filled);
        result.put("open", open);
        result.put("closed", closed);
        result.put("on_hold", onHold);
        result.put("filled", filled);
        return toJson(result);
    }

    @Tool("Permanently delete a job posting. Only allowed when no candidates have applied. " +
          "If candidates exist, close or fill the job instead. Input: jobId (Long).")
    public String deleteJobPosting(Long jobId) {
        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        long candidateCount = applicationRepository.countByJobPostingId(jobId);
        if (candidateCount > 0) {
            return "{\"type\":\"error\",\"message\":\"Cannot delete job " + jobId
                    + " — it has " + candidateCount + " candidate(s). Close or fill it instead.\"}";
        }
        jobPostingRepository.delete(job);
        log.info("Job posting deleted: jobId={} title={}", jobId, job.getTitle());
        return "{\"type\":\"job_deleted\",\"job_id\":" + jobId + ",\"title\":\""
                + job.getTitle().replace("\"", "\\\"") + "\"}";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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