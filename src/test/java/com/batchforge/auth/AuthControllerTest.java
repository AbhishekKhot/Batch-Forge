package com.batchforge.auth;

import com.batchforge.support.PostgresContainerConfiguration;
import com.batchforge.support.RedisContainerConfiguration;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import com.jayway.jsonpath.JsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersOrgOwnerAndReturnsTokens() throws Exception {
        String body = """
                { "organizationName": "Acme", "email": "Owner@Example.com", "password": "s3cretpassword" }
                """;

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));

        Optional<User> stored = userRepository.findByEmail("owner@example.com"); 
        assertThat(stored).isPresent();
        User u = stored.get();
        assertThat(u.getEmail()).isEqualTo("Owner@Example.com");       
        assertThat(u.getRole()).isEqualTo(Role.ORG_OWNER);
        assertThat(u.getPasswordHash()).isNotEqualTo("s3cretpassword");
        assertThat(passwordEncoder.matches("s3cretpassword", u.getPasswordHash())).isTrue();
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        String first = """
                { "organizationName": "Globex", "email": "dupe@example.com", "password": "s3cretpassword" }
                """;
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());

        String second = """
                { "organizationName": "Globex Two", "email": "Dupe@Example.com", "password": "anotherpass1" }
                """;
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void loginSucceedsAndIsCaseInsensitive() throws Exception {
        String register = """
                { "organizationName": "Initech", "email": "Peter@Initech.com", "password": "s3cretpassword" }
                """;
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(register))
                .andExpect(status().isOk());

        String login = """
                { "email": "peter@initech.com", "password": "s3cretpassword" }
                """;
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        String register = """
                { "organizationName": "Umbrella", "email": "alice@umbrella.com", "password": "s3cretpassword" }
                """;
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(register))
                .andExpect(status().isOk());

        String login = """
                { "email": "alice@umbrella.com", "password": "wrongpassword" }
                """;
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginFailsForUnknownEmail() throws Exception {
        String login = """
                { "email": "nobody@nowhere.com", "password": "whatever12" }
                """;
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    void registrationValidationFails() throws Exception {
        String bad = """
                { "organizationName": "", "email": "not-an-email", "password": "short" }
                """;
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.organizationName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }


    @Test
    void refreshRotatesAndReturnsNewTokens() throws Exception {
        String refreshToken = registerAndGetRefreshToken("Hooli", "refresh@example.com", "s3cretpassword");

        String body = """
                { "refreshToken": "%s" }
                """.formatted(refreshToken);

        String json = mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        String rotated = JsonPath.read(json, "$.refreshToken");
        assertThat(rotated).isNotEqualTo(refreshToken); 
    }

    @Test
    void refreshWithInvalidTokenIsUnauthorized() throws Exception {
        String body = """
                { "refreshToken": "unknown-family.deadbeefsecret" }
                """;
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void replayingRotatedTokenRevokesTheFamily() throws Exception {
        String original = registerAndGetRefreshToken("Pied Piper", "reuse@example.com", "s3cretpassword");

        String originalBody = """
                { "refreshToken": "%s" }
                """.formatted(original);

        String json = mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(originalBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotated = JsonPath.read(json, "$.refreshToken");

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(originalBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));

        String rotatedBody = """
                { "refreshToken": "%s" }
                """.formatted(rotated);
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(rotatedBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutThenRefreshIsUnauthorized() throws Exception {
        String refreshToken = registerAndGetRefreshToken("Aviato", "logout@example.com", "s3cretpassword");

        String body = """
                { "refreshToken": "%s" }
                """.formatted(refreshToken);

        mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshValidationFails() throws Exception {
        String body = """
                { "refreshToken": "" }
                """;
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.refreshToken").exists());
    }

    private String registerAndGetRefreshToken(String org, String email, String password) throws Exception {
        String body = """
                { "organizationName": "%s", "email": "%s", "password": "%s" }
                """.formatted(org, email, password);
        String json = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.refreshToken");
    }
}