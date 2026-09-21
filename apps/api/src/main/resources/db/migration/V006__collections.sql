CREATE TABLE collection (
    id uuid PRIMARY KEY,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    name varchar(160) NOT NULL CHECK (length(trim(name)) > 0),
    type varchar(12) NOT NULL CHECK (type IN ('MANUAL', 'SMART')),
    query_definition jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((type = 'MANUAL' AND query_definition IS NULL)
        OR (type = 'SMART' AND jsonb_typeof(query_definition) = 'object' AND query_definition IS NOT NULL)),
    UNIQUE (id, wardrobe_id, type)
);
CREATE INDEX collection_wardrobe_created ON collection (wardrobe_id, created_at DESC, id DESC);
ALTER TABLE garment ADD CONSTRAINT garment_id_wardrobe_unique UNIQUE (id, wardrobe_id);
CREATE TABLE collection_garment (
    collection_id uuid NOT NULL,
    garment_id uuid NOT NULL,
    wardrobe_id uuid NOT NULL,
    collection_type varchar(12) NOT NULL DEFAULT 'MANUAL' CHECK (collection_type = 'MANUAL'),
    PRIMARY KEY (collection_id, garment_id),
    FOREIGN KEY (collection_id, wardrobe_id, collection_type)
        REFERENCES collection(id, wardrobe_id, type) ON DELETE CASCADE,
    FOREIGN KEY (garment_id, wardrobe_id)
        REFERENCES garment(id, wardrobe_id) ON DELETE CASCADE
);
CREATE INDEX collection_garment_lookup ON collection_garment (garment_id);
