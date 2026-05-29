package com.hr.agent.tools;

import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.Interview;
import com.hr.agent.entity.JobPosting;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.InterviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailToolTest {

    @Mock JavaMailSender mailSender;
    @Mock CandidateRepository candidateRepository;
    @Mock ApplicationRepository applicationRepository;
    @Mock InterviewRepository interviewRepository;

    private EmailTool emailTool;

    @BeforeEach
    void setUp() {
        emailTool = new EmailTool(mailSender, candidateRepository, applicationRepository, interviewRepository);
        ReflectionTestUtils.setField(emailTool, "fromEmail", "hr@company.com");
    }

    // ── sendInterviewInvitation ───────────────────────────────────────────────

    @Test
    void sendInterviewInvitation_sendsEmailWithCorrectContent() {
        Candidate candidate = candidate(1L, "Alice", "alice@example.com");
        Interview interview = interview(1L, candidate, job(1L, "Java Developer"));
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(interviewRepository.findByIdWithAssociations(1L)).thenReturn(Optional.of(interview));

        String result = emailTool.sendInterviewInvitation(1L, 1L);

        assertThat(result).contains("sent successfully");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getTo()).contains("alice@example.com");
        assertThat(msg.getFrom()).isEqualTo("hr@company.com");
        assertThat(msg.getSubject()).contains("Java Developer");
        assertThat(msg.getText()).contains("Alice");
        assertThat(msg.getText()).contains("TECHNICAL");
    }

    @Test
    void sendInterviewInvitation_returnsFailureMessageOnMailError() {
        Candidate candidate = candidate(1L, "Alice", "alice@example.com");
        Interview interview = interview(1L, candidate, job(1L, "Java Developer"));
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));
        when(interviewRepository.findByIdWithAssociations(1L)).thenReturn(Optional.of(interview));
        doThrow(new MailSendException("SMTP error")).when(mailSender).send(any(SimpleMailMessage.class));

        String result = emailTool.sendInterviewInvitation(1L, 1L);

        assertThat(result).contains("Failed to send email");
    }

    // ── sendRejectionEmail ────────────────────────────────────────────────────

    @Test
    void sendRejectionEmail_sendsEmailAndSetsStatusToRejected() {
        Application application = application(1L, candidate(1L, "Bob", "bob@example.com"),
                job(1L, "DevOps Engineer"));
        when(applicationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(application));

        String result = emailTool.sendRejectionEmail(1L);

        assertThat(result).contains("sent successfully");
        assertThat(application.getStatus()).isEqualTo(Application.ApplicationStatus.REJECTED);
        verify(applicationRepository).save(application);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("Bob");
        assertThat(captor.getValue().getSubject()).contains("DevOps Engineer");
    }

    // ── sendOfferEmail ────────────────────────────────────────────────────────

    @Test
    void sendOfferEmail_sendsEmailAndSetsStatusToOfferSent() {
        Application application = application(1L, candidate(1L, "Carol", "carol@example.com"),
                job(1L, "Senior Developer"));
        when(applicationRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(application));

        String result = emailTool.sendOfferEmail(1L, "Salary: 25,000 AED, Start: July 1st");

        assertThat(result).contains("sent successfully");
        assertThat(application.getStatus()).isEqualTo(Application.ApplicationStatus.OFFER_SENT);
        verify(applicationRepository).save(application);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("25,000 AED");
    }

    // ── sendCustomEmail ───────────────────────────────────────────────────────

    @Test
    void sendCustomEmail_sendsEmailWithProvidedSubjectAndBody() {
        Candidate candidate = candidate(1L, "Dave", "dave@example.com");
        when(candidateRepository.findById(1L)).thenReturn(Optional.of(candidate));

        String result = emailTool.sendCustomEmail(1L, "Follow-up", "Please send your references.");

        assertThat(result).contains("sent successfully");
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("Follow-up");
        assertThat(captor.getValue().getText()).isEqualTo("Please send your references.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate candidate(Long id, String name, String email) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setFullName(name);
        c.setEmail(email);
        return c;
    }

    private JobPosting job(Long id, String title) {
        JobPosting j = new JobPosting();
        j.setId(id);
        j.setTitle(title);
        return j;
    }

    private Application application(Long id, Candidate candidate, JobPosting job) {
        Application a = new Application();
        a.setId(id);
        a.setCandidate(candidate);
        a.setJobPosting(job);
        a.setStatus(Application.ApplicationStatus.SHORTLISTED);
        return a;
    }

    private Interview interview(Long id, Candidate candidate, JobPosting job) {
        Application app = application(id, candidate, job);
        Interview i = new Interview();
        i.setId(id);
        i.setApplication(app);
        i.setScheduledAt(LocalDateTime.of(2026, 6, 20, 10, 0));
        i.setInterviewType("TECHNICAL");
        i.setInterviewerName("Jane Smith");
        i.setDurationMinutes(60);
        i.setStatus(Interview.InterviewStatus.SCHEDULED);
        return i;
    }
}