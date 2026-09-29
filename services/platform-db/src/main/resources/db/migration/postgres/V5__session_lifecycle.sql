-- Phase 5 / task 5.1: session lifecycle written by event-processor (ADR-0008, ADR-0011).
-- The processor connects as ${processorUser}: SELECT/INSERT/UPDATE on user_session and
-- end_user only (SELECT because INSERT ... ON CONFLICT DO UPDATE and UPDATE ... WHERE read
-- the existing row), no DELETE, no BYPASSRLS: it sets app.tenant_id per transaction like the
-- application. Its only cross-tenant access is claim_sessions_to_close().

-- Earliest NAVIGATION seen so far, so entry_url stays right when events arrive out of order.
ALTER TABLE user_session ADD COLUMN entry_at timestamptz;
-- Set by the tracker when an event arrives for a closed session; the closer recomputes.
ALTER TABLE user_session ADD COLUMN needs_recompute boolean NOT NULL DEFAULT false;

-- The tracker upserts visitors by site + anonymous id (identify() is a later phase).
DROP INDEX end_user_site_anonymous_idx;
CREATE UNIQUE INDEX end_user_site_anonymous_uq ON end_user (site_id, anonymous_id);

-- What the closer scans: open sessions by idleness, and closed sessions to recompute.
CREATE INDEX user_session_open_idle_idx ON user_session (last_active_at) WHERE ended_at IS NULL;
CREATE INDEX user_session_recompute_idx ON user_session (last_active_at) WHERE needs_recompute;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${processorUser}') THEN
        CREATE ROLE ${processorUser} LOGIN PASSWORD '${processorPassword}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ${processorUser};
GRANT SELECT, INSERT, UPDATE ON user_session, end_user TO ${processorUser};

-- FORCE ROW LEVEL SECURITY binds the table owner too; the SECURITY DEFINER function below
-- runs as the owner and must see and lock rows of every tenant. Same pattern as V4: the owner
-- can already disable RLS, so this grants it nothing new, and application roles are unaffected.
DO $$
DECLARE
    owner text := (SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.user_session'::regclass);
BEGIN
    EXECUTE format('CREATE POLICY lifecycle_owner_read ON user_session FOR SELECT TO %I USING (true)', owner);
    EXECUTE format('CREATE POLICY lifecycle_owner_lock ON user_session FOR UPDATE TO %I USING (true)', owner);
END
$$;

-- Claims up to p_limit sessions for one closer pass and locks them until the caller's
-- transaction ends. FOR UPDATE SKIP LOCKED: two closers never claim the same session.
--   CLOSE     open sessions idle for longer than p_idle (last_active_at is server time)
--   RECOMPUTE closed sessions that received late events (needs_recompute)
-- Only ids are returned; the caller reads and updates the rows under its tenant (RLS).
CREATE FUNCTION claim_sessions_to_close(p_idle interval, p_limit int)
    RETURNS TABLE (tenant_id uuid, session_id uuid, kind text)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
        SELECT s.tenant_id, s.id, CASE WHEN s.ended_at IS NULL THEN 'CLOSE' ELSE 'RECOMPUTE' END
        FROM public.user_session s
        WHERE (s.ended_at IS NULL AND s.last_active_at < now() - p_idle)
           OR s.needs_recompute
        ORDER BY s.last_active_at
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    $$;

REVOKE ALL ON FUNCTION claim_sessions_to_close(interval, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_sessions_to_close(interval, int) TO ${processorUser};
