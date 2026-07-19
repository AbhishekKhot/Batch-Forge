package com.batchforge.job;

import com.batchforge.common.PagedResponse;
import com.batchforge.common.error.ApiException;
import com.batchforge.storage.MinioStorageService;
import com.batchforge.storage.StorageProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final MinioStorageService storageService;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;

    public JobService(JobRepository jobRepository,
                      MinioStorageService storageService,
                      StorageProperties storageProperties,
                      ApplicationEventPublisher events) {
        this.jobRepository = jobRepository;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.events = events;
    }


    public String pingCache(String x) {
        System.out.println("### pingCache BODY RAN for " + x);
        return "val-" + x;
    }


    @Transactional
    public CreateJobResponse createImportJob(UUID orgId, UUID submittedBy) {
        String objectKey = orgId + "/" + UUID.randomUUID() + "/source.csv";
        Job job = jobRepository.save(new Job(orgId, submittedBy, objectKey));
        String uploadUrl = storageService.presignedUploadUrl(objectKey);
        Instant expiresAt = Instant.now().plus(storageProperties.uploadUrlTtl());
        return new CreateJobResponse(job.getId(), uploadUrl, expiresAt);
    }

    @Transactional
    public JobStatusResponse confirmUpload(UUID jobId, UUID orgId) {
        Job job = jobRepository.findByIdAndOrgId(jobId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found"));

        JobStatus current = job.getStatus();
        if (current == JobStatus.QUEUED) {
            return new JobStatusResponse(job.getId(), current);
        }
        if (current != JobStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_JOB_STATE",
                    "Job cannot be confirmed in status " + current);
        }

        long size = storageService.objectSize(job.getSourceObjectKey()).orElse(0L);
        if (size <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FOUND",
                    "No uploaded file found for this job");
        }

        job.markQueued();
        events.publishEvent(new JobQueuedEvent(job.getId()));
        return new JobStatusResponse(job.getId(), job.getStatus());
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID jobId, UUID orgId) {
        return jobRepository.findByIdAndOrgId(jobId, orgId)
                .map(JobResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found"));
    }

    @Transactional(readOnly = true)
    public PagedResponse<JobResponse> listJobs(UUID orgId, int page, int size, JobStatus status) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));  // total order -> stable pages
        Page<Job> jobs = (status == null)
                ? jobRepository.findByOrgId(orgId, pageable)
                : jobRepository.findByOrgIdAndStatus(orgId, status, pageable);
        return PagedResponse.of(jobs.map(JobResponse::from));
    }

    @Transactional(readOnly = true)
    public ResultResponse getResult(UUID jobId, UUID orgId) {
        Job job = jobRepository.findByIdAndOrgId(jobId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found: " + jobId));

        JobStatus status = job.getStatus();
        if (status == JobStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_FAILED",
                    "Job " + jobId + " failed; no result is available");
        }
        if (status != JobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "RESULT_NOT_READY",
                    "Job " + jobId + " is " + status + "; the result is not ready yet");
        }

        String key = job.getErrorReportObjectKey();
        if (key == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NO_ERROR_REPORT",
                    "Job completed with no row failures; no error report was generated");
        }
        return new ResultResponse(storageService.presignedDownloadUrl(key),
                Instant.now().plus(storageProperties.uploadUrlTtl()));
    }
}