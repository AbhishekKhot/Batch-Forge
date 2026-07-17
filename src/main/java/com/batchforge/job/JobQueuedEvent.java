package com.batchforge.job;

import java.util.UUID;

public record JobQueuedEvent(UUID jobId) {
}