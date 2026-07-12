package com.batchforge.auth;

import com.batchforge.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-definitely-long-enough-256-bits!!";
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private BatchForgeUserDetails sampleUser(UUID userId, UUID orgId) {
        return new BatchForgeUserDetails(userId, orgId, "owner@example.com", "hash", Role.ORG_OWNER);
    }

    @Test
    void roundTripsClaims() {
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(15), REFRESH_TTL));
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        String token = service.generateAccessToken(sampleUser(userId, orgId));
        AccessTokenClaims claims = service.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.orgId()).isEqualTo(orgId);
        assertThat(claims.email()).isEqualTo("owner@example.com");
        assertThat(claims.role()).isEqualTo(Role.ORG_OWNER);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(-1), REFRESH_TTL));
        String token = service.generateAccessToken(sampleUser(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> service.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentKey() {
        JwtService issuer = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(15), REFRESH_TTL));
        JwtService other = new JwtService(new JwtProperties(
                "a-totally-different-secret-key-also-long-enough-1234", Duration.ofMinutes(15), REFRESH_TTL));

        String token = issuer.generateAccessToken(sampleUser(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> other.parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedToken() {
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(15), REFRESH_TTL));
        String token = service.generateAccessToken(sampleUser(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> service.parseAccessToken(tamperPayload(token)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsNonAccessToken() {
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(15), REFRESH_TTL));
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String refreshLike = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("orgId", UUID.randomUUID().toString())
                .claim("email", "x@example.com")
                .claim("role", "MEMBER")
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> service.parseAccessToken(refreshLike))
                .isInstanceOf(JwtException.class);
    }

    private String tamperPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = (payload[0] == 'A') ? 'B' : 'A';
        parts[1] = new String(payload);
        return parts[0] + "." + parts[1] + "." + parts[2];
    }
}