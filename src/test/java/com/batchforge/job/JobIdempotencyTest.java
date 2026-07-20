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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class JobIdempotencyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JobConsumer jobConsumer;
    @Autowired private JobProcessingService processing;
    @Autowired private ImportedRecordRepository importedRecordRepository;
    @Autowired private ImportProcessor importProcessor;

    @MockitoBean private RabbitTemplate rabbitTemplate;

    private static final String CSV = String.join("\n",
            "email,first_name,last_name,phone",
            "alice@example.com,Alice,Anderson,+1-555-0100",
            "bob@example.com,Bob,Brown,",
            "carol@example.com,Carol,Clark,555 0199",
            "not-an-email,Dave,Davis,",
            "eve@example.com,,Evans,");

    @Test
    void redeliveryAfterCompletionIsANoOp() throws Exception {
        String token = newOrgUserToken();
        UUID jobId = queuedJobWithCsv(token, CSV);

        jobConsumer.onJobMessage(new JobMessage(jobId));
        jobConsumer.onJobMessage(new JobMessage(jobId));

        mockMvc.perform(get("/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.processedRows").value(3))
                .andExpect(jsonPath("$.failedRows").value(2));

        assertThat(importedRecordRepository.countByJobId(jobId)).isEqualTo(3);
    }

    @Test
    void reprocessingAnAdvancedJobInsertsNoDuplicates() throws Exception {
        String token = newOrgUserToken();
        UUID jobId = queuedJobWithCsv(token, CSV);

        assertThat(processing.claim(jobId)).isTrue();
        importProcessor.process(jobId);
        assertThat(importedRecordRepository.countByJobId(jobId)).isEqualTo(3);

        importProcessor.process(jobId);
        assertThat(importedRecordRepository.countByJobId(jobId))
                .as("checkpoint skip: re-running process() must not duplicate rows")
                .isEqualTo(3);
    }

    private String newOrgUserToken() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        return jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
    }

    private UUID queuedJobWithCsv(String token, String csv) throws Exception {
        String body = mockMvc.perform(post("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(JsonPath.read(body, "$.jobId"));
        String uploadUrl = JsonPath.read(body, "$.uploadUrl");
        upload(uploadUrl, csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/jobs/" + jobId + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted());
        return jobId;
    }

    private void upload(String url, byte[] content) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}