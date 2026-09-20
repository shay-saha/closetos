ALTER TABLE user_profile ADD COLUMN version bigint NOT NULL DEFAULT 0;

ALTER TABLE processing_job ADD COLUMN analysis_status varchar(30) NOT NULL DEFAULT 'NOT_REQUESTED';
ALTER TABLE processing_job ADD COLUMN analysis_failure varchar(80);

CREATE TABLE garment_ai_suggestion (
    id uuid PRIMARY KEY,
    garment_id uuid NOT NULL REFERENCES garment(id) ON DELETE CASCADE,
    image_id uuid NOT NULL REFERENCES garment_image(id) ON DELETE CASCADE,
    processing_job_id uuid NOT NULL UNIQUE REFERENCES processing_job(id) ON DELETE CASCADE,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    model_id varchar(300) NOT NULL,
    model_version varchar(200),
    prompt_version varchar(80) NOT NULL,
    pipeline_version varchar(40) NOT NULL,
    suggestion_json jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    decision_json jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    rejected_at timestamptz
);
CREATE INDEX garment_ai_suggestion_review ON garment_ai_suggestion (wardrobe_id, garment_id, status, created_at DESC);
