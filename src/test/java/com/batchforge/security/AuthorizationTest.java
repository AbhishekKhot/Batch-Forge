package com.batchforge.security;

import com.batchforge.auth.BatchForgeUserDetails;
import com.batchforge.auth.JwtService;
import com.batchforge.job.Job;
import com.batchforge.job.JobRepository;
import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
class AuthorizationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JobRepository jobRepository;

    @Test
    void adminOnlyEndpointAllowsAdmin() throws Exception {
        String token = tokenFor(UUID.randomUUID(), Role.ADMIN, "admin@example.com");
        mockMvc.perform(get("/test/admin-only").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void adminOnlyEndpointForbidsNonAdmin() throws Exception {
        String token = tokenFor(UUID.randomUUID(), Role.MEMBER, "member@example.com");
        mockMvc.perform(get("/test/admin-only").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void queriesAreScopedToTheTokensOrg() throws Exception {
        UUID orgA = seedOrgWithJobs("Org A", "a@example.com", 2);
        UUID orgB = seedOrgWithJobs("Org B", "b@example.com", 1);

        // Both tokens are ADMIN, yet each sees only its own org's jobs — role does not cross tenants.
        String tokenA = tokenFor(orgA, Role.ADMIN, "a@example.com");
        String tokenB = tokenFor(orgB, Role.ADMIN, "b@example.com");

        mockMvc.perform(get("/test/my-jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(get("/test/my-jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    private String tokenFor(UUID orgId, Role role, String email) {
        return jwtService.generateAccessToken(
                new BatchForgeUserDetails(UUID.randomUUID(), orgId, email, "irrelevant-hash", role));
    }

    private UUID seedOrgWithJobs(String orgName, String userEmail, int jobCount) {
        Organization org = organizationRepository.save(new Organization(orgName));
        User user = userRepository.save(new User(userEmail, "hash", org.getId(), Role.ADMIN));
        for (int i = 0; i < jobCount; i++) {
            jobRepository.save(new Job(org.getId(), user.getId(), "uploads/file-" + i + ".csv"));
        }
        return org.getId();
    }
}