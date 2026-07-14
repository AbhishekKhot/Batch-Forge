package com.batchforge.auth;

import com.batchforge.user.Role;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID orgId, String email, Role role) {

}