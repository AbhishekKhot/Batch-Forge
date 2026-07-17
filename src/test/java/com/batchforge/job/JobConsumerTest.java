package com.batchforge.job;

import com.batchforge.auth.BatchForgeUserDetails;
import com.batchforge.auth.JwtService;
import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.support.MinioTestcontainersConfiguration;
import com.batchforge.support.TestcontainersConfiguration;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
class JobConsumerTest {

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
    @Autowired
    private JobProcessingService processing;

    @Test
    void queuedJobIsConsumedAndCompleted() throws Exception {
        OrgUser u = newOrgUser();
        JobHandle job = createJob(u.token());
        upload(job.uploadUrl(), "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jobRepository.findById(job.jobId()).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.COMPLETED));
    }

    @Test
    void duplicateDeliveryForFinishedJobIsNoOp() {
        OrgUser u = newOrgUser();
        Job job = new Job(u.orgId(), u.userId(), u.orgId() + "/" + UUID.randomUUID() + "/source.csv");
        job.markQueued();
        job.markProcessing();
        job.markCompleted();
        job = jobRepository.save(job);

        assertThat(processing.claim(job.getId())).isFalse();
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    private OrgUser newOrgUser() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        String token = jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
        return new OrgUser(org.getId(), user.getId(), token);
    }

    private JobHandle createJob(String token) throws Exception {
        String body = mockMvc.perform(post("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new JobHandle(UUID.fromString(JsonPath.read(body, "$.jobId")), JsonPath.read(body, "$.uploadUrl"));
    }

    private void upload(String url, byte[] content) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private record JobHandle(UUID jobId, String uploadUrl) {
    }

    private record OrgUser(UUID orgId, UUID userId, String token) {
    }
}