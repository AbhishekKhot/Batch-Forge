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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class JobPublishOnConfirmTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void confirmingUploadPublishesJobMessage() throws Exception {
        String token = newOrgUserToken();
        JobHandle job = createJob(token);
        upload(job.uploadUrl(), "id,name\n1,alice\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted());

        JobMessage published = rabbitTemplate.receiveAndConvert(
                RabbitConfig.IMPORT_QUEUE, 5_000, new ParameterizedTypeReference<JobMessage>() {});
        assertThat(published).isNotNull();
        assertThat(published.jobId()).isEqualTo(job.jobId());
    }

    @Test
    void failedConfirmPublishesNothing() throws Exception {
        String token = newOrgUserToken();
        JobHandle job = createJob(token);

        mockMvc.perform(post("/jobs/" + job.jobId() + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict());

        Object published = rabbitTemplate.receiveAndConvert(RabbitConfig.IMPORT_QUEUE, 1_000);
        assertThat(published).isNull();
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