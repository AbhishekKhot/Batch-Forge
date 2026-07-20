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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class, MinioTestcontainersConfiguration.class})
class JobCrossOrgIsolationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void anotherOrgCannotReachJobByIdOnAnyEndpoint() throws Exception {
        String ownerToken = newOrgUserToken();
        String otherToken = newOrgUserToken();
        UUID jobId = createJob(ownerToken);

        // GET /jobs/{id}
        mockMvc.perform(get("/jobs/" + jobId).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));

        // POST /jobs/{id}/uploaded
        mockMvc.perform(post("/jobs/" + jobId + "/uploaded").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));

        // GET /jobs/{id}/result
        mockMvc.perform(get("/jobs/" + jobId + "/result").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));
    }

    @Test
    void anotherOrgDoesNotSeeJobInItsList() throws Exception {
        String ownerToken = newOrgUserToken();
        String otherToken = newOrgUserToken();
        UUID jobId = createJob(ownerToken);

        // owner's list contains the job
        mockMvc.perform(get("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(jobId.toString())));

        // the other org's list does not
        mockMvc.perform(get("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(jobId.toString()))));
    }

    private String newOrgUserToken() {
        Organization org = organizationRepository.save(new Organization("Org"));
        String email = "user-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(new User(email, "hash", org.getId(), Role.MEMBER));
        return jwtService.generateAccessToken(
                new BatchForgeUserDetails(user.getId(), org.getId(), email, "hash", Role.MEMBER));
    }

    private UUID createJob(String token) throws Exception {
        String body = mockMvc.perform(post("/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.jobId"));
    }
}