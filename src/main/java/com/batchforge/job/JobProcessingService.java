package com.batchforge.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class JobProcessingService {

    private final JobRepository jobRepository;
    private final ImportedRecordRepository importedRecordRepository;
    private final ImportErrorRepository importErrorRepository;

    public JobProcessingService(JobRepository jobRepository,
                                ImportedRecordRepository importedRecordRepository,
                                ImportErrorRepository importErrorRepository) {
        this.jobRepository = jobRepository;
        this.importedRecordRepository = importedRecordRepository;
        this.importErrorRepository = importErrorRepository;
    }

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
            return true;   // retry / redelivery of an in-flight job -> resume
        }
        log.info("Job {} is {}; ignoring redelivery", jobId, status);
        return false;
    }

    @Transactional(readOnly = true)
    public ProcessingContext loadProcessingContext(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
        return new ProcessingContext(job.getSourceObjectKey(), job.getLastProcessedRow());
    }

    /** One atomic checkpoint: imported rows, error rows, and the progress update commit together. */
    @Transactional
    public void flushBatch(UUID jobId, List<ImportedRecord> importedRows, List<ImportError> errorRows,
                           long lastProcessedRow, long processedDelta, long failedDelta) {
        if (!importedRows.isEmpty()) {
            importedRecordRepository.insertBatch(importedRows);
        }
        if (!errorRows.isEmpty()) {
            importErrorRepository.insertBatch(errorRows);
        }
        jobRepository.findById(jobId).ifPresent(job ->
                job.advanceProgress(lastProcessedRow, processedDelta, failedDelta));
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

    public record ProcessingContext(String sourceObjectKey, long resumeFrom) {
    }

    @Transactional(readOnly = true)
    public List<ImportError> getErrors(UUID jobId) {
        return importErrorRepository.findByJobIdOrderByRow(jobId);
    }

    @Transactional
    public void attachErrorReport(UUID jobId, String errorReportObjectKey) {
        jobRepository.findById(jobId).ifPresent(job -> job.attachErrorReport(errorReportObjectKey));
    }
}