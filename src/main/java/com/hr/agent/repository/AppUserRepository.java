package com.hr.agent.repository;

import com.hr.agent.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // Full load with roles — used by Spring Security on login
    @Query("SELECT u FROM AppUser u LEFT JOIN FETCH u.userRoles ur LEFT JOIN FETCH ur.role WHERE u.username = :username")
    Optional<AppUser> findByUsernameWithRoles(@Param("username") String username);

    // Plain load without roles — used for password operations
    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}