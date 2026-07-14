package com.batchforge.job;

import java.util.UUID;

public record JobStatusResponse(UUID jobId, JobStatus status){}