package com.hr.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "INTERVIEW")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Interview {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "interview_seq")
    @SequenceGenerator(name = "interview_seq", sequenceName = "SEQ_INTERVIEW", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "APPLICATION_ID", nullable = false)
    private Application application;

    @Column(name = "SCHEDULED_AT", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "DURATION_MINUTES")
    @Builder.Default
    private Integer durationMinutes = 60;

    @Column(name = "INTERVIEW_TYPE", length = 50)
    private String interviewType;

    @Column(name = "MEETING_LINK", length = 500)
    private String meetingLink;

    @Column(name = "INTERVIEWER_NAME", length = 200)
    private String interviewerName;

    @Column(name = "NOTES", length = 2000)
    private String notes;

    @Column(name = "FEEDBACK", length = 4000)
    private String feedback;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 30)
    @Builder.Default
    private InterviewStatus status = InterviewStatus.SCHEDULED;

    @CreationTimestamp
    @Column(name = "CREATED_AT", updatable = false)
    private LocalDateTime createdAt;

    public enum InterviewStatus { SCHEDULED, CONFIRMED, COMPLETED, CANCELLED, NO_SHOW }
}