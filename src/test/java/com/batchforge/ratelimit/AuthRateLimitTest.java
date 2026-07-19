package com.batchforge.ratelimit;

import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
@TestPropertySource(properties = {
        "batchforge.rate-limit.auth.enabled=true",
        "batchforge.rate-limit.auth.capacity=3",
        "batchforge.rate-limit.auth.refill-tokens=3",
        "batchforge.rate-limit.auth.refill-period=PT1M"
})
class AuthRateLimitTest {

    private static final String LOGIN_BODY = """
            {"email":"nobody@example.com","password":"wrongpass1"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authAttemptsFromOneIpAreLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login").with(ip("203.0.113.10"))
                            .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/auth/login").with(ip("203.0.113.10"))
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void authLimitIsTrackedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/login").with(ip("203.0.113.20"))
                    .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY));
        }
        mockMvc.perform(post("/auth/login").with(ip("203.0.113.20"))
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/auth/login").with(ip("203.0.113.21"))
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}