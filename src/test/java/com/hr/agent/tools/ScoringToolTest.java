package com.hr.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
import dev.langchain4j.model.ollama.OllamaChatModel;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringToolTest {

    @Mock CandidateRepository candidateRepository;
    @Mock JobPostingRepository jobPostingRepository;
    @Mock OllamaChatModel ollamaChatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolResultContext toolResultContext = new ToolResultContext();
    private ScoringTool scoringTool;

    @BeforeEach
    void setUp() {
        scoringTool = new ScoringTool(
                candidateRepository, jobPostingRepository, ollamaChatModel,
                objectMapper, toolResultContext);
        ReflectionTestUtils.setField(scoringTool, "minScoreThreshold", 60.0);
    }

    @AfterEach
    void tearDown() {
        toolResultContext.retrieve();
    }

    // ── scoreCandidate ────────────────────────────────────────────────────────

    @Test
    void scoreCandidate_shortlistsWhenScoreAboveThreshold() throws Exception {
        Candidate candidate = candidate(1L, "Alice");
        JobPosting job = job(1L, "Senior Java Developer");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(ollamaChatModel.generate(anyString())).thenReturn(llmResponse(85, "SHORTLIST",
                "Strong Java skills", "No Kubernetes", "Excellent Spring Boot developer"));

        String result = scoringTool.scoreCandidate(1L, 1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("score").asDouble()).isEqualTo(85.0);
        assertThat(json.get("recommendation").asText()).isEqualTo("SHORTLIST");
        assertThat(json.get("candidate_name").asText()).isEqualTo("Alice");
        assertThat(json.get("job_title").asText()).isEqualTo("Senior Java Developer");

        assertThat(candidate.getStatus()).isEqualTo(Candidate.CandidateStatus.SHORTLISTED);
        assertThat(candidate.getScore()).isEqualTo(85.0);
        verify(candidateRepository).save(candidate);
    }

    @Test
    void scoreCandidate_rejectsWhenScoreBelowThreshold() throws Exception {
        Candidate candidate = candidate(2L, "Bob");
        JobPosting job = job(1L, "Senior Java Developer");
        when(candidateRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(ollamaChatModel.generate(anyString())).thenReturn(llmResponse(40, "REJECT",
                "Basic Java knowledge", "Missing 4 years experience", "Does not meet requirements"));

        String result = scoringTool.scoreCandidate(2L, 1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("score").asDouble()).isEqualTo(40.0);
        assertThat(candidate.getStatus()).isEqualTo(Candidate.CandidateStatus.REJECTED);
    }

    @Test
    void scoreCandidate_parsesAllLlmResponseFields() throws Exception {
        Candidate candidate = candidate(1L, "Alice");
        JobPosting job = job(1L, "Java Developer");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(ollamaChatModel.generate(anyString())).thenReturn(llmResponse(78, "SHORTLIST",
                "Spring Boot expert", "Lacks cloud experience", "Good overall fit"));

        String result = scoringTool.scoreCandidate(1L, 1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("strengths").asText()).isEqualTo("Spring Boot expert");
        assertThat(json.get("gaps").asText()).isEqualTo("Lacks cloud experience");
        assertThat(json.get("reason").asText()).isEqualTo("Good overall fit");
    }

    @Test
    void scoreCandidate_handlesUnparseableLlmResponse() throws Exception {
        Candidate candidate = candidate(1L, "Alice");
        JobPosting job = job(1L, "Java Developer");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(ollamaChatModel.generate(anyString())).thenReturn("I cannot score this candidate.");

        String result = scoringTool.scoreCandidate(1L, 1L);

        JsonNode json = objectMapper.readTree(result);
        // Falls back to default score of 50
        assertThat(json.get("score").asDouble()).isEqualTo(50.0);
        assertThat(json.get("recommendation").asText()).isEqualTo("CONSIDER");
    }

    // ── scoreAllCandidates ────────────────────────────────────────────────────

    @Test
    void scoreAllCandidates_scoresEachUnscoredCandidate() throws Exception {
        Candidate alice = candidate(1L, "Alice");
        Candidate bob = candidate(2L, "Bob");
        JobPosting job = job(1L, "Java Developer");

        when(candidateRepository.findUnscored(1L)).thenReturn(List.of(alice, bob));
        when(jobPostingRepository.findById(1L)).thenReturn(Optional.of(job));
        when(ollamaChatModel.generate(anyString()))
                .thenReturn(llmResponse(80, "SHORTLIST", "Good skills", "None", "Strong candidate"))
                .thenReturn(llmResponse(45, "REJECT", "Limited skills", "Missing experience", "Below threshold"));

        String result = scoringTool.scoreAllCandidates(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("scoring_batch");
        assertThat(json.get("count").asInt()).isEqualTo(2);
        assertThat(json.get("results").get(0).get("score").asDouble()).isEqualTo(80.0);
        assertThat(json.get("results").get(1).get("score").asDouble()).isEqualTo(45.0);

        assertThat(alice.getStatus()).isEqualTo(Candidate.CandidateStatus.SHORTLISTED);
        assertThat(bob.getStatus()).isEqualTo(Candidate.CandidateStatus.REJECTED);
        verify(candidateRepository, times(2)).save(any(Candidate.class));
    }

    @Test
    void scoreAllCandidates_returnsEmptyBatchWhenNoneUnscored() throws Exception {
        when(candidateRepository.findUnscored(1L)).thenReturn(List.of());

        String result = scoringTool.scoreAllCandidates(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(0);
        assertThat(json.get("results").isEmpty()).isTrue();
        verify(jobPostingRepository, never()).findById(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate candidate(Long id, String name) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setFullName(name);
        c.setSkills("Java, Spring Boot");
        c.setExperienceYears(5);
        c.setEducation("BSc Computer Science");
        c.setCurrentRole("Developer");
        return c;
    }

    private JobPosting job(Long id, String title) {
        JobPosting j = new JobPosting();
        j.setId(id);
        j.setTitle(title);
        j.setRequiredSkills("Java, Spring Boot");
        j.setExperienceYears(5);
        j.setDescription("Build enterprise applications");
        return j;
    }

    private String llmResponse(int score, String recommendation,
                               String strengths, String gaps, String reason) {
        return String.format("""
            SCORE: %d
            RECOMMENDATION: %s
            STRENGTHS: %s
            GAPS: %s
            REASON: %s
            """, score, recommendation, strengths, gaps, reason);
    }
}
