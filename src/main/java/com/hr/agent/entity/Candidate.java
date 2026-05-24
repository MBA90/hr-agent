package com.hr.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "CANDIDATE")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Candidate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "candidate_seq")
    @SequenceGenerator(name = "candidate_seq", sequenceName = "SEQ_CANDIDATE", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "FULL_NAME", nullable = false, length = 200)
    private String fullName;

    @Column(name = "EMAIL", nullable = false, unique = true, length = 200)
    private String email;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @Column(name = "NATIONALITY", length = 100)
    private String nationality;

    @Column(name = "CV_FILE_PATH", length = 500)
    private String cvFilePath;

    @Lob
    @Column(name = "CV_RAW_TEXT")
    private String cvRawText;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 30)
    @Builder.Default
    private CandidateStatus status = CandidateStatus.APPLIED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "JOB_POSTING_ID")
    private JobPosting jobPosting;

    @OneToMany(mappedBy = "candidate", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Interview> interviews;

    @CreationTimestamp
    @Column(name = "APPLIED_AT", updatable = false)
    private LocalDateTime appliedAt;

    @UpdateTimestamp
    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    public enum CandidateStatus {
        APPLIED, CV_REVIEWED, SHORTLISTED,
        INTERVIEW_SCHEDULED, INTERVIEW_DONE,
        OFFER_SENT, HIRED, REJECTED
    }
}
