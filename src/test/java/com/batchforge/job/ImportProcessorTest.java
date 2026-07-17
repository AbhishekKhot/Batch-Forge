package com.batchforge.job;

import com.batchforge.auth.BatchForgeUserDetails;
import com.batchforge.auth.JwtService;
import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.storage.MinioStorageService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
class ImportProcessorTest {

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
    private ImportedRecordRepository importedRecordRepository;
    @Autowired
    private MinioStorageService storageService;
    @Autowired
    private ImportProcessor importProcessor;
    @Autowired
    private ImportErrorRepository importErrorRepository;

    @Test
    void importsValidRowsAndCountsFailuresEndToEnd() throws Exception {
        OrgUser u = newOrgUser();
        JobHandle handle = createJob(u.token());
        String csv = """
                email,first_name,last_name,phone
                alice@example.com,Alice,Smith,
                ,Bob,Jones,
                carol@example.com,Carol,Doe,
                not-email,Dan,Ray,
                erin@example.com,Erin,Fox,
                """;
        upload(handle.uploadUrl(), csv);

        mockMvc.perform(post("/jobs/" + handle.jobId() + "/uploaded")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Job job = jobRepository.findById(handle.jobId()).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
            assertThat(job.getProcessedRows()).isEqualTo(3L);
            assertThat(job.getFailedRows()).isEqualTo(2L);
            assertThat(job.getTotalRows()).isEqualTo(5L);
        });
        assertThat(importedRecordRepository.countByJobId(handle.jobId())).isEqualTo(3L);
    }

    @Test
    void resumesFromCheckpointWithoutReprocessingCommittedRows() throws Exception {
        OrgUser u = newOrgUser();
        String key = u.orgId() + "/" + UUID.randomUUID() + "/source.csv";
        String csv = """
                email,first_name,last_name,phone
                a@x.com,A,A,
                b@x.com,B,B,
                ,C,C,
                d@x.com,D,D,
                e@x.com,E,E,
                """;
        upload(storageService.presignedUploadUrl(key), csv);

        Job seeded = new Job(u.orgId(), u.userId(), key);
        seeded.advanceProgress(2, 2, 0);
        seeded = jobRepository.save(seeded);
        UUID jobId = seeded.getId();
        importedRecordRepository.insertBatch(List.of(
                new ImportedRecord(jobId, 1, "a@x.com", "A", "A", null, "h1"),
                new ImportedRecord(jobId, 2, "b@x.com", "B", "B", null, "h2")));

        importProcessor.process(jobId);

        Job after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getLastProcessedRow()).isEqualTo(5L);
        assertThat(after.getProcessedRows()).isEqualTo(4L);
        assertThat(after.getFailedRows()).isEqualTo(1L);
        assertThat(importedRecordRepository.countByJobId(jobId)).isEqualTo(4L);
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

    private void upload(String url, String content) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content.getBytes(StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private record JobHandle(UUID jobId, String uploadUrl) {
    }

    private record OrgUser(UUID orgId, UUID userId, String token) {
    }

    @Test
    void persistsFailedRowsWithReasonsToImportErrors() throws Exception {
        OrgUser u = newOrgUser();
        String key = u.orgId() + "/" + UUID.randomUUID() + "/source.csv";
        String csv = """
                email,first_name,last_name,phone
                alice@example.com,Alice,Smith,
                ,Bob,Jones,
                not-email,Dan,Ray,
                """;
        upload(storageService.presignedUploadUrl(key), csv);
        Job job = jobRepository.save(new Job(u.orgId(), u.userId(), key));

        importProcessor.process(job.getId());

        List<ImportError> errors = importErrorRepository.findByJobIdOrderByRow(job.getId());
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).sourceRowNumber()).isEqualTo(2L);
        assertThat(errors.get(1).sourceRowNumber()).isEqualTo(3L);
        assertThat(errors.get(0).reason()).isNotBlank();
        assertThat(errors.get(1).reason()).isNotBlank();
        assertThat(importedRecordRepository.countByJobId(job.getId())).isEqualTo(1L);
    }
}