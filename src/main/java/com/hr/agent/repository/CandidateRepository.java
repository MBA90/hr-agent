package com.hr.agent.repository;

import com.hr.agent.entity.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    Optional<Candidate> findByEmail(String email);

    @Query("SELECT c FROM Candidate c JOIN FETCH c.jobPosting WHERE c.id = :id")
    Optional<Candidate> findByIdWithJobPosting(@Param("id") Long id);

    List<Candidate> findByJobPostingId(Long jobPostingId);

    @Query("SELECT c FROM Candidate c WHERE c.jobPosting.id = :jobId AND c.score >= :minScore ORDER BY c.score DESC")
    List<Candidate> findTopCandidates(@Param("jobId") Long jobId, @Param("minScore") double minScore);

    List<Candidate> findByJobPostingIdAndStatus(Long jobPostingId, Candidate.CandidateStatus status);

    @Query("SELECT c FROM Candidate c WHERE c.score IS NULL AND c.jobPosting.id = :jobId")
    List<Candidate> findUnscored(@Param("jobId") Long jobId);

    long countByJobPostingId(Long jobPostingId);
}
