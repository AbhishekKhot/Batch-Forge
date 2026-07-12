package com.batchforge.auth;

import com.batchforge.common.error.ApiException;
import com.batchforge.support.RedisContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataRedisTest
@Import(RedisContainerConfiguration.class)
class RefreshTokenServiceTest {

    @Autowired
    private StringRedisTemplate redis;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(redis,
                new JwtProperties("unused-here", Duration.ofMinutes(15), Duration.ofDays(7)));
    }

    @Test
    void issuedTokenRotatesAndReturnsUserId() {
        UUID userId = UUID.randomUUID();
        String t1 = service.issue(userId);

        String familyId = t1.substring(0, t1.indexOf('.'));
        assertThat(redis.getExpire("refresh:" + familyId)).isPositive();

        RotatedTokens rotated = service.rotate(t1);
        assertThat(rotated.userId()).isEqualTo(userId);
        assertThat(rotated.refreshToken()).isNotEqualTo(t1);
        assertThat(rotated.refreshToken()).startsWith(familyId + ".");
    }

    @Test
    void rotatingUnknownFamilyIsRejected() {
        String bogus = UUID.randomUUID() + ".c29tZS1yYW5kb20tc2VjcmV0";
        assertThatThrownBy(() -> service.rotate(bogus)).isInstanceOf(ApiException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.rotate("no-dot-here")).isInstanceOf(ApiException.class);
    }

    @Test
    void replayingRotatedTokenRevokesEntireFamily() {
        String t1 = service.issue(UUID.randomUUID());
        RotatedTokens rotated = service.rotate(t1);
        String t2 = rotated.refreshToken();

        assertThatThrownBy(() -> service.rotate(t1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rotate(t2)).isInstanceOf(ApiException.class);
    }

    @Test
    void revokedTokenCannotRotate() {
        String t1 = service.issue(UUID.randomUUID());
        service.revoke(t1);
        assertThatThrownBy(() -> service.rotate(t1)).isInstanceOf(ApiException.class);
    }
}