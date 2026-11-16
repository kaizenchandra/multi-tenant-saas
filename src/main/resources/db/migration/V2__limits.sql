ALTER TABLE usage
    ADD COLUMN window_at timestamptz NOT NULL DEFAULT date_trunc('second', now());
ALTER TABLE usage
    ADD COLUMN window_count int NOT NULL DEFAULT 0;
