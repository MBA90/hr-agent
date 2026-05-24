package com.hr.agent.tools;

import com.hr.agent.dto.CandidateProfile;
import com.hr.agent.entity.Candidate;
import com.hr.agent.repository.CandidateRepository;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Tool: CV Parser
 * ───────────────
 * Reads a candidate's PDF CV from disk, extracts raw text via PDFBox,
 * then asks the LLM to parse it into structured CandidateProfile fields.
 * Finally saves the extracted data back to the CANDIDATE table.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CvParserTool {

    private final CandidateRepository candidateRepository;
    private final OllamaChatModel ollamaChatModel;

    @Value("${hr.agent.cv-storage-path:./cv-uploads/}")
    private String cvStoragePath;

    @Tool("Parse the CV of a candidate and extract their skills, experience, education, and current role. " +
          "Input: candidateId (Long). Returns a summary of the parsed profile.")
    public String parseCandidateCv(Long candidateId) {
        log.info("Parsing CV for candidateId={}", candidateId);

        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        if (candidate.getCvFilePath() == null || candidate.getCvFilePath().isBlank()) {
            return "No CV file found for candidate " + candidate.getFullName() + ". Please upload a CV first.";
        }

        try {
            // 1. Extract raw text from PDF
            String rawText = extractTextFromPdf(candidate.getCvFilePath());

            // 2. Ask LLM to parse into structured fields
            CandidateProfile profile = extractProfileWithLlm(rawText);

            // 3. Persist extracted data
            candidate.setCvRawText(rawText);
            candidate.setSkills(profile.getSkills());
            candidate.setExperienceYears(profile.getExperienceYears());
            candidate.setEducation(profile.getEducation());
            candidate.setCurrentRole(profile.getCurrentRole());
            candidate.setNationality(profile.getNationality());
            candidate.setStatus(Candidate.CandidateStatus.CV_REVIEWED);
            candidateRepository.save(candidate);

            log.info("CV parsed successfully for candidate={}", candidate.getFullName());
            return String.format(
                "CV parsed for %s: Skills=[%s], Experience=%d yrs, Education=%s, Role=%s",
                candidate.getFullName(), profile.getSkills(),
                profile.getExperienceYears(), profile.getEducation(), profile.getCurrentRole()
            );

        } catch (Exception e) {
            log.error("Failed to parse CV for candidateId={}", candidateId, e);
            return "Failed to parse CV for candidate " + candidateId + ": " + e.getMessage();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractTextFromPdf(String filePath) throws Exception {
        File pdfFile = new File(filePath);
        if (!pdfFile.exists()) {
            // Try relative to cv-storage-path
            pdfFile = new File(cvStoragePath + filePath);
        }
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private CandidateProfile extractProfileWithLlm(String rawText) {
        String prompt = """
            Extract the following information from this CV text and respond ONLY in this exact format:
            
            FULL_NAME: <full name>
            CURRENT_ROLE: <current job title and company>
            EXPERIENCE_YEARS: <total years of professional experience as a number>
            SKILLS: <comma-separated list of technical skills>
            EDUCATION: <highest degree and institution>
            NATIONALITY: <nationality>
            SUMMARY: <2-sentence professional summary>
            
            CV TEXT:
            """ + rawText;

        String response = ollamaChatModel.generate(prompt);

        return parseProfileResponse(response);
    }

    private CandidateProfile parseProfileResponse(String response) {
        CandidateProfile profile = new CandidateProfile();
        for (String line : response.split("\n")) {
            if (line.startsWith("FULL_NAME:"))        profile.setFullName(after(line));
            else if (line.startsWith("CURRENT_ROLE:")) profile.setCurrentRole(after(line));
            else if (line.startsWith("SKILLS:"))       profile.setSkills(after(line));
            else if (line.startsWith("EDUCATION:"))    profile.setEducation(after(line));
            else if (line.startsWith("NATIONALITY:"))    profile.setNationality(after(line));
            else if (line.startsWith("SUMMARY:"))      profile.setSummary(after(line));
            else if (line.startsWith("EXPERIENCE_YEARS:")) {
                try { profile.setExperienceYears(Integer.parseInt(after(line).trim())); }
                catch (NumberFormatException ignored) { profile.setExperienceYears(0); }
            }
        }
        return profile;
    }

    private String after(String line) {
        int idx = line.indexOf(":");
        return idx >= 0 ? line.substring(idx + 1).trim() : "";
    }
}
