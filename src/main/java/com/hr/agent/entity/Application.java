package com.hr.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "APPLICATION",
       uniqueConstraints = @UniqueConstraint(
           columnNames = {"CANDIDATE_ID", "JOB_POSTING_ID"},
           name = "UQ_APPLICATION_CANDIDATE_JOB"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "application_seq")
    @SequenceGenerator(name = "application_seq", sequenceName = "SEQ_APPLICATION", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "APP_REF_NO", nullable = false, unique = true, length = 20)
    private String appRefNo;

    @PrePersist
    private void assignAppRefNo() {
        if (appRefNo == null && id != null) {
            appRefNo = "APP_" + id;
        }
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CANDIDATE_ID", nullable = false)
    private Candidate candidate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "JOB_POSTING_ID", nullable = false)
    private JobPosting jobPosting;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 30, nullable = false)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    // ── CV reference (active = what this application was reviewed/scored against) ──
    @Column(name = "CV_FILE_PATH", length = 500)
    private String cvFilePath;

    @Column(name = "CV_VERSION")
    @Builder.Default
    private Integer cvVersion = 0;

    // ── Profile snapshot (frozen at review time, immune to later re-uploads) ──
    @Column(name = "NATIONALITY", length = 100)
    private String nationality;

    @Column(name = "SKILLS", length = 2000)
    private String skills;

    @Column(name = "EXPERIENCE_YEARS")
    private Integer experienceYears;

    @Column(name = "EDUCATION", length = 500)
    private String education;

    @Column(name = "CURRENT_ROLE", length = 200)
    private String currentRole;

    @Column(name = "SCORE")
    private Double score;

    @Column(name = "SCORE_REASON", length = 2000)
    private String scoreReason;

    @CreationTimestamp
    @Column(name = "APPLIED_AT", updatable = false)
    private LocalDateTime appliedAt;

    @UpdateTimestamp
    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Interview> interviews;

    public enum ApplicationStatus {
        APPLIED, CV_REVIEWED, SHORTLISTED,
        INTERVIEW_SCHEDULED, INTERVIEW_DONE,
        OFFER_SENT, HIRED, REJECTED
    }
}