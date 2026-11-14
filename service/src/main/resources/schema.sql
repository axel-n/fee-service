-- The fee records: one row per charged transaction_id. FeeWorkflow's CHARGE step inserts it, with every field of the
-- request it charged and the fee: the insert is the charge. Run at every start (spring.sql.init.mode: always), so it must
-- stay idempotent; that run also fails startup when the database can't be reached, or when it holds a fee_record table
-- that lacks one of its columns (the last statement). The app's database user owns the table: it creates it and its index.
-- The build also reads this file: it runs it in a Postgres that Testcontainers starts, and jOOQ generates the table's
-- Kotlin classes from the result (jooq-codegen-maven in service/pom.xml).
--
-- Stored data: renaming or removing a column breaks existing records.
-- NUMERIC without precision keeps each value's scale: an amount sent as 1000 is read back as 1000, a fee of 1.50 as 1.50.
CREATE TABLE IF NOT EXISTS fee_record (
    -- the request, every field as the client sent it
    transaction_id TEXT        CONSTRAINT fee_record_pkey PRIMARY KEY,
    amount         NUMERIC     NOT NULL,
    asset          TEXT        NOT NULL,
    asset_type     TEXT        NOT NULL,
    type           TEXT        NOT NULL,
    state          TEXT        NOT NULL,
    -- the request's created_at (no zone, read as UTC); Postgres keeps microseconds, so a 7th to 9th fraction digit is rounded
    created_at     TIMESTAMP   NOT NULL,
    -- the charge
    fee            NUMERIC     NOT NULL,
    rate           NUMERIC     NOT NULL,
    description    TEXT        NOT NULL,
    -- when the row was inserted, that is when the fee was charged, from the app's clock
    charged_at     TIMESTAMPTZ NOT NULL
);

-- Old records are found by when they were charged, the app's own clock: DELETE FROM fee_record WHERE charged_at < :cutoff.
-- Keep a charge as long as clients may resend its transaction_id: a deleted record no longer protects its id from a second
-- charge. Never by created_at, which is the client's date and may be years older than the charge.
CREATE INDEX IF NOT EXISTS fee_record_charged_at ON fee_record (charged_at);

-- Fails startup on a fee_record table that lacks a column, which CREATE TABLE IF NOT EXISTS leaves as it is. It checks
-- columns only: an older table that has them all keeps its own constraints and indexes, so drop it after the schema changed
SELECT transaction_id, amount, asset, asset_type, type, state, created_at, fee, rate, description, charged_at
FROM fee_record
WHERE false;
