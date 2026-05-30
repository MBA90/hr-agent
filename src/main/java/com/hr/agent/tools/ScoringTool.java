package com.hr.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.dto.ScoringResult;
import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScoringTool {

    private final ApplicationRepository applicationRepository;
    private final OllamaChatModel ollamaChatModel;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Value("${hr.agent.min-score-threshold:60}")
    private double minScoreThreshold;

    @Tool("Score a candidate against a job posting and decide if they should be shortlisted or rejected. " +
          "Input: candidateId (Long), jobId (Long). Returns score (0-100) and recommendation.")
    public String scoreCandidate(Long candidateId, Long jobId) {
        log.info("Scoring candidateId={} for jobId={}", candidateId, jobId);

        Application application = applicationRepository
                .findByCandidateIdAndJobPostingIdWithDetails(candidateId, jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No application found for candidateId=" + candidateId + " jobId=" + jobId));

        try {
            ScoringResult result = doScore(application);
            log.info("Scored candidate={} score={}", application.getCandidate().getFullName(), result.getScore());
            return toJson(scoringResultMap(result, application.getCandidate().getFullName(),
                    application.getJobPosting().getTitle()));
        } catch (Exception e) {
            log.error("Scoring failed for candidateId={} jobId={}", candidateId, jobId, e);
            return "Scoring failed: " + e.getMessage();
        }
    }

    @Tool("Score all unscored candidates for a given job posting. Input: jobId (Long).")
    public String scoreAllCandidates(Long jobId) {
        List<Application> unscored = applicationRepository.findUnscored(jobId);
        if (unscored.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "scoring_batch");
            empty.put("job_id", jobId);
            empty.put("count", 0);
            empty.put("results", List.of());
            return toJson(empty);
        }

        JobPosting job = unscored.get(0).getJobPosting();

        List<Map<String, Object>> results = new ArrayList<>();
        for (Application app : unscored) {
            try {
                ScoringResult result = doScore(app);
                results.add(scoringResultMap(result, app.getCandidate().getFullName(), job.getTitle()));
            } catch (Exception e) {
                log.error("Scoring failed for applicationId={}", app.getId(), e);
                Map<String, Object> errEntry = new LinkedHashMap<>();
                errEntry.put("application_id", app.getId());
                errEntry.put("candidate_name", app.getCandidate().getFullName());
                errEntry.put("error", e.getMessage());
                results.add(errEntry);
            }
        }

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("type", "scoring_batch");
        batch.put("job_id", jobId);
        batch.put("job_title", job.getTitle());
        batch.put("count", results.size());
        batch.put("results", results);
        return toJson(batch);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ScoringResult doScore(Application application) {
        ScoringResult result = scoreWithLlm(application);
        application.setScore(result.getScore());
        application.setScoreReason(result.getReason());
        application.setStatus(result.isShortlisted(minScoreThreshold)
                ? Application.ApplicationStatus.SHORTLISTED
                : Application.ApplicationStatus.REJECTED);
        applicationRepository.save(application);
        return result;
    }

    private Map<String, Object> scoringResultMap(ScoringResult r, String candidateName, String jobTitle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "scoring_result");
        m.put("candidate_id", r.getCandidateId());
        m.put("candidate_name", candidateName);
        m.put("job_id", r.getJobId());
        m.put("job_title", jobTitle);
        m.put("score", r.getScore());
        m.put("recommendation", r.getRecommendation());
        m.put("strengths", r.getStrengths());
        m.put("gaps", r.getGaps());
        m.put("reason", r.getReason());
        return m;
    }

    private ScoringResult scoreWithLlm(Application application) {
        Candidate candidate = application.getCandidate();
        JobPosting job = application.getJobPosting();
        String prompt = String.format("""
            You are an expert HR recruiter. Score this candidate for the job below.

            JOB TITLE: %s
            REQUIRED SKILLS: %s
            REQUIRED EXPERIENCE: %d years
            JOB DESCRIPTION: %s

            CANDIDATE NAME: %s
            CANDIDATE SKILLS: %s
            CANDIDATE EXPERIENCE: %d years
            CANDIDATE EDUCATION: %s
            CANDIDATE CURRENT ROLE: %s

            Respond ONLY in this exact format:
            SCORE: <number 0-100>
            RECOMMENDATION: <SHORTLIST or REJECT or CONSIDER>
            STRENGTHS: <what the candidate does well>
            GAPS: <missing skills or experience>
            REASON: <one sentence justification>
            """,
            job.getTitle(),
            job.getRequiredSkills(),
            job.getExperienceYears() != null ? job.getExperienceYears() : 0,
            job.getDescription(),
            candidate.getFullName(),
            application.getSkills() != null ? application.getSkills() : "Not specified",
            application.getExperienceYears() != null ? application.getExperienceYears() : 0,
            application.getEducation() != null ? application.getEducation() : "Not specified",
            application.getCurrentRole() != null ? application.getCurrentRole() : "Not specified"
        );

        String response = ollamaChatModel.generate(prompt);
        return parseScoringResponse(response, candidate.getId(), job.getId());
    }

    private ScoringResult parseScoringResponse(String response, Long candidateId, Long jobId) {
        ScoringResult result = ScoringResult.builder()
                .candidateId(candidateId).jobId(jobId)
                .score(50.0).recommendation("CONSIDER").build();

        for (String line : response.split("\n")) {
            if (line.startsWith("SCORE:")) {
                try { result.setScore(Double.parseDouble(after(line))); }
                catch (NumberFormatException ignored) {}
            } else if (line.startsWith("RECOMMENDATION:")) result.setRecommendation(after(line));
            else if (line.startsWith("STRENGTHS:"))        result.setStrengths(after(line));
            else if (line.startsWith("GAPS:"))             result.setGaps(after(line));
            else if (line.startsWith("REASON:"))           result.setReason(after(line));
        }
        return result;
    }

    private String after(String line) {
        int idx = line.indexOf(":");
        return idx >= 0 ? line.substring(idx + 1).trim() : "";
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