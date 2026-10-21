-- ======================================================================================
-- Graphite Supabase Database Migration: Explicit updated_at_millis Column
-- Target Table: public.notebooks
-- ======================================================================================

ALTER TABLE public.notebooks
    ADD COLUMN IF NOT EXISTS updated_at_millis BIGINT DEFAULT 0;
