package com.hr.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.dto.ScoringResult;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
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

    private final CandidateRepository candidateRepository;
    private final JobPostingRepository jobPostingRepository;
    private final OllamaChatModel ollamaChatModel;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Value("${hr.agent.min-score-threshold:60}")
    private double minScoreThreshold;

    @Tool("Score a candidate against a job posting and decide if they should be shortlisted or rejected. " +
          "Input: candidateId (Long), jobId (Long). Returns score (0-100) and recommendation.")
    public String scoreCandidate(Long candidateId, Long jobId) {
        log.info("Scoring candidateId={} for jobId={}", candidateId, jobId);

        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));
        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        try {
            ScoringResult result = doScore(candidate, job);
            log.info("Scored candidate={} score={}", candidate.getFullName(), result.getScore());
            return toJson(scoringResultMap(result, candidate.getFullName(), job.getTitle()));
        } catch (Exception e) {
            log.error("Scoring failed for candidateId={} jobId={}", candidateId, jobId, e);
            return "Scoring failed: " + e.getMessage();
        }
    }

    @Tool("Score all unscored candidates for a given job posting. Input: jobId (Long).")
    public String scoreAllCandidates(Long jobId) {
        List<Candidate> unscored = candidateRepository.findUnscored(jobId);
        if (unscored.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "scoring_batch");
            empty.put("job_id", jobId);
            empty.put("count", 0);
            empty.put("results", List.of());
            return toJson(empty);
        }

        JobPosting job = jobPostingRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<Map<String, Object>> results = new ArrayList<>();
        for (Candidate c : unscored) {
            try {
                ScoringResult result = doScore(c, job);
                results.add(scoringResultMap(result, c.getFullName(), job.getTitle()));
            } catch (Exception e) {
                log.error("Scoring failed for candidateId={}", c.getId(), e);
                Map<String, Object> errEntry = new LinkedHashMap<>();
                errEntry.put("candidate_id", c.getId());
                errEntry.put("candidate_name", c.getFullName());
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

    private ScoringResult doScore(Candidate candidate, JobPosting job) {
        ScoringResult result = scorewithLlm(candidate, job);
        candidate.setScore(result.getScore());
        candidate.setScoreReason(result.getReason());
        candidate.setStatus(result.isShortlisted(minScoreThreshold)
                ? Candidate.CandidateStatus.SHORTLISTED
                : Candidate.CandidateStatus.REJECTED);
        candidateRepository.save(candidate);
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

    private ScoringResult scorewithLlm(Candidate candidate, JobPosting job) {
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
            candidate.getSkills() != null ? candidate.getSkills() : "Not specified",
            candidate.getExperienceYears() != null ? candidate.getExperienceYears() : 0,
            candidate.getEducation() != null ? candidate.getEducation() : "Not specified",
            candidate.getCurrentRole() != null ? candidate.getCurrentRole() : "Not specified"
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
