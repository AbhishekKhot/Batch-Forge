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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
class ErrorReportTest {

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
    private ImportErrorRepository importErrorRepository;
    @Autowired
    private MinioStorageService storageService;
    @Autowired
    private ImportProcessor importProcessor;

    @Test
    void writesErrorReportToStorageForFailedRows() throws Exception {
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

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jobRepository.findById(handle.jobId()).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.COMPLETED));

        Job job = jobRepository.findById(handle.jobId()).orElseThrow();
        assertThat(job.getErrorReportObjectKey()).isNotNull();
        assertThat(job.getFailedRows()).isEqualTo(2L);

        List<List<String>> report = downloadCsv(job.getErrorReportObjectKey());
        assertThat(report).hasSize(3);
        assertThat(report.get(0)).containsExactly("row_number", "error");
        assertThat(report.get(1).get(0)).isEqualTo("2");
        assertThat(report.get(2).get(0)).isEqualTo("4");
        assertThat(report.get(1).get(1)).isNotBlank();
    }

    @Test
    void noErrorReportWhenAllRowsValid() throws Exception {
        OrgUser u = newOrgUser();
        JobHandle handle = createJob(u.token());
        String csv = """
                email,first_name,last_name,phone
                alice@example.com,Alice,Smith,
                carol@example.com,Carol,Doe,
                erin@example.com,Erin,Fox,
                """;
        upload(handle.uploadUrl(), csv);

        mockMvc.perform(post("/jobs/" + handle.jobId() + "/uploaded")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jobRepository.findById(handle.jobId()).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.COMPLETED));

        Job job = jobRepository.findById(handle.jobId()).orElseThrow();
        assertThat(job.getErrorReportObjectKey()).isNull();
        assertThat(job.getFailedRows()).isEqualTo(0L);
    }

    @Test
    void errorReportIncludesFailuresFromBeforeTheResumePoint() throws Exception {
        OrgUser u = newOrgUser();
        String key = u.orgId() + "/" + UUID.randomUUID() + "/source.csv";
        String csv = """
                email,first_name,last_name,phone
                ,A,A,
                b@x.com,B,B,
                ,C,C,
                d@x.com,D,D,
                ,E,E,
                """;
        upload(storageService.presignedUploadUrl(key), csv);

        Job seeded = new Job(u.orgId(), u.userId(), key);
        seeded.advanceProgress(2, 1, 1);
        seeded = jobRepository.save(seeded);
        UUID jobId = seeded.getId();
        importedRecordRepository.insertBatch(List.of(
                new ImportedRecord(jobId, 2, "b@x.com", "B", "B", null, "h2")));
        importErrorRepository.insertBatch(List.of(
                new ImportError(jobId, 1, "email is required")));

        importProcessor.process(jobId);

        Job after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getFailedRows()).isEqualTo(3L);
        assertThat(after.getErrorReportObjectKey()).isNotNull();

        List<List<String>> report = downloadCsv(after.getErrorReportObjectKey());
        assertThat(report).hasSize(4);
        assertThat(report.get(1).get(0)).isEqualTo("1");
        assertThat(report.get(2).get(0)).isEqualTo("3");
        assertThat(report.get(3).get(0)).isEqualTo("5");
    }

    private List<List<String>> downloadCsv(String key) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        try (InputStream in = storageService.getObject(key);
             CSVParser csv = CSVFormat.DEFAULT.parse(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (CSVRecord entry : csv) {
                List<String> cols = new ArrayList<>();
                entry.forEach(cols::add);
                rows.add(cols);
            }
        }
        return rows;
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
}