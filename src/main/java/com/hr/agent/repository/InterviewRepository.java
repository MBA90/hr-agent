package com.hr.agent.repository;

import com.hr.agent.entity.Interview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    @Query("SELECT i FROM Interview i JOIN FETCH i.application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.candidate.id = :candidateId")
    List<Interview> findByCandidateId(@Param("candidateId") Long candidateId);

    @Query("SELECT i FROM Interview i JOIN FETCH i.application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE i.scheduledAt >= :from AND i.status = 'SCHEDULED' ORDER BY i.scheduledAt ASC")
    List<Interview> findUpcoming(@Param("from") LocalDateTime from);

    @Query("SELECT i FROM Interview i JOIN FETCH i.application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE a.jobPosting.id = :jobPostingId")
    List<Interview> findByJobPostingId(@Param("jobPostingId") Long jobPostingId);

    @Query("SELECT i FROM Interview i JOIN FETCH i.application a JOIN FETCH a.candidate JOIN FETCH a.jobPosting WHERE i.id = :id")
    Optional<Interview> findByIdWithAssociations(@Param("id") Long id);

    @Query("SELECT COUNT(i) > 0 FROM Interview i WHERE i.scheduledAt BETWEEN :start AND :end AND i.status NOT IN ('CANCELLED', 'NO_SHOW')")
    boolean existsConflict(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}