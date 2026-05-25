package com.hr.agent.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {

    @NotBlank
    private String token;

    @NotBlank
    @Size(min = 5, max = 100)
    private String newPassword;

    @NotBlank
    private String confirmPassword;
}