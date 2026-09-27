-- Phase 1 / task 1.4: row-level security (ADR-0008).
-- Flyway runs as the schema owner; the application connects as ${appUser}, a non-owner,
-- non-superuser role, so RLS always applies to it. The app sets app.tenant_id per
-- transaction (set_config(..., true)); with no tenant set, every policy matches no rows.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${appUser}') THEN
        CREATE ROLE ${appUser} LOGIN PASSWORD '${appPassword}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ${appUser};

-- Config tables: no DELETE, soft delete only (is_active / deleted_at).
GRANT SELECT, INSERT, UPDATE ON tenant, site, site_key, masking_rule, model_provider_config, app_user
    TO ${appUser};
-- Telemetry-derived tables: hard delete for retention and erasure.
GRANT SELECT, INSERT, UPDATE, DELETE ON end_user, user_session, session_insight, erasure_request,
    external_ticket_link TO ${appUser};
-- Audit log is append-only for the application.
GRANT SELECT, INSERT ON review_event TO ${appUser};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${appUser};
-- New tables in later migrations must grant explicitly (no default privileges on purpose).

-- An unset or reset custom setting reads as '' once it has been used in the session;
-- NULLIF turns that into NULL so policies match nothing instead of failing the cast.
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = app_current_tenant())
    WITH CHECK (id = app_current_tenant());

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'site', 'site_key', 'masking_rule', 'model_provider_config', 'app_user',
        'end_user', 'user_session', 'session_insight', 'review_event', 'erasure_request',
        'external_ticket_link']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I'
                       ' USING (tenant_id = app_current_tenant())'
                       ' WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END
$$;
