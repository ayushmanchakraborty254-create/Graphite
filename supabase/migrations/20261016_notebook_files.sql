-- ======================================================================================
-- Graphite Supabase Database Migration: Document & PDF Support for Noteshelf
-- Target Table: public.notebooks
-- ======================================================================================

-- 1. Add optional columns for document attachments (MIME type, file name, page count)
ALTER TABLE public.notebooks
    ADD COLUMN IF NOT EXISTS mime_type TEXT,
    ADD COLUMN IF NOT EXISTS original_file_name TEXT,
    ADD COLUMN IF NOT EXISTS page_count INT DEFAULT 1;

-- 2. Ensure RLS policies continue to govern access
ALTER TABLE public.notebooks ENABLE ROW LEVEL SECURITY;
