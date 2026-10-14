-- ======================================================================================
-- Graphite Supabase Database Migration: Cross-Device Offline-First Sync & Tombstones
-- Project: Graphite Android Client
-- Target Table: public.notebooks
-- ======================================================================================

-- 1. Add columns for timestamp-based conflict resolution (LWW), tombstones, and versioning
ALTER TABLE public.notebooks
    ADD COLUMN IF NOT EXISTS updated_at_millis BIGINT NOT NULL DEFAULT (EXTRACT(EPOCH FROM now()) * 1000)::bigint,
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1;

-- 2. Create index on (user_id, updated_at_millis) for fast incremental sync queries
CREATE INDEX IF NOT EXISTS notebooks_user_id_updated_idx ON public.notebooks(user_id, updated_at_millis);

-- 3. Ensure Row Level Security (RLS) is enabled and enforced
ALTER TABLE public.notebooks ENABLE ROW LEVEL SECURITY;

-- Note on RLS and Cross-Device Sync:
-- By including `is_deleted = true` rows in the SELECT policy, device B can query updates
-- since its last sync and observe that device A deleted a note, properly removing it locally
-- without ever exposing any other user's private notes.
