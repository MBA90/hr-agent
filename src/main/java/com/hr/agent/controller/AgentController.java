package com.hr.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.dto.ChatRequest;
import com.hr.agent.dto.ChatResponse;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
import com.hr.agent.service.HrAgentService;
import com.hr.agent.tools.ToolResultContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

/**
 * REST Controller — HR Agent endpoints
 *
 * POST /api/agent/chat            → Chat with the HR agent
 * POST /api/agent/upload-cv       → Upload a candidate's PDF CV
 * GET  /api/agent/health          → Health check
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final HrAgentService hrAgentService;
    private final CandidateRepository candidateRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Value("${hr.agent.cv-storage-path:./cv-uploads/}")
    private String cvStoragePath;

    // ── Chat ──────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("Chat from recruiterId={}: {}", request.getRecruiterId(), request.getMessage());
        try {
            String reply = hrAgentService.chat(request.getRecruiterId(), request.getMessage());
            return ResponseEntity.ok(parseReply(request.getRecruiterId(), reply));
        } catch (Exception e) {
            log.error("Agent error for recruiterId={}", request.getRecruiterId(), e);
            return ResponseEntity.internalServerError()
                    .body(ChatResponse.error(request.getRecruiterId(), e.getMessage()));
        }
    }

    private ChatResponse parseReply(String recruiterId, String reply) {
        // Primary data source: captured directly from the tool (never truncated by the LLM)
        JsonNode capturedData = toolResultContext.retrieve();

        // Extract LLM text message from the reply
        String message = extractMessage(reply);

        return ChatResponse.builder()
                .recruiterId(recruiterId)
                .message(message)
                .data(capturedData)
                .timestamp(LocalDateTime.now())
                .success(true)
                .build();
    }

    private String extractMessage(String reply) {
        JsonNode json = extractJsonObject(reply);
        if (json != null && json.has("message")) {
            String text = json.get("message").asText();
            // Handle double-wrap: LLM put entire JSON in "message"
            if (text.trim().startsWith("{")) {
                JsonNode inner = extractJsonObject(text);
                if (inner != null && inner.has("message")) {
                    return inner.get("message").asText();
                }
            }
            return text;
        }
        // Fallback: use the raw reply as the message
        return reply;
    }

    /**
     * Tries to extract a JSON object from text that may be:
     *  1. A clean JSON string
     *  2. Wrapped in a markdown code block (```json ... ```)
     *  3. Surrounded by extra prose (takes the first { ... } substring)
     */
    private JsonNode extractJsonObject(String text) {
        if (text == null || text.isBlank()) return null;

        // 1. Direct parse
        try { return objectMapper.readTree(text); }
        catch (Exception ignored) {}

        // 2. Strip markdown code fence
        String stripped = text
                .replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .trim();
        if (!stripped.equals(text)) {
            try { return objectMapper.readTree(stripped); }
            catch (Exception ignored) {}
        }

        // 3. Extract first { ... } substring
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try { return objectMapper.readTree(text.substring(start, end + 1)); }
            catch (Exception ignored) {}
        }

        return null;
    }

    // ── CV Upload ─────────────────────────────────────────────────────────────

    @PostMapping("/upload-cv")
    public ResponseEntity<String> uploadCv(
            @RequestParam("file")  MultipartFile file,
            @RequestParam("jobId") Long jobId,
            @RequestParam("email") String email,
            @RequestParam("name")  String name,
            @RequestParam(value = "phone", required = false) String phone) {

        String originalFilename = file.getOriginalFilename();
        if (file.isEmpty() || originalFilename == null || !originalFilename.endsWith(".pdf")) {
            return ResponseEntity.badRequest().body("Please upload a valid PDF file.");
        }

        try {
            Path storageDir = Paths.get(cvStoragePath);
            Files.createDirectories(storageDir);
            String fileName = System.currentTimeMillis() + "_" + originalFilename;
            Path filePath = storageDir.resolve(fileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            JobPosting job = jobPostingRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

            Candidate candidate = candidateRepository.findByEmail(email)
                    .orElseGet(() -> Candidate.builder()
                            .fullName(name)
                            .email(email)
                            .phone(phone)
                            .jobPosting(job)
                            .status(Candidate.CandidateStatus.APPLIED)
                            .build());

            candidate.setCvFilePath(filePath.toString());
            candidateRepository.save(candidate);

            log.info("CV uploaded for candidate={} jobId={}", email, jobId);
            return ResponseEntity.ok(
                "CV uploaded successfully. Candidate ID: " + candidate.getId() +
                ". Use the chat to parse and score this candidate."
            );

        } catch (Exception e) {
            log.error("CV upload failed for email={}", email, e);
            return ResponseEntity.internalServerError().body("Upload failed: " + e.getMessage());
        }
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("HR Agent is running");
    }
}
