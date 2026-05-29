package com.hr.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "JOB_POSTING")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "job_seq")
    @SequenceGenerator(name = "job_seq", sequenceName = "SEQ_JOB_POSTING", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "TITLE", nullable = false, length = 200)
    private String title;

    @Column(name = "DEPARTMENT", length = 100)
    private String department;

    @Column(name = "DESCRIPTION", length = 4000)
    private String description;

    @Column(name = "REQUIRED_SKILLS", length = 2000)
    private String requiredSkills;

    @Column(name = "EXPERIENCE_YEARS")
    private Integer experienceYears;

    @Column(name = "LOCATION", length = 100)
    private String location;

    @Column(name = "SALARY_MIN")
    private Double salaryMin;

    @Column(name = "SALARY_MAX")
    private Double salaryMax;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 20)
    @Builder.Default
    private JobStatus status = JobStatus.OPEN;

    @CreationTimestamp
    @Column(name = "CREATED_AT", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "jobPosting", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Application> applications;

    public enum JobStatus { OPEN, CLOSED, ON_HOLD, FILLED }
}
