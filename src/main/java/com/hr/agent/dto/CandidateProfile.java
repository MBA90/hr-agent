package com.hr.agent.dto;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateProfile {
    private String fullName;
    private String email;
    private String phone;
    private String currentRole;
    private String education;
    private String skills;
    private Integer experienceYears;
    private String nationality;
    private String summary;
}
