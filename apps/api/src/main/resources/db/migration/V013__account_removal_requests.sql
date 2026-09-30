CREATE TABLE identity_authentication (
    subject_hash char(64) PRIMARY KEY CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    revoked boolean NOT NULL DEFAULT false
);
INSERT INTO identity_authentication (subject_hash)
SELECT encode(sha256(convert_to(cognito_sub, 'UTF8')), 'hex') FROM user_profile;

CREATE TABLE account_removal (
    id uuid PRIMARY KEY,
    subject_hash char(64) NOT NULL UNIQUE REFERENCES identity_authentication(subject_hash) CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    owner_id uuid UNIQUE,
    provider_subject varchar(128),
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'REMOVING', 'COMPLETE')),
    requested_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    lease_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    failure_code varchar(80),
    CHECK (provider_subject IS NULL OR subject_hash = encode(sha256(convert_to(provider_subject, 'UTF8')), 'hex')),
    completed_at timestamptz,
    CHECK (
        (state = 'COMPLETE' AND owner_id IS NULL AND provider_subject IS NULL AND completed_at IS NOT NULL)
        OR (state <> 'COMPLETE' AND owner_id IS NOT NULL AND provider_subject IS NOT NULL AND completed_at IS NULL)
    )
);
CREATE INDEX account_removal_pending ON account_removal (next_attempt_at, requested_at)
    WHERE state <> 'COMPLETE';

CREATE FUNCTION reject_revoked_profile() RETURNS trigger LANGUAGE plpgsql AS $revocation$
DECLARE
    is_revoked boolean;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.cognito_sub <> OLD.cognito_sub THEN
        RAISE EXCEPTION 'An account cannot change its authentication subject' USING ERRCODE = '23514';
    END IF;
    -- The conflicting row update also rejects an obsolete repeatable-read snapshot.
    INSERT INTO identity_authentication (subject_hash)
    VALUES (encode(sha256(convert_to(NEW.cognito_sub, 'UTF8')), 'hex'))
    ON CONFLICT (subject_hash) DO UPDATE SET revoked = identity_authentication.revoked
    RETURNING revoked INTO is_revoked;
    IF is_revoked THEN
        RAISE EXCEPTION 'Removed accounts cannot provision a profile' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$revocation$;

CREATE TRIGGER prevent_revoked_profile
    BEFORE INSERT OR UPDATE OF cognito_sub ON user_profile
    FOR EACH ROW EXECUTE FUNCTION reject_revoked_profile();

CREATE FUNCTION revoke_removed_identity() RETURNS trigger LANGUAGE plpgsql AS $revocation$
BEGIN
    UPDATE identity_authentication SET revoked = true WHERE subject_hash = NEW.subject_hash;
    RETURN NEW;
END
$revocation$;
CREATE TRIGGER revoke_removed_identity
    AFTER INSERT ON account_removal
    FOR EACH ROW EXECUTE FUNCTION revoke_removed_identity();

CREATE FUNCTION preserve_identity_revocation() RETURNS trigger LANGUAGE plpgsql AS $revocation$
BEGIN
    IF NEW.subject_hash <> OLD.subject_hash OR (OLD.revoked AND NOT NEW.revoked) THEN
        RAISE EXCEPTION 'Identity revocation is permanent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$revocation$;
CREATE TRIGGER preserve_identity_revocation
    BEFORE UPDATE ON identity_authentication
    FOR EACH ROW EXECUTE FUNCTION preserve_identity_revocation();

CREATE FUNCTION preserve_removal_request() RETURNS trigger LANGUAGE plpgsql AS $revocation$
BEGIN
    IF (NEW.owner_id IS NOT NULL AND NEW.owner_id IS DISTINCT FROM OLD.owner_id)
        OR (NEW.provider_subject IS NOT NULL AND NEW.provider_subject IS DISTINCT FROM OLD.provider_subject)
        OR (OLD.state = 'COMPLETE' AND NEW.state <> 'COMPLETE') THEN
        RAISE EXCEPTION 'Removal requests cannot change their account or reopen' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$revocation$;
CREATE TRIGGER preserve_removal_request
    BEFORE UPDATE ON account_removal
    FOR EACH ROW EXECUTE FUNCTION preserve_removal_request();
