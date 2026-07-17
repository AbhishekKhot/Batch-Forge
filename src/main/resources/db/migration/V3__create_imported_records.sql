CREATE TABLE imported_records (
                                  id                UUID         NOT NULL DEFAULT gen_random_uuid(),
                                  job_id            UUID         NOT NULL,
                                  source_row_number BIGINT       NOT NULL,
                                  email             VARCHAR(320) NOT NULL,
                                  first_name        VARCHAR(255) NOT NULL,
                                  last_name         VARCHAR(255) NOT NULL,
                                  phone             VARCHAR(50),
                                  row_hash          VARCHAR(64)  NOT NULL,
                                  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                  CONSTRAINT pk_imported_records PRIMARY KEY (id),
                                  CONSTRAINT fk_imported_records_job FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
                                  CONSTRAINT ux_imported_records_job_row UNIQUE (job_id, source_row_number)
);