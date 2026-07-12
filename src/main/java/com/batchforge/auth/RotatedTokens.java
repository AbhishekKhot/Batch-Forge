package com.batchforge.auth;

import java.util.UUID;

public record RotatedTokens(String refreshToken, UUID userId) {
}