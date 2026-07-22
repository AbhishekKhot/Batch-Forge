package com.batchforge.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Job {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 32)
    private JobType jobType = JobType.CSV_IMPORT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "source_object_key", nullable = false, updatable = false, length = 1024)
    private String sourceObjectKey;

    @Column(name = "result_object_key", length = 1024)
    private String resultObjectKey;

    @Column(name = "error_report_object_key", length = 1024)
    private String errorReportObjectKey;

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "processed_rows", nullable = false)
    private long processedRows = 0;

    @Column(name = "failed_rows", nullable = false)
    private long failedRows = 0;

    @Column(name = "last_processed_row", nullable = false)
    private long lastProcessedRow = 0;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Job(UUID orgId, UUID submittedBy, String sourceObjectKey) {
        this.orgId = orgId;
        this.submittedBy = submittedBy;
        this.sourceObjectKey = sourceObjectKey;
    }

    public void markProcessing() {
        this.status = JobStatus.PROCESSING;
    }

    public void markQueued(){
        this.status = JobStatus.QUEUED;
    }

    public void markCompleted() {
        this.totalRows = this.processedRows + this.failedRows;   
        this.status = JobStatus.COMPLETED;
    }

    public void markFailed() {
        this.status = JobStatus.FAILED;
    }

    public void advanceProgress(long lastProcessedRow, long processedDelta, long failedDelta) {
        this.lastProcessedRow = lastProcessedRow;
        this.processedRows += processedDelta;
        this.failedRows += failedDelta;
    }

    public void attachErrorReport(String errorReportObjectKey) {
        this.errorReportObjectKey = errorReportObjectKey;
    }
}