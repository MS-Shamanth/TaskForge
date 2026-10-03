CREATE TABLE jobs (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    priority        INT          NOT NULL DEFAULT 0,
    run_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    locked_by       VARCHAR(100),
    locked_until    TIMESTAMPTZ,
    idempotency_key VARCHAR(100) UNIQUE,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_ready ON jobs (status, priority DESC, run_at);
