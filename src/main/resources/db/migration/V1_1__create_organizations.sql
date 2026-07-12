CREATE TABLE organizations (
   id         UUID         NOT NULL,
   name       VARCHAR(255) NOT NULL,
   created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
   CONSTRAINT pk_organizations PRIMARY KEY (id)
);