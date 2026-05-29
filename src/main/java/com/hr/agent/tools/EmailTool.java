package com.hr.agent.tools;

import com.hr.agent.entity.Application;
import com.hr.agent.entity.Candidate;
import com.hr.agent.entity.Interview;
import com.hr.agent.repository.ApplicationRepository;
import com.hr.agent.repository.CandidateRepository;
import com.hr.agent.repository.InterviewRepository;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailTool {

    private final JavaMailSender mailSender;
    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;

    @Value("${spring.mail.username}")
    private String fromEmail;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEEE, MMMM dd yyyy 'at' hh:mm a");

    // ── Interview Invitation ──────────────────────────────────────────────────

    @Tool("Send an interview invitation email to a candidate. " +
          "Input: candidateId (Long), interviewId (Long).")
    public String sendInterviewInvitation(Long candidateId, Long interviewId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        Interview interview = interviewRepository.findByIdWithAssociations(interviewId)
                .orElseThrow(() -> new IllegalArgumentException("Interview not found"));

        String jobTitle = interview.getApplication().getJobPosting().getTitle();
        String subject = "Interview Invitation — " + jobTitle;
        String body = String.format("""
            Dear %s,

            Congratulations! We are pleased to invite you for a %s interview for the position of %s.

            Interview Details:
            ─────────────────
            Date & Time : %s
            Duration    : %d minutes
            Type        : %s
            Interviewer : %s
            %s

            Please confirm your attendance by replying to this email.

            We look forward to meeting you!

            Best regards,
            HR Recruitment Team
            """,
            candidate.getFullName(),
            interview.getInterviewType(),
            jobTitle,
            interview.getScheduledAt().format(FMT),
            interview.getDurationMinutes(),
            interview.getInterviewType(),
            interview.getInterviewerName() != null ? interview.getInterviewerName() : "HR Team",
            interview.getMeetingLink() != null ? "Meeting Link: " + interview.getMeetingLink() : ""
        );

        return sendEmail(candidate.getEmail(), subject, body, "Interview invitation");
    }

    // ── Rejection Email ───────────────────────────────────────────────────────

    @Tool("Send a polite rejection email to a candidate for a specific application. " +
          "Input: applicationId (Long).")
    public String sendRejectionEmail(Long applicationId) {
        Application application = applicationRepository.findByIdWithDetails(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        Candidate candidate = application.getCandidate();
        String jobTitle = application.getJobPosting().getTitle();

        String subject = "Application Update — " + jobTitle;
        String body = String.format("""
            Dear %s,

            Thank you for taking the time to apply for the %s position and for your interest in joining our team.

            After careful consideration of your application, we regret to inform you that we will not be
            moving forward with your candidacy at this time. This decision was not easy, as we received
            many strong applications.

            We encourage you to apply for future openings that match your skills and experience.
            We wish you the very best in your job search.

            Kind regards,
            HR Recruitment Team
            """,
            candidate.getFullName(), jobTitle
        );

        application.setStatus(Application.ApplicationStatus.REJECTED);
        applicationRepository.save(application);

        return sendEmail(candidate.getEmail(), subject, body, "Rejection email");
    }

    // ── Offer Email ───────────────────────────────────────────────────────────

    @Tool("Send a job offer email to a candidate. " +
          "Input: applicationId (Long), offerDetails (String — salary, start date, etc).")
    public String sendOfferEmail(Long applicationId, String offerDetails) {
        Application application = applicationRepository.findByIdWithDetails(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found"));

        Candidate candidate = application.getCandidate();
        String jobTitle = application.getJobPosting().getTitle();

        String subject = "Job Offer — " + jobTitle;
        String body = String.format("""
            Dear %s,

            We are thrilled to offer you the position of %s at our organization!

            Offer Details:
            ─────────────
            %s

            Please review the attached formal offer letter and confirm your acceptance within 3 business days.

            We are excited to have you join our team!

            Best regards,
            HR Recruitment Team
            """,
            candidate.getFullName(), jobTitle, offerDetails
        );

        application.setStatus(Application.ApplicationStatus.OFFER_SENT);
        applicationRepository.save(application);

        return sendEmail(candidate.getEmail(), subject, body, "Job offer email");
    }

    // ── Custom Email ──────────────────────────────────────────────────────────

    @Tool("Send a custom email to a candidate. " +
          "Input: candidateId (Long), subject (String), body (String).")
    public String sendCustomEmail(Long candidateId, String subject, String body) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        return sendEmail(candidate.getEmail(), subject, body, "Custom email");
    }

    // ── Internal Helper ───────────────────────────────────────────────────────

    private String sendEmail(String to, String subject, String body, String type) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("{} sent to {}", type, to);
            return type + " sent successfully to " + to;
        } catch (Exception e) {
            log.error("Failed to send {} to {}: {}", type, to, e.getMessage());
            return "Failed to send email to " + to + ": " + e.getMessage();
        }
    }
}