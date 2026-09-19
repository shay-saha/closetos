CREATE TABLE garment_image (
    id uuid PRIMARY KEY,
    garment_id uuid NOT NULL REFERENCES garment(id) ON DELETE CASCADE,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE CASCADE,
    image_role varchar(20) NOT NULL CHECK (image_role IN ('FRONT', 'BACK', 'DETAIL', 'LABEL', 'ALTERNATE')),
    original_filename varchar(255) NOT NULL,
    mime_type varchar(40) NOT NULL,
    source_s3_key varchar(512) NOT NULL UNIQUE,
    expected_size bigint NOT NULL CHECK (expected_size > 0),
    source_checksum varchar(44) NOT NULL,
    upload_key uuid NOT NULL,
    processing_status varchar(30) NOT NULL,
    assets jsonb,
    width integer,
    height integer,
    original_deleted boolean NOT NULL DEFAULT false,
    delete_original_after_isolation boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (wardrobe_id, upload_key)
);
CREATE INDEX garment_image_garment ON garment_image (garment_id, created_at);
CREATE INDEX garment_image_pending ON garment_image (processing_status, created_at);

CREATE TABLE processing_job (
    id uuid PRIMARY KEY,
    image_id uuid NOT NULL REFERENCES garment_image(id) ON DELETE CASCADE,
    pipeline_version varchar(40) NOT NULL,
    execution_arn varchar(512),
    state varchar(30) NOT NULL,
    failure_code varchar(80),
    failure_detail varchar(1000),
    attempt_count integer NOT NULL CHECK (attempt_count BETWEEN 1 AND 5),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE (image_id, pipeline_version)
);
CREATE INDEX processing_job_image ON processing_job (image_id, started_at DESC);

CREATE TABLE processed_integration_event (
    event_id varchar(200) PRIMARY KEY,
    event_type varchar(80) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    idempotency_key varchar(200) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    lease_until timestamptz,
    available_at timestamptz NOT NULL DEFAULT now(),
    publish_attempts integer NOT NULL DEFAULT 0,
    failure_detail varchar(1000)
);
CREATE INDEX outbox_pending ON outbox_event (published_at, available_at, created_at);
