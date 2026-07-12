CREATE INDEX ix_jobs_org_id_created_at ON jobs (org_id, created_at DESC);
CREATE INDEX ix_jobs_org_id_status ON jobs (org_id, status);
CREATE INDEX ix_jobs_submitted_by ON jobs (submitted_by);