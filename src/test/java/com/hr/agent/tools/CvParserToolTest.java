package com.hr.agent.tools;

import com.hr.agent.entity.Candidate;
import com.hr.agent.repository.CandidateRepository;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CvParserToolTest {

    @Mock CandidateRepository candidateRepository;
    @Mock OllamaChatModel ollamaChatModel;

    @TempDir Path tempDir;

    private CvParserTool cvParserTool;

    @BeforeEach
    void setUp() {
        cvParserTool = new CvParserTool(candidateRepository, ollamaChatModel);
        ReflectionTestUtils.setField(cvParserTool, "cvStoragePath", tempDir.toString() + "/");
    }

    // ── Guard cases ───────────────────────────────────────────────────────────

    @Test
    void parseCandidateCv_throwsWhenCandidateNotFound() {
        when(candidateRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cvParserTool.parseCandidateCv(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
        verify(ollamaChatModel, never()).generate(anyString());
    }

    @Test
    void parseCandidateCv_returnsMessageWhenNoCvFileAttached() {
        Candidate candidate = new Candidate();
        candidate.setId(1L);
        candidate.setFullName("Alice");
        candidate.setCvFilePath(null);
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));

        String result = cvParserTool.parseCandidateCv(1L);

        assertThat(result).contains("No CV file");
        verify(ollamaChatModel, never()).generate(anyString());
    }

    @Test
    void parseCandidateCv_returnsMessageWhenCvFilePathIsBlank() {
        Candidate candidate = new Candidate();
        candidate.setId(1L);
        candidate.setFullName("Alice");
        candidate.setCvFilePath("   ");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));

        String result = cvParserTool.parseCandidateCv(1L);

        assertThat(result).contains("No CV file");
    }

    @Test
    void parseCandidateCv_returnsErrorWhenFileDoesNotExist() {
        Candidate candidate = new Candidate();
        candidate.setId(1L);
        candidate.setFullName("Alice");
        candidate.setCvFilePath("/nonexistent/path/cv.pdf");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));

        String result = cvParserTool.parseCandidateCv(1L);

        assertThat(result).contains("Failed to parse CV");
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void parseCandidateCv_parsesAndSavesCandidateProfile() throws IOException {
        // Create a real minimal PDF using PDFBox
        Path pdfPath = tempDir.resolve("alice_cv.pdf");
        createMinimalPdf(pdfPath, "Alice Johnson\nJava Developer at Acme Corp\n5 years experience\nBSc Computer Science\nJava Spring Boot Docker");

        Candidate candidate = new Candidate();
        candidate.setId(1L);
        candidate.setFullName("Alice Johnson");
        candidate.setEmail("alice@example.com");
        candidate.setCvFilePath(pdfPath.toString());
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(ollamaChatModel.generate(anyString())).thenReturn("""
                FULL_NAME: Alice Johnson
                CURRENT_ROLE: Java Developer at Acme Corp
                EXPERIENCE_YEARS: 5
                SKILLS: Java, Spring Boot, Docker
                EDUCATION: BSc Computer Science, State University
                SUMMARY: Experienced Java developer with 5 years in enterprise applications.
                """);

        String result = cvParserTool.parseCandidateCv(1L);

        assertThat(result).contains("Alice Johnson");
        assertThat(result).contains("Java, Spring Boot, Docker");
        assertThat(candidate.getStatus()).isEqualTo(Candidate.CandidateStatus.CV_REVIEWED);
        assertThat(candidate.getSkills()).isEqualTo("Java, Spring Boot, Docker");
        assertThat(candidate.getExperienceYears()).isEqualTo(5);
        assertThat(candidate.getCurrentRole()).isEqualTo("Java Developer at Acme Corp");
        assertThat(candidate.getEducation()).isEqualTo("BSc Computer Science, State University");
        verify(candidateRepository).save(candidate);
    }

    @Test
    void parseCandidateCv_handlesUnparseableLlmResponse() throws IOException {
        Path pdfPath = tempDir.resolve("bob_cv.pdf");
        createMinimalPdf(pdfPath, "Bob Smith CV content");

        Candidate candidate = new Candidate();
        candidate.setId(2L);
        candidate.setFullName("Bob Smith");
        candidate.setCvFilePath(pdfPath.toString());
        when(candidateRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(ollamaChatModel.generate(anyString())).thenReturn("I cannot parse this CV.");

        String result = cvParserTool.parseCandidateCv(2L);

        // LLM returned no structured fields — tool saves with defaults and reports success
        assertThat(result).contains("Bob Smith");
        verify(candidateRepository).save(candidate);
        assertThat(candidate.getStatus()).isEqualTo(Candidate.CandidateStatus.CV_REVIEWED);
        assertThat(candidate.getExperienceYears()).isEqualTo(0); // defaulted from null
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void createMinimalPdf(Path path, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                // PDFBox doesn't support newlines in showText; write line by line
                for (String line : text.split("\n")) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -15);
                }
                cs.endText();
            }
            doc.save(path.toFile());
        }
    }
}
