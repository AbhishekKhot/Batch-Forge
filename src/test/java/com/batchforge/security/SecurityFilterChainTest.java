package com.batchforge.security;

import com.batchforge.auth.BatchForgeUserDetails;
import com.batchforge.auth.JwtService;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import com.batchforge.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @Test
    void protectedRouteWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/test/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    void protectedRouteWithValidTokenIsAccepted() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(
                new BatchForgeUserDetails(userId, orgId, "admin@example.com", "irrelevant-hash", Role.ADMIN));

        mockMvc.perform(get("/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.orgId").value(orgId.toString()))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void protectedRouteWithGarbageTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/test/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    void authRoutesRemainPublic() throws Exception {
        String login = """
                { "email": "nobody@nowhere.com", "password": "whatever12" }
                """;
        // Reaches AuthService (INVALID_CREDENTIALS) rather than being blocked by the chain (UNAUTHENTICATED).
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }
}