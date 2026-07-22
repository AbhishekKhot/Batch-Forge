package com.batchforge.auth;

import com.batchforge.common.error.ApiException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_TOKEN_HASH = "tokenHash";

    private final StringRedisTemplate redis;
    private final Duration refreshTokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(StringRedisTemplate redis, JwtProperties properties) {
        this.redis = redis;
        this.refreshTokenTtl = properties.refreshTokenTtl();
    }

    public String issue(UUID userId) {
        String familyId = UUID.randomUUID().toString();
        String secret = randomSecret();
        storeFamily(familyId, userId.toString(), hash(secret));
        return familyId + "." + secret;
    }

    public RotatedTokens rotate(String presentedToken) {
        Parsed parsed = parse(presentedToken);
        String key = KEY_PREFIX + parsed.familyId();

        HashOperations<String, String, String> ops = redis.opsForHash();
        Map<String, String> entries = ops.entries(key);
        if (entries.isEmpty()) {
            throw invalid();
        }

        String storedHash = entries.get(FIELD_TOKEN_HASH);
        String userId = entries.get(FIELD_USER_ID);

        if (!constantTimeEquals(storedHash, hash(parsed.secret()))) {
            redis.delete(key);
            throw invalid();
        }

        String newSecret = randomSecret();
        storeFamily(parsed.familyId(), userId, hash(newSecret));
        return new RotatedTokens(parsed.familyId() + "." + newSecret, UUID.fromString(userId));
    }

    public void revoke(String presentedToken) {
        Parsed parsed = parse(presentedToken);
        redis.delete(KEY_PREFIX + parsed.familyId());
    }

    private void storeFamily(String familyId, String userId, String tokenHash) {
        String key = KEY_PREFIX + familyId;
        redis.<String, String>opsForHash()
                .putAll(key, Map.of(FIELD_USER_ID, userId, FIELD_TOKEN_HASH, tokenHash));
        redis.expire(key, refreshTokenTtl);
    }

    private Parsed parse(String token) {
        if (token == null) {
            throw invalid();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw invalid();
        }
        return new Parsed(token.substring(0, dot), token.substring(dot + 1));
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Refresh token is invalid or expired");
    }

    private record Parsed(String familyId, String secret) {}
}