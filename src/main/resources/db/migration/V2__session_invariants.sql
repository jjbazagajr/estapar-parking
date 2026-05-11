ALTER TABLE parking_sessions
    ADD COLUMN open_plate VARCHAR(16)
        AS (CASE WHEN exit_time IS NULL THEN license_plate END) STORED,
    ADD UNIQUE KEY uk_sessions_open_plate (open_plate),
    ADD UNIQUE KEY uk_sessions_entry_event (license_plate, entry_time),
    DROP INDEX idx_sessions_plate_open;
