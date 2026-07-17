CREATE TABLE import_errors (
                               id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               job_id            UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
                               source_row_number BIGINT NOT NULL,
                               reason            TEXT NOT NULL,
                               created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                               CONSTRAINT uq_import_errors_job_row UNIQUE (job_id, source_row_number)
);