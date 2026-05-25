package com.hr.agent.service;

import com.hr.agent.dto.auth.AuthResponse;
import com.hr.agent.dto.auth.LoginRequest;
import com.hr.agent.dto.auth.RegisterRequest;
import com.hr.agent.entity.AppUser;
import com.hr.agent.entity.Role;
import com.hr.agent.exception.DuplicateResourceException;
import com.hr.agent.repository.AppUserRepository;
import com.hr.agent.repository.RoleRepository;
import com.hr.agent.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (appUserRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username already taken: " + request.getUsername());
        }
        if (appUserRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email already registered: " + request.getEmail());
        }

        Role recruiterRole = roleRepository.findByName("ROLE_RECRUITER")
                .orElseThrow(() -> new IllegalStateException("ROLE_RECRUITER not seeded in DB"));

        AppUser user = AppUser.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .enabled(true)
                .build();

        appUserRepository.save(user);  // flush to generate ID before addRole
        user.addRole(recruiterRole);   // UserRoleId needs the generated ID
        // Both saves are in the same transaction — if anything fails, both roll back

        log.info("Registered new user: {}", user.getUsername());
        return buildResponse(user, jwtUtil.generateToken(user));
    }

    public AuthResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        AppUser user = (AppUser) auth.getPrincipal();
        log.info("User logged in: {}", user.getUsername());
        return buildResponse(user, jwtUtil.generateToken(user));
    }

    private AuthResponse buildResponse(AppUser user, String token) {
        Set<String> roleNames = user.getUserRoles().stream()
                .map(ur -> ur.getRole().getName())
                .collect(Collectors.toSet());

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .roles(roleNames)
                .build();
    }
}