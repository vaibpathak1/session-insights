-- Runs once, on first container start only.
-- Schema itself is owned by Flyway (Phase 1); this only proves the image
-- ships pgvector so the stack check fails fast if it doesn't.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() for defaults
