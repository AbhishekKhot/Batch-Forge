package com.batchforge.job;

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
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class JobControllerTest {

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
    void createsJobAndReturnsPresignedUploadUrl() throws Exception {
        Organization org = organizationRepository.save(new Organization("Acme"));
        User user = userRepository.save(new User("submitter@example.com", "hash", org.getId(), Role.MEMBER));
        String token = jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), user.getEmail(), "hash", Role.MEMBER));

        String body = mockMvc.perform(post("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();

        UUID jobId = UUID.fromString(JsonPath.read(body, "$.jobId"));
        Job job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getOrgId()).isEqualTo(org.getId());
        assertThat(job.getSubmittedBy()).isEqualTo(user.getId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getJobType()).isEqualTo(JobType.CSV_IMPORT);
        assertThat(job.getSourceObjectKey())
                .startsWith(org.getId().toString() + "/")
                .endsWith("/source.csv");
    }

    @Test
    void createJobRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }
}