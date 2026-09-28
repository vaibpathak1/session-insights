#!/usr/bin/env bash
# Rotates the DEV site's key in the local compose PostgreSQL: revokes the current key(s),
# inserts a new one, prints it once and writes it to examples/demo-site/.env.local
# (git-ignored). Local development only.
#
# Key format and hash match platform-domain SiteKeys exactly:
#   plaintext = "sk_dev_" + base64url(32 random bytes, no padding)
#   prefix    = first 12 characters     hash = lowercase hex SHA-256 of the UTF-8 plaintext
#
# The dev tenant is created by api-service's `dev` profile (DevSeedData); this script
# refuses to run without it.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Missing .env — run: cp .env.example .env" >&2; exit 1; }
set -a; source .env; set +a

DEV_TENANT_ID=00000000-0000-7000-8000-000000000001   # DevSeedData.DEV_TENANT_ID
ENV_FILE=examples/demo-site/.env.local
COLLECTOR_URL=${SI_COLLECTOR_URL:-http://localhost:8081}

# The compose owner role is a superuser, so row-level security does not hide rows here.
psql_owner() {
  docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qtAX "$@"
}

docker compose exec -T postgres pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" 2>/dev/null \
  || { echo "PostgreSQL is not running — run: docker compose up -d" >&2; exit 1; }

site_id=$(psql_owner <<SQL 2>/dev/null || true
SELECT s.id FROM site s JOIN tenant t ON t.id = s.tenant_id
WHERE t.id = '$DEV_TENANT_ID' AND t.is_active AND t.deleted_at IS NULL
  AND s.is_active AND s.deleted_at IS NULL
ORDER BY s.created_at LIMIT 1;
SQL
)
if [ -z "$site_id" ]; then
  cat >&2 <<EOF
Dev tenant $DEV_TENANT_ID (with an active site) not found.
Seed it once with api-service's dev profile, then re-run this script:
  mvn -q install -DskipTests
  mvn -q -pl services/api-service spring-boot:run -Dspring-boot.run.profiles=dev
EOF
  exit 1
fi

key="sk_dev_$(openssl rand 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
prefix=${key:0:12}
hash=$(printf '%s' "$key" | openssl dgst -sha256 -hex | awk '{print $NF}')
[ ${#key} -eq 50 ] && [ ${#hash} -eq 64 ] || { echo "Key generation failed" >&2; exit 1; }

revoked=$(psql_owner -v tenant="$DEV_TENANT_ID" -v site="$site_id" -v prefix="$prefix" -v hash="$hash" <<'SQL'
BEGIN;
WITH r AS (
  UPDATE site_key SET revoked_at = now(), updated_at = now(), version = version + 1
  WHERE tenant_id = :'tenant' AND site_id = :'site' AND revoked_at IS NULL
  RETURNING 1)
SELECT count(*) FROM r;
INSERT INTO site_key (tenant_id, site_id, key_prefix, key_hash) VALUES (:'tenant', :'site', :'prefix', :'hash');
COMMIT;
SQL
)

mkdir -p "$(dirname "$ENV_FILE")"
tmp=$(mktemp)
if [ -f "$ENV_FILE" ]; then
  grep -v -e '^VITE_SI_SITE_KEY=' "$ENV_FILE" > "$tmp" || true
fi
grep -q '^VITE_SI_COLLECTOR_URL=' "$tmp" || echo "VITE_SI_COLLECTOR_URL=$COLLECTOR_URL" >> "$tmp"
echo "VITE_SI_SITE_KEY=$key" >> "$tmp"
chmod 600 "$tmp" && mv "$tmp" "$ENV_FILE"

echo "Revoked $revoked key(s) for dev site $site_id. New dev site key (shown once):"
echo "  $key"
echo "Written to $ENV_FILE. Running collectors may accept the old key for up to ~60 s (ADR-0010)."
