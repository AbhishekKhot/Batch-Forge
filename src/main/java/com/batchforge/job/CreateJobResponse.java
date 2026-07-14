package com.batchforge.job;

import java.time.Instant;
import java.util.UUID;

public record CreateJobResponse(UUID jobId, String uploadUrl, Instant expiresAt) {
}