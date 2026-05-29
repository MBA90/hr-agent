package com.hr.agent.repository;

import com.hr.agent.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {

    @Query("SELECT a FROM Application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.id = :id")
    Optional<Application> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT a FROM Application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.candidate.id = :candidateId AND a.jobPosting.id = :jobPostingId")
    Optional<Application> findByCandidateIdAndJobPostingIdWithDetails(@Param("candidateId") Long candidateId, @Param("jobPostingId") Long jobPostingId);

    @Query("SELECT a FROM Application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.jobPosting.id = :jobId ORDER BY a.appliedAt DESC")
    List<Application> findByJobPostingIdWithDetails(@Param("jobId") Long jobId);

    @Query("SELECT a FROM Application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.jobPosting.id = :jobId AND a.score >= :minScore ORDER BY a.score DESC")
    List<Application> findTopApplications(@Param("jobId") Long jobId, @Param("minScore") double minScore);

    @Query("SELECT a FROM Application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.score IS NULL AND a.jobPosting.id = :jobId")
    List<Application> findUnscored(@Param("jobId") Long jobId);

    List<Application> findByCandidateId(Long candidateId);

    List<Application> findByCandidateIdAndStatus(Long candidateId, Application.ApplicationStatus status);

    long countByJobPostingId(Long jobPostingId);
}