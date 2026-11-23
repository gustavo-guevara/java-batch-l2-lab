-- Eligibility batch schema. Safe to run more than once.

-- One row per job execution: the audit trail L2 checks first.
CREATE TABLE IF NOT EXISTS batch_run (
    run_id         BIGSERIAL PRIMARY KEY,
    job_name       TEXT        NOT NULL,
    business_date  DATE        NOT NULL,
    file_key       TEXT        NOT NULL,
    file_checksum  TEXT,
    status         TEXT        NOT NULL
                   CHECK (status IN ('RUNNING','SUCCESS','SUCCESS_WITH_ERRORS','FAILED','SKIPPED')),
    rows_read      INT         NOT NULL DEFAULT 0,
    rows_loaded    INT         NOT NULL DEFAULT 0,
    rows_rejected  INT         NOT NULL DEFAULT 0,
    exit_code      INT,
    message        TEXT,
    host           TEXT,
    pid            BIGINT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at       TIMESTAMPTZ
);

-- Safety net: the same file content can only be loaded successfully once.
CREATE UNIQUE INDEX IF NOT EXISTS ux_batch_run_loaded_checksum
    ON batch_run (file_checksum)
    WHERE status IN ('SUCCESS','SUCCESS_WITH_ERRORS');

CREATE INDEX IF NOT EXISTS ix_batch_run_date ON batch_run (business_date, started_at DESC);

-- Current coverage per member (upserted by each load).
CREATE TABLE IF NOT EXISTS member_eligibility (
    member_id       TEXT PRIMARY KEY,
    first_name      TEXT NOT NULL,
    last_name       TEXT NOT NULL,
    dob             DATE NOT NULL,
    plan_code       TEXT NOT NULL,
    coverage_start  DATE NOT NULL,
    coverage_end    DATE NOT NULL,
    last_run_id     BIGINT REFERENCES batch_run (run_id),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (coverage_end >= coverage_start)
);

-- Every rejected row, with the reason, so the business can fix the source.
CREATE TABLE IF NOT EXISTS load_error (
    error_id    BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES batch_run (run_id),
    line_no     INT    NOT NULL,
    raw_line    TEXT   NOT NULL,
    reason      TEXT   NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_load_error_run ON load_error (run_id);
