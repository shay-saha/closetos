CREATE TABLE garment (
    id uuid PRIMARY KEY,
    wardrobe_id uuid NOT NULL REFERENCES wardrobe(id) ON DELETE CASCADE,
    status varchar(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE', 'LAUNDRY', 'PACKED', 'LENT', 'ARCHIVED')),
    processing_status varchar(30) NOT NULL DEFAULT 'READY',
    name varchar(160) NOT NULL,
    category varchar(20) NOT NULL
        CHECK (category IN ('TOP', 'DRESS', 'BOTTOM', 'OUTERWEAR', 'SHOES', 'BAG', 'JEWELLERY', 'ACCESSORY', 'OTHER')),
    subcategory varchar(80),
    brand varchar(120),
    size_label varchar(40),
    primary_colour_name varchar(60),
    primary_colour_hex varchar(7),
    secondary_colours jsonb NOT NULL DEFAULT '[]',
    pattern varchar(80),
    material varchar(120),
    length varchar(60),
    formality varchar(60),
    season_tags jsonb NOT NULL DEFAULT '[]',
    occasion_tags jsonb NOT NULL DEFAULT '[]',
    style_tags jsonb NOT NULL DEFAULT '[]',
    purchase_price numeric(12,2) CHECK (purchase_price >= 0),
    purchase_currency char(3),
    purchase_date date,
    notes varchar(4000),
    wear_count_cached integer NOT NULL DEFAULT 0 CHECK (wear_count_cached >= 0),
    last_worn_at date,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    search_document tsvector GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(name, '') || ' ' || coalesce(brand, '') || ' ' ||
            category || ' ' || coalesce(subcategory, '') || ' ' || coalesce(primary_colour_name, '') || ' ' ||
            coalesce(material, '') || ' ' || coalesce(notes, '') || ' ' ||
            season_tags::text || ' ' || occasion_tags::text || ' ' || style_tags::text)
    ) STORED
);

CREATE INDEX garment_wardrobe_status_created ON garment (wardrobe_id, status, created_at DESC, id DESC);
CREATE INDEX garment_wardrobe_category_status ON garment (wardrobe_id, category, status);
CREATE INDEX garment_wardrobe_last_worn ON garment (wardrobe_id, last_worn_at);
CREATE INDEX garment_search ON garment USING gin (search_document);
