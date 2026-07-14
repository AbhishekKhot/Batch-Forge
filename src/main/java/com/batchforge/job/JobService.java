package com.batchforge.job;

import com.batchforge.common.error.ApiException;
import com.batchforge.storage.MinioStorageService;
import com.batchforge.storage.StorageProperties;
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

    public JobService(JobRepository jobRepository,
                      MinioStorageService storageService,
                      StorageProperties storageProperties) {
        this.jobRepository = jobRepository;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
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
        return new JobStatusResponse(job.getId(), job.getStatus());
    }
}