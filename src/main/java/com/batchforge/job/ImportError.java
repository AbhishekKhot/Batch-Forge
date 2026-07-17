package com.batchforge.job;

import java.util.UUID;

public record ImportError(UUID jobId, long sourceRowNumber, String reason) {
}