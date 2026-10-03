-- Index for the claim query (JobRepository.CLAIM_SQL), whose inner SELECT filters on status IN ('QUEUED','RETRY')
-- and orders by priority DESC, run_at. idx_jobs_ready (V1) starts with status, so for two statuses PostgreSQL has to
-- fetch every due row and sort them all before the LIMIT. This partial index holds only claimable rows, already in
-- claim order, so a poll reads the first due entries and stops at the LIMIT. idx_jobs_ready stays: the lease reaper
-- and the per-status count of GET /api/jobs?status= use it.
CREATE INDEX idx_jobs_claim ON jobs (priority DESC, run_at) WHERE status IN ('QUEUED','RETRY');
