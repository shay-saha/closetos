DO $upgrade$
BEGIN
    IF EXISTS (SELECT 1 FROM account_removal WHERE state <> 'COMPLETE' AND data_erased_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Finish existing account removals before upgrading: their erased records cannot provide a complete photo cleanup plan';
    END IF;
END
$upgrade$;

CREATE TABLE account_removal_source (
    request_id uuid NOT NULL REFERENCES account_removal(id) ON DELETE CASCADE,
    source_key text NOT NULL CHECK (source_key ~ '^users/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/garments/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/images/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/original\.(jpg|jpeg|png|webp|heic|heif)$'),
    PRIMARY KEY (request_id, source_key)
);

CREATE FUNCTION validate_removal_source() RETURNS trigger LANGUAGE plpgsql AS $source$
DECLARE
    receipt account_removal%ROWTYPE;
BEGIN
    SELECT * INTO receipt FROM account_removal WHERE id = NEW.request_id FOR KEY SHARE;
    IF receipt.owner_id IS NULL OR receipt.state <> 'REMOVING' OR receipt.data_erased_at IS NOT NULL
        OR split_part(NEW.source_key, '/', 2) <> receipt.owner_id::text THEN
        RAISE EXCEPTION 'Photo cleanup sources must belong to the account being removed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$source$;
CREATE TRIGGER validate_removal_source BEFORE INSERT ON account_removal_source
    FOR EACH ROW EXECUTE FUNCTION validate_removal_source();

CREATE FUNCTION clear_completed_removal_sources() RETURNS trigger LANGUAGE plpgsql AS $source$
BEGIN
    IF NEW.state = 'COMPLETE' THEN
        DELETE FROM account_removal_source WHERE request_id = NEW.id;
    END IF;
    RETURN NEW;
END
$source$;
CREATE TRIGGER clear_completed_removal_sources AFTER UPDATE OF state ON account_removal
    FOR EACH ROW EXECUTE FUNCTION clear_completed_removal_sources();
