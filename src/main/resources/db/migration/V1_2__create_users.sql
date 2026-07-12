CREATE TABLE users (
     id            UUID         NOT NULL,
     email         VARCHAR(255) NOT NULL,
     password_hash VARCHAR(255) NOT NULL,
     org_id        UUID         NOT NULL,
     role          VARCHAR(32)  NOT NULL,
     created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
     CONSTRAINT pk_users PRIMARY KEY (id),
     CONSTRAINT fk_users_organization FOREIGN KEY (org_id) REFERENCES organizations (id),
     CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'ORG_OWNER', 'MEMBER'))
);