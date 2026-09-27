CREATE TABLE expensive_action_policy (
    action varchar(32) PRIMARY KEY,
    short_limit integer NOT NULL CHECK (short_limit BETWEEN 1 AND 10000),
    short_window_seconds integer NOT NULL CHECK (short_window_seconds BETWEEN 1 AND 3600),
    long_limit integer NOT NULL CHECK (long_limit BETWEEN 1 AND 100000),
    long_window_seconds integer NOT NULL CHECK (long_window_seconds BETWEEN 3600 AND 604800),
    CHECK (short_limit <= long_limit),
    CHECK (short_window_seconds < long_window_seconds)
);

INSERT INTO expensive_action_policy VALUES
    ('PHOTO_UPLOAD', 30, 60, 200, 86400),
    ('PROCESSING_RETRY', 10, 60, 40, 86400),
    ('SEMANTIC_QUERY', 30, 60, 500, 86400),
    ('GARMENT_EMBEDDING', 60, 60, 1000, 86400);

CREATE TABLE expensive_action_usage (
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    action varchar(32) NOT NULL REFERENCES expensive_action_policy(action),
    short_started_at timestamptz NOT NULL,
    short_used integer NOT NULL CHECK (short_used >= 0),
    long_started_at timestamptz NOT NULL,
    long_used integer NOT NULL CHECK (long_used >= 0),
    PRIMARY KEY (wardrobe_id, action)
);
