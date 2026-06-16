-- ============================================================
--  V1__initial_schema.sql
--  Flyway migration — runs automatically on app startup
--  Naming: V{version}__{description}.sql
-- ============================================================

-- ── Extensions ───────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── client_configs ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS client_configs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    client_id       TEXT NOT NULL UNIQUE,
    algorithm       TEXT NOT NULL DEFAULT 'SLIDING_WINDOW'
                        CHECK (algorithm IN ('TOKEN_BUCKET','SLIDING_WINDOW','FIXED_WINDOW','GCRA')),
    rate_per_second INTEGER NOT NULL DEFAULT 100,
    burst_size      INTEGER NOT NULL DEFAULT 200,
    window_seconds  INTEGER NOT NULL DEFAULT 60,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_client_configs_client_id ON client_configs (client_id);
CREATE INDEX idx_client_configs_active    ON client_configs (is_active) WHERE is_active = TRUE;

-- ── rate_limit_audit_log ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS rate_limit_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    client_id       TEXT NOT NULL,
    decision        TEXT NOT NULL CHECK (decision IN ('ALLOWED','REJECTED')),
    algorithm       TEXT NOT NULL,
    limit_applied   INTEGER NOT NULL,
    current_count   INTEGER NOT NULL,
    redis_shard     TEXT,
    trace_id        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_client_created ON rate_limit_audit_log (client_id, created_at DESC);
CREATE INDEX idx_audit_decision        ON rate_limit_audit_log (decision, created_at DESC);

-- ── ml_training_samples ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS ml_training_samples (
    id                  BIGSERIAL PRIMARY KEY,
    client_id           TEXT NOT NULL,
    sampled_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rps_mean_1m         DOUBLE PRECISION,
    rps_mean_5m         DOUBLE PRECISION,
    rps_p99_1m          DOUBLE PRECISION,
    latency_p99_ms      DOUBLE PRECISION,
    hour_of_day         SMALLINT,
    day_of_week         SMALLINT,
    optimal_limit       INTEGER,
    was_throttled       BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_ml_samples_client_time ON ml_training_samples (client_id, sampled_at DESC);

-- ── ab_experiment_results ────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_experiment_results (
    id                  BIGSERIAL PRIMARY KEY,
    experiment_id       TEXT NOT NULL,
    client_id           TEXT NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    static_limit        INTEGER NOT NULL,
    adaptive_limit      INTEGER NOT NULL,
    divergence_pct      DOUBLE PRECISION,
    actual_rps          DOUBLE PRECISION,
    decision_static     TEXT CHECK (decision_static  IN ('ALLOWED','REJECTED')),
    decision_adaptive   TEXT CHECK (decision_adaptive IN ('ALLOWED','REJECTED'))
);

CREATE INDEX idx_ab_experiment_id ON ab_experiment_results (experiment_id, recorded_at DESC);

-- ── anomaly_events ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS anomaly_events (
    id              BIGSERIAL PRIMARY KEY,
    client_id       TEXT NOT NULL,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    z_score         DOUBLE PRECISION NOT NULL,
    rps_at_spike    DOUBLE PRECISION NOT NULL,
    rolling_mean    DOUBLE PRECISION NOT NULL,
    rolling_stddev  DOUBLE PRECISION NOT NULL,
    action_taken    TEXT CHECK (action_taken IN ('TIGHTENED','ALERTED','NONE')),
    resolved_at     TIMESTAMPTZ
);

CREATE INDEX idx_anomaly_client ON anomaly_events (client_id, detected_at DESC);

-- ── updated_at trigger ───────────────────────────────────────
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_client_configs_updated_at
    BEFORE UPDATE ON client_configs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
