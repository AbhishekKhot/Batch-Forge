package com.batchforge.job;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        JobStatus status,
        JobType jobType,
        Long totalRows,
        long processedRows,
        long failedRows,
        Instant createdAt,
        Instant updatedAt) {

    static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getStatus(),
                job.getJobType(),
                job.getTotalRows(),
                job.getProcessedRows(),
                job.getFailedRows(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}