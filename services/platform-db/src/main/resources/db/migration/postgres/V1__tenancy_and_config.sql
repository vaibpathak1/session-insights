-- Phase 1 / task 1.2: tenancy and configuration (F8).
-- Config entities are soft-deleted (is_active, deleted_at); every row carries tenant_id (ADR-0008).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenant (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   text        NOT NULL,
    -- retention defaults per docs/requirements.md: events 30 d, replays 30 d, insights 13 months
    event_retention_days   integer     NOT NULL DEFAULT 30  CHECK (event_retention_days > 0),
    replay_retention_days  integer     NOT NULL DEFAULT 30  CHECK (replay_retention_days > 0),
    insight_retention_days integer     NOT NULL DEFAULT 395 CHECK (insight_retention_days > 0),
    external_llm_allowed   boolean     NOT NULL DEFAULT false,
    is_active              boolean     NOT NULL DEFAULT true,
    deleted_at             timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0
);

CREATE TABLE site (
    id                uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    name              text          NOT NULL,
    allowed_origins   text[]        NOT NULL DEFAULT '{}',
    sampling_rate     numeric(4, 3) NOT NULL DEFAULT 1.0 CHECK (sampling_rate BETWEEN 0 AND 1),
    signal_thresholds jsonb         NOT NULL DEFAULT '{}',
    is_active         boolean       NOT NULL DEFAULT true,
    deleted_at        timestamptz,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    version           bigint        NOT NULL DEFAULT 0
);
CREATE INDEX site_tenant_idx ON site (tenant_id);

CREATE TABLE site_key (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    site_id    uuid        NOT NULL REFERENCES site (id),
    key_prefix text        NOT NULL,              -- first characters, for display only
    key_hash   text        NOT NULL UNIQUE,       -- SHA-256 of the key; plaintext is never stored
    revoked_at timestamptz,
    is_active  boolean     NOT NULL DEFAULT true,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0
);
CREATE INDEX site_key_site_idx ON site_key (site_id);

CREATE TABLE masking_rule (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    site_id      uuid        NOT NULL REFERENCES site (id),
    css_selector text        NOT NULL,
    action       text        NOT NULL CHECK (action IN ('MASK', 'UNMASK', 'BLOCK')),
    is_active    boolean     NOT NULL DEFAULT true,
    deleted_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0
);
CREATE INDEX masking_rule_site_idx ON masking_rule (site_id);

-- No secrets here: API keys for external providers come from the environment (ADR-0005).
CREATE TABLE model_provider_config (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    provider        text        NOT NULL CHECK (provider IN ('OLLAMA', 'OPENAI', 'ANTHROPIC')),
    enabled         boolean     NOT NULL DEFAULT false,
    chat_model      text        NOT NULL,
    embedding_model text,
    is_active       boolean     NOT NULL DEFAULT true,
    deleted_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX model_provider_config_tenant_provider_uq
    ON model_provider_config (tenant_id, provider) WHERE is_active;

-- Dashboard users (not recorded end users; see end_user in V2).
CREATE TABLE app_user (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    email               text        NOT NULL,
    display_name        text        NOT NULL,
    role                text        NOT NULL CHECK (role IN ('ADMIN', 'ANALYST', 'VIEWER')),
    external_subject_id text,                     -- OIDC "sub", set once OIDC login exists
    is_active           boolean     NOT NULL DEFAULT true,
    deleted_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX app_user_tenant_email_uq ON app_user (tenant_id, lower(email)) WHERE is_active;
CREATE UNIQUE INDEX app_user_external_subject_uq ON app_user (external_subject_id)
    WHERE external_subject_id IS NOT NULL AND is_active;
