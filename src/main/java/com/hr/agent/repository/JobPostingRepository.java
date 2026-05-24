package com.hr.agent.repository;

import com.hr.agent.entity.JobPosting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    List<JobPosting> findByStatus(JobPosting.JobStatus status);

    @Query("SELECT j FROM JobPosting j WHERE LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<JobPosting> findByTitleContaining(@Param("keyword") String keyword);

    List<JobPosting> findByDepartmentAndStatus(String department, JobPosting.JobStatus status);

    long countByStatus(JobPosting.JobStatus status);
}
