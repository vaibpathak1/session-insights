-- Phase 5 / task 5.6: tenant binding for the API (ADR-0013, same pattern as ADR-0010).
-- An authenticated API user must be mapped to a tenant before any tenant is set, but RLS
-- hides every app_user row until app.tenant_id is set. resolve_app_user() is the single
-- pre-tenant read for the API: an active user's id, tenant and role, and nothing else.
-- Dev login matches by email now; OIDC (Phase 11) will look up external_subject_id the same way.

-- FORCE ROW LEVEL SECURITY binds the owner too; the SECURITY DEFINER function runs as the
-- owner. V4 added owner read policies on tenant/site/site_key; app_user needs one as well.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY user_resolution_owner_read ON app_user FOR SELECT TO %I USING (true)',
                   (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.app_user'::regclass));
END
$$;

-- Returns one row per matching active user of an active tenant. The same email may exist in
-- several tenants; the caller must refuse an ambiguous login rather than pick one.
CREATE FUNCTION resolve_app_user(p_email text)
    RETURNS TABLE (user_id uuid, tenant_id uuid, role text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
        SELECT u.id, u.tenant_id, u.role
        FROM public.app_user u
        JOIN public.tenant t ON t.id = u.tenant_id
        WHERE lower(u.email) = lower(p_email)
          AND u.is_active AND u.deleted_at IS NULL
          AND t.is_active AND t.deleted_at IS NULL
    $$;

REVOKE ALL ON FUNCTION resolve_app_user(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_app_user(text) TO ${appUser};
