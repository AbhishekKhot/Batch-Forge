package com.batchforge.job;

public record ContactRow(
        long sourceRowNumber,
        String email,
        String firstName,
        String lastName,
        String phone,
        String rowHash) {
}