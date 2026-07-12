package com.batchforge.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByOrgId(UUID orgId, Pageable pageable);

    Page<Job> findByOrgIdAndStatus(UUID orgId, JobStatus status, Pageable pageable);
}