package com.batchforge.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class User {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public User(String email, String passwordHash, UUID orgId, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.orgId = orgId;
        this.role = role;
    }
}