package com.batchforge.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TestExceptionController {

    @PostMapping("/test/api-exception")
    String throwApiException() {
        throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email already registered");
    }

    @PostMapping("/test/unexpected")
    String throwUnexpected() {
        throw new IllegalStateException("some internal detail that must not leak");
    }

    @PostMapping("/test/validate")
    String validate(@Valid @RequestBody Payload payload) {
        return "ok";
    }

    record Payload(@NotBlank String name, @Email String email) {}
}