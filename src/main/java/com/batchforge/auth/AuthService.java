package com.batchforge.auth;

import com.batchforge.common.error.ApiException;
import com.batchforge.organization.Organization;
import com.batchforge.organization.OrganizationRepository;
import com.batchforge.user.Role;
import com.batchforge.user.User;
import com.batchforge.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final String dummyHash;

    public AuthService(OrganizationRepository organizationRepository,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw emailAlreadyExists();
        }

        Organization organization = organizationRepository.save(new Organization(request.organizationName()));

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                organization.getId(),
                Role.ORG_OWNER);

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailAlreadyExists();
        }

        return issueTokens(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return issueTokens(user);
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        RotatedTokens rotated = refreshTokenService.rotate(request.refreshToken());
        User user = userRepository.findById(rotated.userId())
                .orElseThrow(this::invalidRefreshToken); 
        BatchForgeUserDetails principal = new BatchForgeUserDetails(
                user.getId(), user.getOrgId(), user.getEmail(), user.getPasswordHash(), user.getRole());
        String accessToken = jwtService.generateAccessToken(principal);
        return AuthResponse.bearer(accessToken, rotated.refreshToken());
    }

    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    private AuthResponse issueTokens(User user) {
        BatchForgeUserDetails principal = new BatchForgeUserDetails(
                user.getId(), user.getOrgId(), user.getEmail(), user.getPasswordHash(), user.getRole());
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = refreshTokenService.issue(user.getId());
        return AuthResponse.bearer(accessToken, refreshToken);
    }

    private ApiException emailAlreadyExists() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already registered");
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    private ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }
}