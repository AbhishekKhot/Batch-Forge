package com.batchforge.ratelimit;

import com.batchforge.auth.BatchForgeUserDetails;
import com.batchforge.auth.JwtService;
import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.support.MinioTestcontainersConfiguration;
import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
@TestPropertySource(properties = {
        "batchforge.rate-limit.enabled=true",
        "batchforge.rate-limit.capacity=3",
        "batchforge.rate-limit.refill-tokens=3",
        "batchforge.rate-limit.refill-period=PT1M"
})
class RateLimitTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void requestsWithinTheLimitPassAndTheNextIsRateLimited() throws Exception {
        OrgUser user = newOrgUser();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/jobs?size=2").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/jobs?size=2").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void limitsAreTrackedPerUser() throws Exception {
        OrgUser a = newOrgUser();
        OrgUser b = newOrgUser();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/jobs?size=2").header(HttpHeaders.AUTHORIZATION, "Bearer " + a.token()))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/jobs?size=2").header(HttpHeaders.AUTHORIZATION, "Bearer " + a.token()))
                .andExpect(status().isTooManyRequests());

        // b has its own bucket, unaffected by a's exhaustion
        mockMvc.perform(get("/jobs?size=2").header(HttpHeaders.AUTHORIZATION, "Bearer " + b.token()))
                .andExpect(status().isOk());
    }

    private OrgUser newOrgUser() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        String token = jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
        return new OrgUser(org.getId(), user.getId(), token);
    }

    record OrgUser(UUID orgId, UUID userId, String token) {}
}