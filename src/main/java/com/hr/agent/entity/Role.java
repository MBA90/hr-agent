package com.hr.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;

@Entity
@Table(name = "APP_ROLE")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Role implements GrantedAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_role_seq")
    @SequenceGenerator(name = "app_role_seq", sequenceName = "SEQ_APP_ROLE", allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "NAME", nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "DESCRIPTION", length = 200)
    private String description;

    @Override
    public String getAuthority() {
        return name;
    }
}