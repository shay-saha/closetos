ALTER TABLE reembedding_job ALTER COLUMN requested_by DROP NOT NULL;

INSERT INTO identity_authentication (subject_hash)
SELECT DISTINCT encode(sha256(convert_to(requested_by, 'UTF8')), 'hex')
FROM reembedding_job WHERE requested_by IS NOT NULL
ON CONFLICT DO NOTHING;

UPDATE reembedding_job j SET requested_by = NULL
WHERE EXISTS (
    SELECT 1 FROM identity_authentication a
    WHERE a.revoked AND a.subject_hash = encode(sha256(convert_to(j.requested_by, 'UTF8')), 'hex')
);

CREATE FUNCTION guard_embedding_requester() RETURNS trigger LANGUAGE plpgsql AS $requester$
DECLARE
    is_revoked boolean;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.requested_by IS NOT NULL
        AND NEW.requested_by IS DISTINCT FROM OLD.requested_by THEN
        RAISE EXCEPTION 'Embedding jobs cannot change their requester' USING ERRCODE = '23514';
    END IF;
    IF NEW.requested_by IS NULL THEN
        IF TG_OP = 'INSERT' THEN
            RAISE EXCEPTION 'New embedding jobs require an active requester' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    -- Serialize admission with removal, including requests using an obsolete snapshot.
    INSERT INTO identity_authentication (subject_hash)
    VALUES (encode(sha256(convert_to(NEW.requested_by, 'UTF8')), 'hex'))
    ON CONFLICT (subject_hash) DO UPDATE SET revoked = identity_authentication.revoked
    RETURNING revoked INTO is_revoked;
    IF is_revoked THEN
        RAISE EXCEPTION 'Removed accounts cannot request embedding jobs' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$requester$;
CREATE TRIGGER guard_embedding_requester
    BEFORE INSERT OR UPDATE OF requested_by ON reembedding_job
    FOR EACH ROW EXECUTE FUNCTION guard_embedding_requester();

CREATE FUNCTION erase_embedding_requester() RETURNS trigger LANGUAGE plpgsql AS $requester$
BEGIN
    UPDATE reembedding_job SET requested_by = NULL
    WHERE requested_by IS NOT NULL
        AND encode(sha256(convert_to(requested_by, 'UTF8')), 'hex') = NEW.subject_hash;
    RETURN NEW;
END
$requester$;
-- PostgreSQL runs same-kind triggers by name; revoke admission before taking the cleanup snapshot.
CREATE TRIGGER scrub_embedding_requester
    AFTER INSERT ON account_removal
    FOR EACH ROW EXECUTE FUNCTION erase_embedding_requester();
