package com.batchforge.job;

public enum JobStatus {
    PENDING,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}