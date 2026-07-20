package com.batchforge.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login, and refresh-token lifecycle")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new organization and its first user",
            description = "Creates a new organization and its first user; the first registrant becomes the ORG_OWNER. Returns an access/refresh token pair.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registered; tokens issued"),
            @ApiResponse(responseCode = "400", description = "Validation failed (errorCode VALIDATION_FAILED)"),
            @ApiResponse(responseCode = "409", description = "Email already registered (errorCode EMAIL_ALREADY_EXISTS)")
    })
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and obtain tokens",
            description = "Validates credentials and returns a new access/refresh token pair.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated; tokens issued"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate tokens using a refresh token",
            description = "Exchanges a valid refresh token for a new access/refresh pair. Refresh tokens are single-use; presenting a previously-used token triggers reuse detection.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens rotated"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid, expired, or reused")
    })
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a refresh token",
            description = "Revokes the supplied refresh token. Already-issued access tokens remain valid until they expire.")
    @ApiResponse(responseCode = "204", description = "Refresh token revoked")
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }
}