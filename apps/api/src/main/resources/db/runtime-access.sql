DO $runtime$
DECLARE
    relation record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'closetos_app') THEN
        CREATE ROLE closetos_app LOGIN NOINHERIT;
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'closetos_app'
            AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
    ) OR EXISTS (
        SELECT 1 FROM pg_auth_members WHERE member = 'closetos_app'::regrole
    ) OR EXISTS (
        SELECT 1 FROM pg_shdepend WHERE refclassid = 'pg_authid'::regclass
            AND refobjid = 'closetos_app'::regrole AND deptype = 'o'
    ) THEN
        RAISE EXCEPTION 'Runtime role must not have administrative privileges, memberships, or owned objects';
    END IF;
    ALTER ROLE closetos_app LOGIN NOINHERIT;
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC, closetos_app', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO closetos_app', current_database());
    REVOKE ALL ON SCHEMA public FROM PUBLIC, closetos_app;
    GRANT USAGE ON SCHEMA public TO closetos_app;
    REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, closetos_app;
    REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, closetos_app;

    -- Table revocation does not clear separately granted column privileges.
    FOR relation IN
        SELECT c.relname, string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) AS columns
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
        WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
            AND a.attnum > 0 AND NOT a.attisdropped AND a.attacl IS NOT NULL
        GROUP BY c.relname
    LOOP
        EXECUTE format('REVOKE ALL (%s) ON TABLE public.%I FROM PUBLIC, closetos_app', relation.columns, relation.relname);
    END LOOP;
END
$runtime$;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    user_profile, wardrobe, garment, garment_image, processing_job,
    processed_integration_event, outbox_event, garment_ai_suggestion,
    outfit, outfit_item, wear_event, wear_event_garment, collection, collection_garment,
    embedding_model, garment_embedding, garment_embedding_work, reembedding_job,
    reembedding_item, packing_list, packing_item, workflow_slot, expensive_action_usage
TO closetos_app;

GRANT SELECT ON TABLE expensive_action_policy, workflow_capacity TO closetos_app;
-- SELECT FOR UPDATE needs an update privilege; the capacity value stays administrator controlled.
GRANT UPDATE (id) ON TABLE workflow_capacity TO closetos_app;
