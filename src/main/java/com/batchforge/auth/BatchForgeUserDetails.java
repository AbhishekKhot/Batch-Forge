package com.batchforge.auth;

import com.batchforge.user.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class BatchForgeUserDetails implements UserDetails {

    private final UUID userId;
    private final UUID orgId;
    private final String email;
    private final String passwordHash;
    private final Role role;

    public BatchForgeUserDetails(UUID userId, UUID orgId, String email, String passwordHash, Role role) {
        this.userId = userId;
        this.orgId = orgId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}