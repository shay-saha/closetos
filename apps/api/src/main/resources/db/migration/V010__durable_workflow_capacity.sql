CREATE TABLE workflow_capacity (
    id integer PRIMARY KEY CHECK (id = 1),
    maximum_active integer NOT NULL CHECK (maximum_active BETWEEN 1 AND 16)
);
INSERT INTO workflow_capacity (id, maximum_active) VALUES (1, 4);

CREATE TABLE workflow_slot (
    job_id uuid PRIMARY KEY,
    execution_arn varchar(512) NOT NULL UNIQUE,
    state_machine_arn varchar(256) NOT NULL,
    execution_name varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    attempted boolean NOT NULL DEFAULT false,
    reserved_at timestamptz NOT NULL DEFAULT now(),
    observed_at timestamptz,
    observed_status varchar(80)
);
CREATE INDEX workflow_slot_observation ON workflow_slot (observed_at NULLS FIRST, reserved_at);

INSERT INTO workflow_slot (job_id, execution_arn, state_machine_arn, execution_name, payload, attempted)
SELECT j.id, j.execution_arn,
    regexp_replace(j.execution_arn, ':execution:([^:]+):.*$', ':stateMachine:\1'),
    split_part(j.execution_arn, ':', 8),
    jsonb_build_object(
        'jobId', j.id, 'imageId', i.id, 'garmentId', i.garment_id, 'wardrobeId', i.wardrobe_id,
        'sourceKey', i.source_s3_key,
        'outputPrefix', substring(i.source_s3_key FROM 1 FOR length(i.source_s3_key) - length(split_part(i.source_s3_key, '/', -1))) || 'pipelines/' || j.pipeline_version || '/',
        'mimeType', i.mime_type, 'expectedSize', i.expected_size, 'checksumSha256', i.source_checksum,
        'pipelineVersion', j.pipeline_version, 'requestId', j.id::text),
    true
FROM processing_job j JOIN garment_image i ON i.id = j.image_id
WHERE j.state IN ('PROCESSING_MEDIA', 'ANALYSING')
    AND j.execution_arn ~ '^arn:aws(-[a-z]+)?:states:[a-z0-9-]+:[0-9]{12}:execution:[^:]+:[^:]+$';
