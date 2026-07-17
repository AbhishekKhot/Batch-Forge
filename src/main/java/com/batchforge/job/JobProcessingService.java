package com.batchforge.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
public class JobProcessingService {

    private final JobRepository jobRepository;

    public JobProcessingService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Decide whether to process this delivery, in its own committed transaction.
     * QUEUED -> transition to PROCESSING and return true; already PROCESSING -> return true
     * (a retry or redelivery resuming an in-flight job); COMPLETED/FAILED/unknown -> return false.
     * Re-entrancy on PROCESSING is what lets listener retry actually re-run the work.
     */
    @Transactional
    public boolean claim(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Received message for unknown job {}; discarding", jobId);
            return false;
        }
        JobStatus status = job.getStatus();
        if (status == JobStatus.QUEUED) {
            job.markProcessing();
            return true;
        }
        if (status == JobStatus.PROCESSING) {
            return true;
        }
        log.info("Job {} is {}; ignoring redelivery", jobId, status);
        return false;
    }

    @Transactional
    public void complete(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(Job::markCompleted);
    }

    @Transactional
    public void markFailed(UUID jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != JobStatus.COMPLETED) {
                job.markFailed();
            }
        });
    }
}