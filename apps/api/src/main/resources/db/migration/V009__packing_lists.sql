CREATE TABLE packing_list (
    id uuid PRIMARY KEY,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    name varchar(160) NOT NULL CHECK (length(trim(name)) > 0),
    start_date date NOT NULL,
    end_date date NOT NULL,
    location_text varchar(200),
    constraints jsonb NOT NULL CHECK (jsonb_typeof(constraints) = 'object'),
    plan jsonb CHECK (plan IS NULL OR jsonb_typeof(plan) = 'object'),
    manual_override boolean NOT NULL DEFAULT false,
    explanations jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(explanations) = 'array'),
    garment_versions jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(garment_versions) = 'object'),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (end_date >= start_date AND end_date - start_date < 31),
    UNIQUE (id, wardrobe_id)
);
CREATE INDEX packing_list_wardrobe_created ON packing_list (wardrobe_id, created_at DESC, id DESC);
CREATE TABLE packing_item (
    packing_list_id uuid NOT NULL,
    wardrobe_id uuid NOT NULL,
    garment_id uuid NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'TO_PACK' CHECK (status IN ('TO_PACK', 'PACKED')),
    PRIMARY KEY (packing_list_id, garment_id),
    FOREIGN KEY (packing_list_id, wardrobe_id)
        REFERENCES packing_list(id, wardrobe_id) ON DELETE CASCADE,
    FOREIGN KEY (garment_id, wardrobe_id)
        REFERENCES garment(id, wardrobe_id) ON DELETE CASCADE
);
CREATE INDEX packing_item_garment ON packing_item (garment_id);
