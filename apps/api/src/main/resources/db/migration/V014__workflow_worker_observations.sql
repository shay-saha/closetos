CREATE TABLE workflow_task (
    task_arn varchar(512) PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES workflow_slot(job_id) ON DELETE CASCADE,
    observed_at timestamptz NOT NULL DEFAULT now(),
    stopped_at timestamptz,
    CHECK (task_arn ~ '^arn:aws(-[a-z]+)?:ecs:[a-z0-9-]+:[0-9]{12}:task/[^/]+/[a-f0-9]{32}$')
);
CREATE INDEX workflow_task_job ON workflow_task(job_id);

CREATE FUNCTION retain_worker_stop_confirmation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.stopped_at IS NOT NULL AND NEW.stopped_at IS DISTINCT FROM OLD.stopped_at THEN
        RAISE EXCEPTION 'Worker stop confirmation is permanent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retain_worker_stop_confirmation BEFORE UPDATE ON workflow_task
    FOR EACH ROW EXECUTE FUNCTION retain_worker_stop_confirmation();
