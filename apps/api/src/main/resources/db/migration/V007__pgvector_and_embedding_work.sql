CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embedding_model (
    model_key char(64) PRIMARY KEY,
    provider varchar(32) NOT NULL,
    model_id varchar(512) NOT NULL,
    model_version varchar(200),
    pipeline_version varchar(100) NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions IN (256, 384, 512, 1024)),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (model_key, dimensions)
);
CREATE TABLE garment_embedding (
    garment_id uuid NOT NULL,
    wardrobe_id uuid NOT NULL,
    model_key char(64) NOT NULL,
    dimensions integer NOT NULL,
    embedding vector NOT NULL,
    source_fingerprint char(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (garment_id, model_key),
    FOREIGN KEY (garment_id, wardrobe_id) REFERENCES garment(id, wardrobe_id) ON DELETE CASCADE,
    FOREIGN KEY (model_key, dimensions) REFERENCES embedding_model(model_key, dimensions),
    CHECK (vector_dims(embedding) = dimensions),
    CHECK (vector_norm(embedding) > 0.999 AND vector_norm(embedding) < 1.001)
);
CREATE INDEX garment_embedding_owner_model ON garment_embedding (wardrobe_id, model_key);
CREATE INDEX garment_embedding_cosine_256 ON garment_embedding USING hnsw ((embedding::vector(256)) vector_cosine_ops) WHERE dimensions = 256;
CREATE INDEX garment_embedding_cosine_384 ON garment_embedding USING hnsw ((embedding::vector(384)) vector_cosine_ops) WHERE dimensions = 384;
CREATE INDEX garment_embedding_cosine_512 ON garment_embedding USING hnsw ((embedding::vector(512)) vector_cosine_ops) WHERE dimensions = 512;
CREATE INDEX garment_embedding_cosine_1024 ON garment_embedding USING hnsw ((embedding::vector(1024)) vector_cosine_ops) WHERE dimensions = 1024;

CREATE TABLE garment_embedding_work (
    garment_id uuid NOT NULL,
    wardrobe_id uuid NOT NULL,
    model_key char(64) NOT NULL REFERENCES embedding_model(model_key),
    source_fingerprint char(64) NOT NULL,
    state varchar(12) NOT NULL CHECK (state IN ('RUNNING', 'READY', 'QUEUED', 'FAILED')),
    lease_owner uuid,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    failure_code varchar(80),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (garment_id, model_key),
    FOREIGN KEY (garment_id, wardrobe_id) REFERENCES garment(id, wardrobe_id) ON DELETE CASCADE,
    CHECK ((state = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL))
);
CREATE INDEX embedding_work_owner_updated ON garment_embedding_work (wardrobe_id, updated_at DESC);
CREATE INDEX outbox_pending_aggregate ON outbox_event (event_type, aggregate_id) WHERE published_at IS NULL;
