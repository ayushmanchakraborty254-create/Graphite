-- ======================================================================================
-- Graphite Supabase Database Schema & Strict Row Level Security (RLS) Policies
-- Project: Graphite Android Client
-- Target Table: public.notebooks
-- ======================================================================================

-- 1. Create notebooks table
CREATE TABLE IF NOT EXISTS public.notebooks (
    id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE DEFAULT auth.uid(),
    title TEXT NOT NULL,
    folder_id TEXT NOT NULL DEFAULT 'my_notes',
    paper_style TEXT NOT NULL DEFAULT 'RULED',
    preview_lines JSONB NOT NULL DEFAULT '[]'::jsonb,
    strokes_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT timezone('utc'::text, now()),
    updated_at TEXT NOT NULL DEFAULT to_char(now(), 'DD/MM/YY HH12:MI am')
);

-- Index user_id for fast RLS queries
CREATE INDEX IF NOT EXISTS notebooks_user_id_idx ON public.notebooks(user_id);

-- 2. Enable Row Level Security (RLS)
ALTER TABLE public.notebooks ENABLE ROW LEVEL SECURITY;

-- 3. Strict Row Level Security Policies
-- Policy 1: Authenticated users can only query/select their own notes
CREATE POLICY "Users can only view their own notes"
    ON public.notebooks
    FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

-- Policy 2: Authenticated users can only insert notes where user_id matches auth.uid()
CREATE POLICY "Users can only insert notes with their own auth.uid"
    ON public.notebooks
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

-- Policy 3: Authenticated users can only update notes they own
CREATE POLICY "Users can only update their own notes"
    ON public.notebooks
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- Policy 4: Authenticated users can only delete notes they own
CREATE POLICY "Users can only delete their own notes"
    ON public.notebooks
    FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- Policy 5: Explicitly deny all public / unauthenticated access (belt-and-suspenders)
REVOKE ALL ON public.notebooks FROM anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.notebooks TO authenticated;

-- ======================================================================================
-- Verification Checklist:
-- 1. Unauthenticated requests receive HTTP 401 or empty result sets.
-- 2. Authenticated requests with JWT only access rows matching `auth.uid() = user_id`.
-- 3. Google Drive synchronization is NEVER triggered by Supabase or database webhooks.
-- ======================================================================================
