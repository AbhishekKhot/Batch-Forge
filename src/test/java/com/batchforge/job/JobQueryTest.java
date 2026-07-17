package com.batchforge.job;

import com.batchforge.auth.BatchForgeUserDetails;
import com.batchforge.auth.JwtService;
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
class JobQueryTest {

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
    void getReturnsOwnJob() throws Exception {
        OrgUser owner = newOrgUser();
        Job job = seedJob(owner.orgId(), owner.userId());

        mockMvc.perform(get("/jobs/" + job.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.jobType").value("CSV_IMPORT"));
    }

    @Test
    void getAnotherOrgsJobIsNotFound() throws Exception {
        OrgUser owner = newOrgUser();
        OrgUser other = newOrgUser();
        Job job = seedJob(owner.orgId(), owner.userId());

        mockMvc.perform(get("/jobs/" + job.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));
    }

    @Test
    void listIsScopedToOrgAndPaginated() throws Exception {
        OrgUser owner = newOrgUser();
        OrgUser other = newOrgUser();
        seedJob(owner.orgId(), owner.userId());
        seedJob(owner.orgId(), owner.userId());
        seedJob(owner.orgId(), owner.userId());
        seedJob(other.orgId(), other.userId());

        mockMvc.perform(get("/jobs?size=2").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        OrgUser owner = newOrgUser();
        seedJob(owner.orgId(), owner.userId());
        Job queued = seedJob(owner.orgId(), owner.userId());
        queued.markQueued();
        jobRepository.save(queued);

        mockMvc.perform(get("/jobs?status=QUEUED").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("QUEUED"));
    }

    private OrgUser newOrgUser() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        String token = jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
        return new OrgUser(org.getId(), user.getId(), token);
    }

    private Job seedJob(UUID orgId, UUID userId) {
        return jobRepository.save(new Job(orgId, userId, orgId + "/" + UUID.randomUUID() + "/source.csv"));
    }

    private record OrgUser(UUID orgId, UUID userId, String token) {
    }
}