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
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class LargeImportStreamingTest {

    private static final int ROWS = 50_000;
    private static final int BATCH_SIZE = 500;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JobConsumer jobConsumer;
    @Autowired private ImportedRecordRepository importedRecordRepository;

    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoSpyBean private JobProcessingService processingSpy;

    @Test
    void largeFileIsStreamedInBatchesAndFullyImported() throws Exception {
        String token = newOrgUserToken();

        String body = mockMvc.perform(post("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(JsonPath.read(body, "$.jobId"));
        String uploadUrl = JsonPath.read(body, "$.uploadUrl");

        upload(uploadUrl, buildCsv(ROWS));

        mockMvc.perform(post("/jobs/" + jobId + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted());

        jobConsumer.onJobMessage(new JobMessage(jobId));

        mockMvc.perform(get("/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalRows").value(ROWS))
                .andExpect(jsonPath("$.processedRows").value(ROWS))
                .andExpect(jsonPath("$.failedRows").value(0));

        assertThat(importedRecordRepository.countByJobId(jobId)).isEqualTo(ROWS);

        Mockito.verify(processingSpy, Mockito.atLeast(ROWS / BATCH_SIZE))
                .flushBatch(eq(jobId), any(), any(), anyLong(), anyLong(), anyLong());
    }

    private static byte[] buildCsv(int rows) {
        StringBuilder sb = new StringBuilder(rows * 40);
        sb.append("email,first_name,last_name,phone\n");
        for (int i = 1; i <= rows; i++) {
            sb.append("user").append(i).append("@example.com,First").append(i)
                    .append(",Last").append(i).append(",\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String newOrgUserToken() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        return jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
    }

    private void upload(String url, byte[] content) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}