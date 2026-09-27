-- Phase 2 / task 2.2: tenant resolution at the ingestion edge (ADR-0010).
-- The collector knows only a site key; RLS hides site_key rows until a tenant is set.
-- resolve_site_key() is the single pre-tenant read path. The collector role may call it and
-- nothing else: no table privileges at all.

-- FORCE ROW LEVEL SECURITY binds the table owner too. A superuser owner (local compose)
-- bypasses RLS anyway; a non-superuser owner needs these policies for the SECURITY DEFINER
-- function to see rows. The owner can already disable RLS, so this grants it nothing new.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenant', 'site', 'site_key']
    LOOP
        EXECUTE format('CREATE POLICY key_resolution_owner_read ON %I FOR SELECT TO %I USING (true)',
                       t, (SELECT pg_get_userbyid(relowner) FROM pg_class
                           WHERE oid = format('public.%I', t)::regclass));
    END LOOP;
END
$$;

CREATE FUNCTION resolve_site_key(p_key_hash text)
    RETURNS TABLE (tenant_id uuid, site_id uuid, allowed_origins text[], sampling_rate numeric)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
        SELECT s.tenant_id, s.id, s.allowed_origins, s.sampling_rate
        FROM public.site_key k
        JOIN public.site s ON s.id = k.site_id AND s.tenant_id = k.tenant_id
        JOIN public.tenant t ON t.id = s.tenant_id
        WHERE k.key_hash = p_key_hash
          AND k.revoked_at IS NULL AND k.is_active AND k.deleted_at IS NULL
          AND s.is_active AND s.deleted_at IS NULL
          AND t.is_active AND t.deleted_at IS NULL
    $$;

REVOKE ALL ON FUNCTION resolve_site_key(text) FROM PUBLIC;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${collectorUser}') THEN
        CREATE ROLE ${collectorUser} LOGIN PASSWORD '${collectorPassword}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ${collectorUser};
GRANT EXECUTE ON FUNCTION resolve_site_key(text) TO ${collectorUser};
