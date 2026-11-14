-- The fee records: one row per transaction_id. FeeService inserts it PENDING with every field of the validated request
-- and the workflow_id it submits FeeWorkflow under; the workflow's CHARGE step charges it: the fee, and the request fields
-- of the payload it charged (a request of the same id sent at the same time may have started it with another). Run at every
-- start (spring.sql.init.mode: always), so it must stay idempotent; that run also fails startup when the database can't
-- be reached, or when it holds a fee_record table that lacks one of its columns (the last statement). The app's database
-- user owns the table: it creates it and its index.
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
    status         TEXT      NOT NULL CONSTRAINT fee_record_status_check CHECK (status IN ('PENDING', 'CHARGED')),
    -- the Restate workflow key of the transaction (FeeWorkflow/<workflow_id>): only that workflow may charge the row
    workflow_id    TEXT      NOT NULL,
    -- when the row was inserted, from the app's clock
    started_at     TIMESTAMPTZ NOT NULL,
    -- the charge: all set when CHARGED, none while PENDING; charged_at from the app's clock
    fee            NUMERIC,
    rate           NUMERIC,
    description    TEXT,
    charged_at     TIMESTAMPTZ,
    CONSTRAINT fee_record_charge_exactly_when_charged CHECK (
        (status = 'CHARGED'
            AND fee IS NOT NULL AND rate IS NOT NULL AND description IS NOT NULL AND charged_at IS NOT NULL)
        OR (status = 'PENDING'
            AND fee IS NULL AND rate IS NULL AND description IS NULL AND charged_at IS NULL)
    )
);

-- Old records are found by when they were started, the app's own clock: DELETE FROM fee_record WHERE status = 'CHARGED'
-- AND started_at < :cutoff. Never a PENDING row (its workflow can no longer charge it), and keep a charge as long as clients
-- may resend its transaction_id: a deleted record no longer protects its id from a second charge.
-- Never by created_at, which is the client's date and may be years older than the charge.
CREATE INDEX IF NOT EXISTS fee_record_started_at ON fee_record (started_at);

-- Fails startup on a fee_record table that lacks a column, which CREATE TABLE IF NOT EXISTS leaves as it is. It checks
-- columns only: an older table that has them all keeps its own constraints and indexes, so drop it after the schema changed
SELECT transaction_id, amount, asset, asset_type, type, state, created_at, status, workflow_id, started_at, fee, rate,
       description, charged_at
FROM fee_record
WHERE false;
