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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class JobUploadConfirmationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void confirmingUploadTransitionsJobToQueued() throws Exception {
        String token = newOrgUserToken();
        JobHandle job = createJob(token);
        upload(job.uploadUrl(), "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(job.jobId().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void confirmingWithoutUploadIsConflict() throws Exception {
        String token = newOrgUserToken();
        JobHandle job = createJob(token);

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_NOT_FOUND"));
    }

    @Test
    void confirmingAnotherOrgsJobIsNotFound() throws Exception {
        String ownerToken = newOrgUserToken();
        String otherToken = newOrgUserToken();
        JobHandle job = createJob(ownerToken);

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));
    }

    @Test
    void confirmingTwiceIsIdempotent() throws Exception {
        String token = newOrgUserToken();
        JobHandle job = createJob(token);
        upload(job.uploadUrl(), "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    private String newOrgUserToken() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        return jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
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
}