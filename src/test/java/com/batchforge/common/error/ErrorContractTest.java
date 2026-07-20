package com.batchforge.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void apiExceptionKeepsItsOwnErrorCode_notSwallowedByCatchAll() throws Exception {
        mockMvc.perform(get("/probe/api-error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROBE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void validationFailureIsBadRequestWithFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void unexpectedExceptionIsGeneric500WithNoLeak() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("kaboom-secret-detail"))));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/api-error")
        void apiError() {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROBE_NOT_FOUND", "probe not found");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new RuntimeException("kaboom-secret-detail");
        }

        @PostMapping("/probe/validate")
        void validate(@Valid @RequestBody Payload payload) {
        }

        record Payload(@NotBlank String name) {}
    }
}