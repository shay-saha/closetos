CREATE TABLE user_profile (
    id uuid PRIMARY KEY,
    cognito_sub varchar(128) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    locale varchar(35) NOT NULL DEFAULT 'en-GB',
    currency char(3) NOT NULL DEFAULT 'GBP',
    timezone varchar(80) NOT NULL DEFAULT 'Europe/London',
    delete_original_after_isolation boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wardrobe (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL UNIQUE REFERENCES user_profile(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
