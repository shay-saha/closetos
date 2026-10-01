ALTER TABLE account_removal
    ADD COLUMN lease_token uuid,
    ADD COLUMN data_erased_at timestamptz,
    ADD COLUMN workers_drained_at timestamptz,
    ADD COLUMN provider_erased_at timestamptz,
    ADD COLUMN media_erased_at timestamptz,
    ADD COLUMN media_purge_after timestamptz;

-- Legacy receipts have no lease owner and can be resumed by the new processor.
UPDATE account_removal SET lease_until = NULL WHERE lease_until IS NOT NULL;
ALTER TABLE account_removal ADD CONSTRAINT account_removal_lease CHECK (
    (lease_token IS NULL AND lease_until IS NULL)
    OR (lease_token IS NOT NULL AND lease_until IS NOT NULL AND state = 'REMOVING')
);
ALTER TABLE account_removal ADD CONSTRAINT account_removal_stages CHECK (
    (data_erased_at IS NULL AND media_purge_after IS NULL AND workers_drained_at IS NULL
        AND provider_erased_at IS NULL AND media_erased_at IS NULL)
    OR (data_erased_at IS NOT NULL AND media_purge_after IS NOT NULL AND media_purge_after >= data_erased_at + interval '15 minutes'
        AND (workers_drained_at IS NULL OR workers_drained_at >= data_erased_at)
        AND (provider_erased_at IS NULL OR provider_erased_at >= data_erased_at)
        AND (media_erased_at IS NULL OR (workers_drained_at IS NOT NULL AND media_erased_at >= media_purge_after)))
);

CREATE FUNCTION preserve_removal_progress() RETURNS trigger LANGUAGE plpgsql AS $progress$
BEGIN
    IF (OLD.data_erased_at IS NOT NULL AND NEW.data_erased_at IS DISTINCT FROM OLD.data_erased_at)
        OR (OLD.media_purge_after IS NOT NULL AND NEW.media_purge_after IS DISTINCT FROM OLD.media_purge_after)
        OR (OLD.workers_drained_at IS NOT NULL AND NEW.workers_drained_at IS DISTINCT FROM OLD.workers_drained_at)
        OR (OLD.provider_erased_at IS NOT NULL AND NEW.provider_erased_at IS DISTINCT FROM OLD.provider_erased_at)
        OR (OLD.media_erased_at IS NOT NULL AND NEW.media_erased_at IS DISTINCT FROM OLD.media_erased_at) THEN
        RAISE EXCEPTION 'Account removal checkpoints are permanent' USING ERRCODE = '23514';
    END IF;
    IF OLD.state <> 'COMPLETE' AND NEW.state = 'COMPLETE'
        AND (NEW.data_erased_at IS NULL OR NEW.workers_drained_at IS NULL
            OR NEW.provider_erased_at IS NULL OR NEW.media_erased_at IS NULL) THEN
        RAISE EXCEPTION 'Account removal requires every cleanup stage' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$progress$;
CREATE TRIGGER preserve_removal_progress
    BEFORE UPDATE ON account_removal
    FOR EACH ROW EXECUTE FUNCTION preserve_removal_progress();
