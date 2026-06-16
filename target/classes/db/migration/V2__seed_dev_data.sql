-- ============================================================
--  V2__seed_dev_data.sql
--  Seed data for local development only.
--  In production, use a separate seed mechanism or skip this.
-- ============================================================

INSERT INTO client_configs (client_id, algorithm, rate_per_second, burst_size, window_seconds)
VALUES
    ('dev-client-1', 'SLIDING_WINDOW', 100, 200, 60),
    ('dev-client-2', 'TOKEN_BUCKET',    50, 100, 60),
    ('dev-client-3', 'GCRA',           200, 400, 60),
    ('dev-client-4', 'FIXED_WINDOW',   300, 300, 60)
ON CONFLICT (client_id) DO NOTHING;
