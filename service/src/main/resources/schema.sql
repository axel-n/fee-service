-- The fee records: one row per transaction_id, written by FeeWorkflow. CLAIM inserts it IN_PROGRESS with every field of
-- the validated request, RECORD completes it with the charge. Run at every start (spring.sql.init.mode: always), so it
-- must stay idempotent; that run also fails startup when the database can't be reached, or when it holds a fee_record
-- table that lacks one of its columns (the last statement). The app's database user owns the table: it creates it and its index.
-- The build also reads this file: it runs it in a Postgres that Testcontainers starts, and jOOQ generates the table's
-- Kotlin classes from the result (jooq-codegen-maven in service/pom.xml).
--
-- Stored data: renaming or removing a column, or changing a status value, breaks existing records.
-- NUMERIC without precision keeps each value's scale: an amount sent as 1000 is read back as 1000, a fee of 1.50 as 1.50.
CREATE TABLE IF NOT EXISTS fee_record (
    -- the request, every field as the client sent it
    transaction_id TEXT      CONSTRAINT fee_record_pkey PRIMARY KEY,
    amount         NUMERIC   NOT NULL,
    asset          TEXT      NOT NULL,
    asset_type     TEXT      NOT NULL,
    type           TEXT      NOT NULL,
    state          TEXT      NOT NULL,
    -- the request's created_at (no zone, read as UTC); Postgres keeps microseconds, so a 7th to 9th fraction digit is rounded
    created_at     TIMESTAMP NOT NULL,
    status         TEXT      NOT NULL CONSTRAINT fee_record_status_check CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- the workflow run that claimed the row: only it may complete it, also when it runs a step again after a crash
    claim_token    TEXT      NOT NULL,
    -- when the row was claimed, from the app's clock
    claimed_at     TIMESTAMPTZ NOT NULL,
    -- the charge: all set when COMPLETED, none while IN_PROGRESS; charged_at from the app's clock
    fee            NUMERIC,
    rate           NUMERIC,
    description    TEXT,
    charged_at     TIMESTAMPTZ,
    CONSTRAINT fee_record_charge_exactly_when_completed CHECK (
        (status = 'COMPLETED'
            AND fee IS NOT NULL AND rate IS NOT NULL AND description IS NOT NULL AND charged_at IS NOT NULL)
        OR (status = 'IN_PROGRESS'
            AND fee IS NULL AND rate IS NULL AND description IS NULL AND charged_at IS NULL)
    )
);

-- Old records are found by when they were claimed, the app's own clock: DELETE FROM fee_record WHERE claimed_at < :cutoff.
-- Never by created_at, which is the client's date and may be years older than the charge.
CREATE INDEX IF NOT EXISTS fee_record_claimed_at ON fee_record (claimed_at);

-- Fails startup on a fee_record table that lacks a column, which CREATE TABLE IF NOT EXISTS leaves as it is. It checks
-- columns only: an older table that has them all keeps its own constraints and indexes, so drop it after the schema changed
SELECT transaction_id, amount, asset, asset_type, type, state, created_at, status, claim_token, claimed_at, fee, rate,
       description, charged_at
FROM fee_record
WHERE false;
