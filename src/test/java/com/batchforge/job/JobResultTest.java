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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
class JobResultTest {

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
    private MinioStorageService storageService;

    @Test
    void returnsWorkingDownloadUrlForCompletedJobWithErrorReport() throws Exception {
        OrgUser u = newOrgUser();
        String errorKey = u.orgId() + "/" + UUID.randomUUID() + "/errors.csv";
        String reportCsv = "row_number,error\n2,email is required\n";
        storageService.putObject(errorKey, reportCsv.getBytes(StandardCharsets.UTF_8), "text/csv");

        Job job = new Job(u.orgId(), u.userId(), u.orgId() + "/" + UUID.randomUUID() + "/source.csv");
        job.markCompleted();
        job.attachErrorReport(errorKey);
        job = jobRepository.save(job);

        String body = mockMvc.perform(get("/jobs/" + job.getId() + "/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String downloadUrl = JsonPath.read(body, "$.downloadUrl");
        HttpResponse<String> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).contains("row_number", "email is required");
    }

    @Test
    void notFoundWhenCompletedJobHasNoErrorReport() throws Exception {
        OrgUser u = newOrgUser();
        Job job = new Job(u.orgId(), u.userId(), u.orgId() + "/" + UUID.randomUUID() + "/source.csv");
        job.markCompleted();
        job = jobRepository.save(job);

        mockMvc.perform(get("/jobs/" + job.getId() + "/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NO_ERROR_REPORT"));
    }

    @Test
    void conflictWhenJobStillInFlight() throws Exception {
        OrgUser u = newOrgUser();
        Job job = jobRepository.save(new Job(u.orgId(), u.userId(),
                u.orgId() + "/" + UUID.randomUUID() + "/source.csv"));   // PENDING

        mockMvc.perform(get("/jobs/" + job.getId() + "/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RESULT_NOT_READY"));
    }

    @Test
    void conflictWhenJobFailed() throws Exception {
        OrgUser u = newOrgUser();
        Job job = new Job(u.orgId(), u.userId(), u.orgId() + "/" + UUID.randomUUID() + "/source.csv");
        job.markFailed();
        job = jobRepository.save(job);

        mockMvc.perform(get("/jobs/" + job.getId() + "/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + u.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("JOB_FAILED"));
    }

    @Test
    void notFoundForJobInAnotherOrg() throws Exception {
        OrgUser owner = newOrgUser();
        OrgUser intruder = newOrgUser();
        String errorKey = owner.orgId() + "/" + UUID.randomUUID() + "/errors.csv";
        storageService.putObject(errorKey, "row_number,error\n2,x\n".getBytes(StandardCharsets.UTF_8), "text/csv");

        Job job = new Job(owner.orgId(), owner.userId(), owner.orgId() + "/" + UUID.randomUUID() + "/source.csv");
        job.markCompleted();
        job.attachErrorReport(errorKey);
        job = jobRepository.save(job);

        mockMvc.perform(get("/jobs/" + job.getId() + "/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));
    }

    private OrgUser newOrgUser() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        String token = jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
        return new OrgUser(org.getId(), user.getId(), token);
    }

    private record OrgUser(UUID orgId, UUID userId, String token) {
    }
}