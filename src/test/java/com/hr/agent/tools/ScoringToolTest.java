package com.hr.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
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

    @Mock ApplicationRepository applicationRepository;
    @Mock OllamaChatModel ollamaChatModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolResultContext toolResultContext = new ToolResultContext();
    private ScoringTool scoringTool;

    @BeforeEach
    void setUp() {
        scoringTool = new ScoringTool(
                applicationRepository, ollamaChatModel, objectMapper, toolResultContext);
        ReflectionTestUtils.setField(scoringTool, "minScoreThreshold", 60.0);
    }

    @AfterEach
    void tearDown() {
        toolResultContext.retrieve();
    }

    // ── scoreCandidate ────────────────────────────────────────────────────────

    @Test
    void scoreCandidate_shortlistsWhenScoreAboveThreshold() throws Exception {
        Application app = application(1L, "Alice", 1L, "Senior Java Developer");
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));
        when(ollamaChatModel.generate(anyString())).thenReturn(llmResponse(85, "SHORTLIST",
                "Strong Java skills", "No Kubernetes", "Excellent Spring Boot developer"));

        String result = scoringTool.scoreCandidate(1L, 1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("score").asDouble()).isEqualTo(85.0);
        assertThat(json.get("recommendation").asText()).isEqualTo("SHORTLIST");
        assertThat(json.get("candidate_name").asText()).isEqualTo("Alice");
        assertThat(json.get("job_title").asText()).isEqualTo("Senior Java Developer");

        assertThat(app.getStatus()).isEqualTo(Application.ApplicationStatus.SHORTLISTED);
        assertThat(app.getScore()).isEqualTo(85.0);
        verify(applicationRepository).save(app);
    }

    @Test
    void scoreCandidate_rejectsWhenScoreBelowThreshold() throws Exception {
        Application app = application(2L, "Bob", 1L, "Senior Java Developer");
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(2L, 1L))
                .thenReturn(Optional.of(app));
        when(ollamaChatModel.generate(anyString())).thenReturn(llmResponse(40, "REJECT",
                "Basic Java knowledge", "Missing 4 years experience", "Does not meet requirements"));

        String result = scoringTool.scoreCandidate(2L, 1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("score").asDouble()).isEqualTo(40.0);
        assertThat(app.getStatus()).isEqualTo(Application.ApplicationStatus.REJECTED);
    }

    @Test
    void scoreCandidate_parsesAllLlmResponseFields() throws Exception {
        Application app = application(1L, "Alice", 1L, "Java Developer");
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));
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
        Application app = application(1L, "Alice", 1L, "Java Developer");
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));
        when(ollamaChatModel.generate(anyString())).thenReturn("I cannot score this candidate.");

        String result = scoringTool.scoreCandidate(1L, 1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("score").asDouble()).isEqualTo(50.0);
        assertThat(json.get("recommendation").asText()).isEqualTo("CONSIDER");
    }

    // ── scoreAllCandidates ────────────────────────────────────────────────────

    @Test
    void scoreAllCandidates_scoresEachUnscoredCandidate() throws Exception {
        Application alice = application(1L, "Alice", 1L, "Java Developer");
        Application bob = application(2L, "Bob", 1L, "Java Developer");

        when(applicationRepository.findUnscored(1L)).thenReturn(List.of(alice, bob));
        when(ollamaChatModel.generate(anyString()))
                .thenReturn(llmResponse(80, "SHORTLIST", "Good skills", "None", "Strong candidate"))
                .thenReturn(llmResponse(45, "REJECT", "Limited skills", "Missing experience", "Below threshold"));

        String result = scoringTool.scoreAllCandidates(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("type").asText()).isEqualTo("scoring_batch");
        assertThat(json.get("count").asInt()).isEqualTo(2);
        assertThat(json.get("results").get(0).get("score").asDouble()).isEqualTo(80.0);
        assertThat(json.get("results").get(1).get("score").asDouble()).isEqualTo(45.0);

        assertThat(alice.getStatus()).isEqualTo(Application.ApplicationStatus.SHORTLISTED);
        assertThat(bob.getStatus()).isEqualTo(Application.ApplicationStatus.REJECTED);
        verify(applicationRepository, times(2)).save(any(Application.class));
    }

    @Test
    void scoreAllCandidates_returnsEmptyBatchWhenNoneUnscored() throws Exception {
        when(applicationRepository.findUnscored(1L)).thenReturn(List.of());

        String result = scoringTool.scoreAllCandidates(1L);

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.get("count").asInt()).isEqualTo(0);
        assertThat(json.get("results").isEmpty()).isTrue();
        verify(applicationRepository, never()).save(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Application application(Long candidateId, String candidateName,
                                    Long jobId, String jobTitle) {
        Candidate c = new Candidate();
        c.setId(candidateId);
        c.setFullName(candidateName);
        c.setSkills("Java, Spring Boot");
        c.setExperienceYears(5);
        c.setEducation("BSc Computer Science");
        c.setCurrentRole("Developer");

        JobPosting j = new JobPosting();
        j.setId(jobId);
        j.setTitle(jobTitle);
        j.setRequiredSkills("Java, Spring Boot");
        j.setExperienceYears(5);
        j.setDescription("Build enterprise applications");

        Application a = new Application();
        a.setId(candidateId);
        a.setCandidate(c);
        a.setJobPosting(j);
        a.setStatus(Application.ApplicationStatus.APPLIED);
        return a;
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