ALTER TABLE garment_embedding ADD COLUMN generation_event uuid;

CREATE TABLE reembedding_job (
    id uuid PRIMARY KEY,
    requested_by varchar(128) NOT NULL,
    request_key uuid NOT NULL,
    wardrobe_id uuid,
    model_key char(64) NOT NULL REFERENCES embedding_model(model_key),
    created_at timestamptz NOT NULL,
    UNIQUE (requested_by, request_key)
);
CREATE INDEX reembedding_job_created ON reembedding_job (created_at DESC, id);

CREATE TABLE reembedding_item (
    job_id uuid NOT NULL REFERENCES reembedding_job(id) ON DELETE CASCADE,
    event_id uuid NOT NULL UNIQUE REFERENCES outbox_event(id),
    outcome varchar(12) CHECK (outcome IN ('SUCCEEDED', 'SKIPPED', 'FAILED')),
    failure_code varchar(80),
    PRIMARY KEY (job_id, event_id),
    CHECK (outcome IS DISTINCT FROM 'FAILED' OR failure_code IS NOT NULL)
);
