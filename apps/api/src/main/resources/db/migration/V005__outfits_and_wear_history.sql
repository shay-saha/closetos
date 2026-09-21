CREATE TABLE outfit (
    id uuid PRIMARY KEY,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    name varchar(160) NOT NULL,
    occasion varchar(80),
    season varchar(60),
    rating integer CHECK (rating BETWEEN 1 AND 5),
    tags jsonb NOT NULL DEFAULT '[]',
    notes varchar(4000),
    archived boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX outfit_wardrobe_created ON outfit (wardrobe_id, created_at DESC, id DESC);

CREATE TABLE outfit_item (
    outfit_id uuid NOT NULL REFERENCES outfit(id) ON DELETE CASCADE,
    garment_id uuid NOT NULL REFERENCES garment(id) ON DELETE CASCADE,
    x numeric NOT NULL CHECK (x BETWEEN 0 AND 100),
    y numeric NOT NULL CHECK (y BETWEEN 0 AND 100),
    scale numeric NOT NULL CHECK (scale BETWEEN 0.1 AND 3),
    rotation numeric NOT NULL CHECK (rotation BETWEEN -180 AND 180),
    z_index integer NOT NULL CHECK (z_index BETWEEN 0 AND 1000),
    PRIMARY KEY (outfit_id, garment_id)
);
CREATE INDEX outfit_item_garment ON outfit_item (garment_id);

CREATE TABLE wear_event (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_profile(id) ON DELETE CASCADE,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    outfit_id uuid REFERENCES outfit(id) ON DELETE SET NULL,
    outfit_name varchar(160),
    worn_on date NOT NULL,
    notes varchar(4000),
    context varchar(120),
    idempotency_key uuid NOT NULL,
    request_fingerprint char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    UNIQUE (wardrobe_id, idempotency_key)
);
CREATE INDEX wear_event_wardrobe_date ON wear_event (wardrobe_id, worn_on DESC, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE TABLE wear_event_garment (
    wear_event_id uuid NOT NULL REFERENCES wear_event(id) ON DELETE CASCADE,
    garment_id uuid NOT NULL REFERENCES garment(id) ON DELETE CASCADE,
    PRIMARY KEY (wear_event_id, garment_id)
);
CREATE INDEX wear_event_garment_lookup ON wear_event_garment (garment_id);
