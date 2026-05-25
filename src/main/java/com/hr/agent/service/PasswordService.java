package com.hr.agent.service;

import com.hr.agent.dto.auth.ChangePasswordRequest;
import com.hr.agent.dto.auth.ForgotPasswordRequest;
import com.hr.agent.dto.auth.ResetPasswordRequest;
import com.hr.agent.entity.AppUser;
import com.hr.agent.entity.PasswordResetToken;
import com.hr.agent.exception.InvalidTokenException;
import com.hr.agent.repository.AppUserRepository;
import com.hr.agent.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordService {

    private final AppUserRepository appUserRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${hr.password-reset.expiration-minutes:60}")
    private int expirationMinutes;

    @Value("${hr.password-reset.reset-url:http://localhost:8080/reset-password}")
    private String resetUrl;

    @Value("${spring.mail.username}")
    private String fromEmail;

    // ── Change password (authenticated user) ─────────────────────────────────

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match");
        }

        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new IllegalArgumentException("New password must be different from the current password");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        appUserRepository.save(user);
        log.info("Password changed for user: {}", username);
    }

    // ── Forgot password ───────────────────────────────────────────────────────

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        Optional<AppUser> userOpt = appUserRepository.findByEmail(request.getEmail());

        // Always return success — never reveal whether the email exists (prevent enumeration)
        if (userOpt.isEmpty()) {
            log.debug("Forgot-password requested for unknown email: {}", request.getEmail());
            return;
        }

        AppUser user = userOpt.get();

        // Invalidate any existing tokens for this user
        tokenRepository.deleteAllByUserId(user.getId());

        PasswordResetToken resetToken = PasswordResetToken.builder()
                .token(UUID.randomUUID().toString())
                .user(user)
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                .build();

        tokenRepository.save(resetToken);
        sendResetEmail(user, resetToken.getToken());
        log.info("Password reset token issued for user: {}", user.getUsername());
    }

    // ── Reset password (token from email) ────────────────────────────────────

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirmation do not match");
        }

        PasswordResetToken resetToken = tokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new InvalidTokenException("Password reset token is invalid or does not exist"));

        if (resetToken.isUsed()) {
            throw new InvalidTokenException("Password reset token has already been used");
        }

        if (resetToken.isExpired()) {
            throw new InvalidTokenException("Password reset token has expired — please request a new one");
        }

        AppUser user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        appUserRepository.save(user);

        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        log.info("Password reset successfully for user: {}", user.getUsername());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void sendResetEmail(AppUser user, String token) {
        String link = resetUrl + "?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(user.getEmail());
        message.setSubject("HR Agent — Password Reset Request");
        message.setText(
            "Hi " + user.getFullName() + ",\n\n" +
            "We received a request to reset your HR Agent password.\n\n" +
            "Click the link below to reset your password (expires in " + expirationMinutes + " minutes):\n" +
            link + "\n\n" +
            "If you did not request a password reset, you can safely ignore this email.\n\n" +
            "— HR Agent Team"
        );

        mailSender.send(message);
        log.debug("Reset email sent to: {}", user.getEmail());
    }
}