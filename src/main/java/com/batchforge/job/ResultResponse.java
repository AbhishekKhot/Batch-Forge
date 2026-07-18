package com.batchforge.job;

import java.time.Instant;

public record ResultResponse(String downloadUrl, Instant expiresAt) {
}