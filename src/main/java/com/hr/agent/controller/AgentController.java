package com.hr.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.dto.ChatRequest;
import com.hr.agent.dto.ChatResponse;
import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.JobPostingRepository;
import com.hr.agent.service.HrAgentService;
import com.hr.agent.tools.ToolResultContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/agent") 
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final HrAgentService hrAgentService;
    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ObjectMapper objectMapper;
    private final ToolResultContext toolResultContext;

    @Value("${hr.agent.cv-storage-path:./cv-uploads/}")
    private String cvStoragePath;

    // ── Chat ──────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<ChatResponse> chat(
            @RequestBody ChatRequest request,
            Authentication authentication) {

        // Identity comes from the verified JWT — never from the request body
        String username = authentication.getName();
        log.info("Chat from user={}: {}", username, request.getMessage());

        try {
            String reply = hrAgentService.chat(username, request.getMessage());
            return ResponseEntity.ok(parseReply(username, reply));
        } catch (Exception e) {
            log.error("Agent error for user={}", username, e);
            return ResponseEntity.internalServerError()
                    .body(ChatResponse.error(username, e.getMessage()));
        }
    }

    private ChatResponse parseReply(String username, String reply) {
        JsonNode capturedData = toolResultContext.retrieve();
        String message = extractMessage(reply);

        return ChatResponse.builder()
                .recruiterId(username)
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
            if (text.trim().startsWith("{")) {
                JsonNode inner = extractJsonObject(text);
                if (inner != null && inner.has("message")) {
                    return inner.get("message").asText();
                }
            }
            return text;
        }
        return reply;
    }

    private JsonNode extractJsonObject(String text) {
        if (text == null || text.isBlank()) return null;

        try { return objectMapper.readTree(text); }
        catch (Exception ignored) {}

        String stripped = text
                .replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "")
                .trim();
        if (!stripped.equals(text)) {
            try { return objectMapper.readTree(stripped); }
            catch (Exception ignored) {}
        }

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
    @PreAuthorize("hasRole('RECRUITER')")
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

            JobPosting job = jobPostingRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

            // Upsert candidate identity only (name/email/phone). CV-derived
            // profile data is now snapshotted per-application, never on the candidate.
            Candidate candidate = candidateRepository.findByEmail(email)
                    .map(existing -> {
                        existing.setFullName(name);
                        if (phone != null && !phone.isBlank()) existing.setPhone(phone);
                        return existing;
                    })
                    .orElseGet(() -> Candidate.builder()
                            .fullName(name)
                            .email(email)
                            .phone(phone)
                            .build());

            candidate = candidateRepository.save(candidate);

            // Resolve (or create) the application for this candidate+job before
            // writing the file, so its APP_REF_NO is available for the filename.
            final Candidate savedCandidate = candidate;
            Application application = applicationRepository
                    .findByCandidateIdAndJobPostingIdWithDetails(candidate.getId(), jobId)
                    .orElseGet(() -> applicationRepository.save(Application.builder()
                            .candidate(savedCandidate)
                            .jobPosting(job)
                            .status(Application.ApplicationStatus.APPLIED)
                            .build()));

            // Once an application has been reviewed it is locked: its CV/profile
            // snapshot and status must not change. Reject the re-upload outright.
            if (application.getStatus() != Application.ApplicationStatus.APPLIED) {
                log.info("Rejected CV re-upload for appRefNo={} jobId={} status={}",
                        application.getAppRefNo(), jobId, application.getStatus());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    "Application " + application.getAppRefNo() + " is already under review (status "
                    + application.getStatus() + "). Its CV can no longer be replaced.");
            }

            // Still APPLIED — safe to (re)attach the CV. Each upload is a new,
            // immutable version on disk; never overwrite.
            int newVersion = (application.getCvVersion() != null ? application.getCvVersion() : 0) + 1;
            String fileName = application.getAppRefNo() + "_v" + newVersion + ".pdf";
            Path filePath = storageDir.resolve(fileName);
            Files.copy(file.getInputStream(), filePath);

            application.setCvFilePath("./cv-uploads/" + fileName);
            application.setCvVersion(newVersion);
            applicationRepository.save(application);

            log.info("CV uploaded for candidate={} appRefNo={} jobId={} version={}",
                    email, application.getAppRefNo(), jobId, newVersion);
            return ResponseEntity.ok(
                "CV uploaded successfully (v" + newVersion + "). Application Reference: "
                + application.getAppRefNo() + " (Candidate: " + candidate.getCndRefNo()
                + "). Use the chat to parse and score this application.");

        } catch (Exception e) {
            log.error("CV upload failed for email={}", email, e);
            return ResponseEntity.internalServerError().body("Upload failed: " + e.getMessage());
        }
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("HR Agent is running");
    }
}