package com.hr.agent.tools;

import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
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

    @Mock ApplicationRepository applicationRepository;
    @Mock OllamaChatModel ollamaChatModel;

    @TempDir Path tempDir;

    private CvParserTool cvParserTool;

    @BeforeEach
    void setUp() {
        cvParserTool = new CvParserTool(applicationRepository, ollamaChatModel);
        ReflectionTestUtils.setField(cvParserTool, "cvStoragePath", tempDir.toString() + "/");
    }

    // ── Guard cases ───────────────────────────────────────────────────────────

    @Test
    void parseCandidateCv_throwsWhenApplicationNotFound() {
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(99L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> cvParserTool.parseCandidateCv(99L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
        verify(ollamaChatModel, never()).generate(anyString());
    }

    @Test
    void parseCandidateCv_returnsMessageWhenNoCvFileAttached() {
        Application app = application(1L, "Alice", 1L);
        app.setCvFilePath(null);
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));

        String result = cvParserTool.parseCandidateCv(1L, 1L);

        assertThat(result).contains("No CV file");
        verify(ollamaChatModel, never()).generate(anyString());
    }

    @Test
    void parseCandidateCv_returnsMessageWhenCvFilePathIsBlank() {
        Application app = application(1L, "Alice", 1L);
        app.setCvFilePath("   ");
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));

        String result = cvParserTool.parseCandidateCv(1L, 1L);

        assertThat(result).contains("No CV file");
    }

    @Test
    void parseCandidateCv_returnsErrorWhenFileDoesNotExist() {
        Application app = application(1L, "Alice", 1L);
        app.setCvFilePath("/nonexistent/path/cv.pdf");
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));

        String result = cvParserTool.parseCandidateCv(1L, 1L);

        assertThat(result).contains("Failed to parse CV");
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void parseCandidateCv_parsesProfileOntoApplicationAndMarksReviewed() throws IOException {
        Path pdfPath = tempDir.resolve("alice_cv.pdf");
        createMinimalPdf(pdfPath, "Alice Johnson\nJava Developer at Acme Corp\n5 years experience\nBSc Computer Science\nJava Spring Boot Docker");

        Application app = application(1L, "Alice Johnson", 1L);
        app.setCvFilePath(pdfPath.toString());
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(1L, 1L))
                .thenReturn(Optional.of(app));
        when(ollamaChatModel.generate(anyString())).thenReturn("""
                FULL_NAME: Alice Johnson
                CURRENT_ROLE: Java Developer at Acme Corp
                EXPERIENCE_YEARS: 5
                SKILLS: Java, Spring Boot, Docker
                EDUCATION: BSc Computer Science, State University
                SUMMARY: Experienced Java developer with 5 years in enterprise applications.
                """);

        String result = cvParserTool.parseCandidateCv(1L, 1L);

        assertThat(result).contains("Alice Johnson");
        assertThat(result).contains("Java, Spring Boot, Docker");
        // Snapshot lands on the application, NOT the candidate
        assertThat(app.getSkills()).isEqualTo("Java, Spring Boot, Docker");
        assertThat(app.getExperienceYears()).isEqualTo(5);
        assertThat(app.getCurrentRole()).isEqualTo("Java Developer at Acme Corp");
        assertThat(app.getEducation()).isEqualTo("BSc Computer Science, State University");
        assertThat(app.getStatus()).isEqualTo(Application.ApplicationStatus.CV_REVIEWED);
        verify(applicationRepository).save(app);
    }

    @Test
    void parseCandidateCv_handlesUnparseableLlmResponse() throws IOException {
        Path pdfPath = tempDir.resolve("bob_cv.pdf");
        createMinimalPdf(pdfPath, "Bob Smith CV content");

        Application app = application(2L, "Bob Smith", 1L);
        app.setCvFilePath(pdfPath.toString());
        when(applicationRepository.findByCandidateIdAndJobPostingIdWithDetails(2L, 1L))
                .thenReturn(Optional.of(app));
        when(ollamaChatModel.generate(anyString())).thenReturn("I cannot parse this CV.");

        String result = cvParserTool.parseCandidateCv(2L, 1L);

        assertThat(result).contains("Bob Smith");
        verify(applicationRepository).save(app);
        assertThat(app.getExperienceYears()).isEqualTo(0); // defaulted from null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Application application(Long candidateId, String candidateName, Long jobId) {
        Candidate c = new Candidate();
        c.setId(candidateId);
        c.setFullName(candidateName);

        JobPosting j = new JobPosting();
        j.setId(jobId);
        j.setTitle("Java Developer");

        Application a = new Application();
        a.setId(candidateId);
        a.setAppRefNo("APP_" + candidateId);
        a.setCandidate(c);
        a.setJobPosting(j);
        a.setStatus(Application.ApplicationStatus.APPLIED);
        return a;
    }

    private void createMinimalPdf(Path path, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
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
