package com.batchforge.job;

import java.util.UUID;

public record ImportedRecord(
        UUID jobId,
        long sourceRowNumber,
        String email,
        String firstName,
        String lastName,
        String phone,
        String rowHash) {
}