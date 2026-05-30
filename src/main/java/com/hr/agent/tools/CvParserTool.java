package com.hr.agent.tools;

import com.hr.agent.dto.CandidateProfile;
import com.hr.agent.entity.Application;
import com.hr.agent.repository.ApplicationRepository;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
@Slf4j
public class CvParserTool {

    private final ApplicationRepository applicationRepository;
    private final OllamaChatModel ollamaChatModel;

    @Value("${hr.agent.cv-storage-path:./cv-uploads/}")
    private String cvStoragePath;

    @Tool("Parse the CV of a candidate's application for a job and extract their skills, experience, " +
          "education, and current role. The parsed profile is stored on that application only. " +
          "Input: candidateId (Long), jobId (Long). Returns a summary of the parsed profile.")
    public String parseCandidateCv(Long candidateId, Long jobId) {
        log.info("Parsing CV for candidateId={} jobId={}", candidateId, jobId);

        Application application = applicationRepository
                .findByCandidateIdAndJobPostingIdWithDetails(candidateId, jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No application found for candidateId=" + candidateId + " jobId=" + jobId));

        String candidateName = application.getCandidate().getFullName();

        if (application.getCvFilePath() == null || application.getCvFilePath().isBlank()) {
            return "No CV file found for application " + application.getAppRefNo()
                    + " (" + candidateName + "). Please upload a CV first.";
        }

        try {
            CandidateProfile profile = parseInto(application, application.getCvFilePath());
            application.setStatus(Application.ApplicationStatus.CV_REVIEWED);
            applicationRepository.save(application);

            log.info("CV parsed successfully for application={}", application.getAppRefNo());
            return String.format(
                "CV parsed for %s (application %s): Skills=[%s], Experience=%d yrs, Education=%s, Role=%s",
                candidateName, application.getAppRefNo(), profile.getSkills(),
                application.getExperienceYears(), profile.getEducation(), profile.getCurrentRole()
            );

        } catch (Exception e) {
            log.error("Failed to parse CV for application of candidateId={} jobId={}", candidateId, jobId, e);
            return "Failed to parse CV for application of candidate " + candidateId + ": " + e.getMessage();
        }
    }

    /** Extracts the CV at {@code cvFilePath} and writes the parsed profile onto the application snapshot. */
    private CandidateProfile parseInto(Application application, String cvFilePath) throws Exception {
        String rawText = extractTextFromPdf(cvFilePath);
        CandidateProfile profile = extractProfileWithLlm(rawText);

        int experienceYears = profile.getExperienceYears() != null ? profile.getExperienceYears() : 0;
        application.setSkills(profile.getSkills());
        application.setExperienceYears(experienceYears);
        application.setEducation(profile.getEducation());
        application.setCurrentRole(profile.getCurrentRole());
        application.setNationality(profile.getNationality());
        return profile;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractTextFromPdf(String filePath) throws Exception {
        File pdfFile = new File(filePath);
        if (!pdfFile.exists()) {
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
            else if (line.startsWith("NATIONALITY:"))  profile.setNationality(after(line));
            else if (line.startsWith("SUMMARY:"))      profile.setSummary(after(line));
            else if (line.startsWith("EXPERIENCE_YEARS:")) {
                try {
                    String years = after(line).trim().replaceAll("[^0-9].*$", "");
                    profile.setExperienceYears(years.isEmpty() ? 0 : Integer.parseInt(years));
                } catch (NumberFormatException ignored) {
                    profile.setExperienceYears(0);
                }
            }
        }
        return profile;
    }

    private String after(String line) {
        int idx = line.indexOf(":");
        return idx >= 0 ? line.substring(idx + 1).trim() : "";
    }
}