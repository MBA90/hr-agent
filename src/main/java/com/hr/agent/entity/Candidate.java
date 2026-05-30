package com.hr.agent.entity;

import jakarta.persistence.*;
import lombok.*;
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

    @Column(name = "CND_REF_NO", nullable = false, unique = true, length = 20)
    private String cndRefNo;

    @PrePersist
    private void assignCndRefNo() {
        if (cndRefNo == null && id != null) {
            cndRefNo = "CND_" + id;
        }
    }

    @Column(name = "FULL_NAME", nullable = false, length = 200)
    private String fullName;

    @Column(name = "EMAIL", nullable = false, unique = true, length = 200)
    private String email;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @UpdateTimestamp
    @Column(name = "UPDATED_AT")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "candidate", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Application> applications;
}