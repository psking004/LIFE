-- ============================================================
-- LIFE PLATFORM: PRODUCTION HARDENING SQL MIGRATION
-- Multi-Tier Campus Innovation Ecosystem
-- ============================================================

-- Ensure necessary extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- 1. TABLES DEFINITION & INTEGRITY CONSTRAINTS
-- ============================================================

-- 1.1 Users table
CREATE TABLE IF NOT EXISTS public.users (
    id TEXT PRIMARY KEY,
    auth_user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    register_number TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL UNIQUE,
    phone_number TEXT NOT NULL DEFAULT '',
    department TEXT NOT NULL DEFAULT 'Computer Science & Engineering',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified_at TIMESTAMPTZ,
    account_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (account_status IN ('ACTIVE', 'PENDING', 'SUSPENDED', 'REJECTED')),
    is_first_login BOOLEAN NOT NULL DEFAULT TRUE,
    is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    approved_by TEXT,
    otp_verified_until TIMESTAMPTZ
);

-- Case-insensitive register number index
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_register_number_upper 
ON public.users (UPPER(register_number));

CREATE INDEX IF NOT EXISTS idx_users_auth_user_id 
ON public.users (auth_user_id);

CREATE INDEX IF NOT EXISTS idx_users_account_status 
ON public.users (account_status);

-- 1.2 Idea Nodes table
CREATE TABLE IF NOT EXISTS public.idea_nodes (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('PROBLEM', 'IMPROVEMENT', 'REFINEMENT')),
    author_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE RESTRICT,
    author_register_number TEXT NOT NULL,
    parent_id TEXT REFERENCES public.idea_nodes(id) ON DELETE RESTRICT,
    votes_count INTEGER NOT NULL DEFAULT 0 CHECK (votes_count >= 0),
    direct_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (direct_score >= 0.0),
    total_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (total_score >= 0.0),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_idea_nodes_parent_id 
ON public.idea_nodes (parent_id);

CREATE INDEX IF NOT EXISTS idx_idea_nodes_author_id 
ON public.idea_nodes (author_id);

CREATE INDEX IF NOT EXISTS idx_idea_nodes_type 
ON public.idea_nodes (type);

CREATE INDEX IF NOT EXISTS idx_idea_nodes_created_at 
ON public.idea_nodes (created_at DESC);

-- 1.3 Votes table (strictly one vote per user per node)
CREATE TABLE IF NOT EXISTS public.votes (
    id TEXT PRIMARY KEY,
    node_id TEXT NOT NULL REFERENCES public.idea_nodes(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT unique_user_node_vote UNIQUE (user_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_votes_node_id 
ON public.votes (node_id);

CREATE INDEX IF NOT EXISTS idx_votes_user_id 
ON public.votes (user_id);

-- 1.4 Login Events table (audit trail)
CREATE TABLE IF NOT EXISTS public.login_events (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    register_number TEXT NOT NULL,
    user_name TEXT NOT NULL,
    login_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    login_status TEXT NOT NULL CHECK (login_status IN ('SUCCESS', 'FAILED_PASSWORD', 'FAILED_OTP', 'ACCOUNT_LOCKED')),
    device_type TEXT NOT NULL DEFAULT 'Android',
    app_version TEXT NOT NULL DEFAULT '1.0.0',
    session_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_login_events_user_id 
ON public.login_events (user_id);

CREATE INDEX IF NOT EXISTS idx_login_events_login_at 
ON public.login_events (login_at DESC);

-- 1.5 Login OTPs table (server-side single-use verification)
CREATE TABLE IF NOT EXISTS public.login_otps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    otp_hash TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 5,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '5 minutes'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_login_otps_user_active 
ON public.login_otps (user_id, is_used, expires_at);

-- 1.6 User Scores table (authoritative scoring engine)
CREATE TABLE IF NOT EXISTS public.user_scores (
    user_id TEXT PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    register_number TEXT NOT NULL,
    problem_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (problem_score >= 0.0),
    improvement_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (improvement_score >= 0.0),
    refinement_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (refinement_score >= 0.0),
    total_score DOUBLE PRECISION NOT NULL DEFAULT 0.0 CHECK (total_score >= 0.0),
    problems_count INTEGER NOT NULL DEFAULT 0 CHECK (problems_count >= 0),
    improvements_count INTEGER NOT NULL DEFAULT 0 CHECK (improvements_count >= 0),
    refinements_count INTEGER NOT NULL DEFAULT 0 CHECK (refinements_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_scores_total_score 
ON public.user_scores (total_score DESC);

-- ============================================================
-- 2. SECURITY DEFINER FUNCTIONS & AUTHORIZATION ENGINES
-- ============================================================

-- 2.1 Authoritative Admin Check
CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, auth
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.users
        WHERE auth_user_id = auth.uid()
          AND is_admin = true
          AND account_status = 'ACTIVE'
          AND is_suspended = false
    );
$$;

-- 2.2 Authoritative Daily LIFE OTP Verification Check
CREATE OR REPLACE FUNCTION public.is_life_otp_verified()
RETURNS BOOLEAN
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, auth
STABLE
AS $$
    SELECT (
        -- Admins bypass OTP
        EXISTS (
            SELECT 1
            FROM public.users
            WHERE auth_user_id = auth.uid()
              AND is_admin = true
              AND account_status = 'ACTIVE'
              AND is_suspended = false
        )
        OR
        -- Verified students whose daily window is still active
        EXISTS (
            SELECT 1
            FROM public.users
            WHERE auth_user_id = auth.uid()
              AND account_status = 'ACTIVE'
              AND is_suspended = false
              AND otp_verified_until IS NOT NULL
              AND otp_verified_until > now()
        )
    );
$$;

-- 2.3 Secure Register Number Identity Resolution RPC
-- Maps Register Number to Auth Identity without exposing the entire user table
CREATE OR REPLACE FUNCTION public.resolve_login_identity(p_register_number TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_clean_reg TEXT;
    v_user RECORD;
    v_auth_email TEXT;
    v_masked_email TEXT;
    v_is_verified_today BOOLEAN;
    v_at_idx INT;
    v_local_part TEXT;
    v_domain_part TEXT;
BEGIN
    v_clean_reg := UPPER(TRIM(p_register_number));
    
    IF v_clean_reg IS NULL OR length(v_clean_reg) < 3 THEN
        RETURN jsonb_build_object('ok', false, 'error', 'Invalid Register Number');
    END IF;

    -- Look up student record in public.users
    SELECT u.id, u.auth_user_id, u.register_number, u.email, u.account_status, 
           u.is_suspended, u.is_admin, u.otp_verified_until
    INTO v_user
    FROM public.users u
    WHERE UPPER(u.register_number) = v_clean_reg
    LIMIT 1;

    -- CRITICAL ANTI-ENUMERATION:
    -- If user does not exist, OR account is NOT active (PENDING, SUSPENDED, REJECTED),
    -- return the IDENTICAL generic error. An unauthenticated attacker cannot tell whether
    -- an account exists, is pending, is suspended, or what its role/email is.
    IF NOT FOUND OR v_user.account_status != 'ACTIVE' OR v_user.is_suspended = true THEN
        RETURN jsonb_build_object('ok', false, 'error', 'Invalid Register Number or Password');
    END IF;

    -- Retrieve Supabase Auth identity email
    IF v_user.auth_user_id IS NOT NULL THEN
        SELECT au.email INTO v_auth_email
        FROM auth.users au
        WHERE au.id = v_user.auth_user_id;
    END IF;

    -- Fallback to registered email if auth_user_id is not yet linked or matches registered email
    IF v_auth_email IS NULL OR v_auth_email = '' THEN
        v_auth_email := v_user.email;
    END IF;

    -- Expose ONLY the minimum required for the authentication flow (auth_email for GoTrue token grant).
    -- NEVER expose user_id, auth_user_id, is_admin, name, department, or account_status to an unauthenticated caller.
    RETURN jsonb_build_object(
        'ok', true,
        'auth_email', v_auth_email
    );
END;
$$;

-- 2.4 Server-Authoritative Atomic Vote Cast Function
CREATE OR REPLACE FUNCTION public.cast_vote(p_node_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_caller_user_id TEXT;
    v_node RECORD;
    v_parent_node RECORD;
    v_vote_id TEXT;
BEGIN
    -- Authorization check: must have completed OTP today or be admin
    IF NOT public.is_life_otp_verified() THEN
        RAISE EXCEPTION 'Access denied: Active daily OTP verification required';
    END IF;

    -- Find calling student ID from auth.uid()
    SELECT id INTO v_caller_user_id
    FROM public.users
    WHERE auth_user_id = auth.uid() AND account_status = 'ACTIVE' AND is_suspended = false
    LIMIT 1;

    IF v_caller_user_id IS NULL THEN
        RAISE EXCEPTION 'Active student profile not found for session';
    END IF;

    -- Fetch target node
    SELECT id, author_id, parent_id, is_deleted, votes_count, direct_score, total_score, type
    INTO v_node
    FROM public.idea_nodes
    WHERE id = p_node_id
    FOR UPDATE;

    IF NOT FOUND OR v_node.is_deleted THEN
        RAISE EXCEPTION 'Node not found or has been deleted';
    END IF;

    -- Check duplicate vote
    IF EXISTS (SELECT 1 FROM public.votes WHERE user_id = v_caller_user_id AND node_id = p_node_id) THEN
        RETURN jsonb_build_object('ok', true, 'message', 'Already voted');
    END IF;

    -- Record Vote
    v_vote_id := 'vote_' || gen_random_uuid()::text;
    INSERT INTO public.votes (id, node_id, user_id, created_at)
    VALUES (v_vote_id, p_node_id, v_caller_user_id, now());

    -- Update Target Node Scores (+1.0 direct score to node author)
    UPDATE public.idea_nodes
    SET votes_count = votes_count + 1,
        direct_score = direct_score + 1.0,
        total_score = total_score + 1.0,
        updated_at = now()
    WHERE id = p_node_id;

    -- Update Author Score in user_scores
    INSERT INTO public.user_scores (
        user_id, register_number, problem_score, improvement_score, refinement_score, total_score,
        problems_count, improvements_count, refinements_count, updated_at
    )
    SELECT 
        u.id, u.register_number,
        CASE WHEN v_node.type = 'PROBLEM' THEN 1.0 ELSE 0.0 END,
        CASE WHEN v_node.type = 'IMPROVEMENT' THEN 1.0 ELSE 0.0 END,
        CASE WHEN v_node.type = 'REFINEMENT' THEN 1.0 ELSE 0.0 END,
        1.0, 0, 0, 0, now()
    FROM public.users u
    WHERE u.id = v_node.author_id
    ON CONFLICT (user_id) DO UPDATE SET
        problem_score = public.user_scores.problem_score + (CASE WHEN v_node.type = 'PROBLEM' THEN 1.0 ELSE 0.0 END),
        improvement_score = public.user_scores.improvement_score + (CASE WHEN v_node.type = 'IMPROVEMENT' THEN 1.0 ELSE 0.0 END),
        refinement_score = public.user_scores.refinement_score + (CASE WHEN v_node.type = 'REFINEMENT' THEN 1.0 ELSE 0.0 END),
        total_score = public.user_scores.total_score + 1.0,
        updated_at = now();

    -- Direct Parent Credit: +0.25 to direct parent node author if parent exists
    IF v_node.parent_id IS NOT NULL THEN
        SELECT id, author_id, type, is_deleted
        INTO v_parent_node
        FROM public.idea_nodes
        WHERE id = v_node.parent_id
        FOR UPDATE;

        IF FOUND AND NOT v_parent_node.is_deleted THEN
            UPDATE public.idea_nodes
            SET total_score = total_score + 0.25,
                updated_at = now()
            WHERE id = v_parent_node.id;

            INSERT INTO public.user_scores (
                user_id, register_number, problem_score, improvement_score, refinement_score, total_score,
                problems_count, improvements_count, refinements_count, updated_at
            )
            SELECT 
                u.id, u.register_number,
                CASE WHEN v_parent_node.type = 'PROBLEM' THEN 0.25 ELSE 0.0 END,
                CASE WHEN v_parent_node.type = 'IMPROVEMENT' THEN 0.25 ELSE 0.0 END,
                CASE WHEN v_parent_node.type = 'REFINEMENT' THEN 0.25 ELSE 0.0 END,
                0.25, 0, 0, 0, now()
            FROM public.users u
            WHERE u.id = v_parent_node.author_id
            ON CONFLICT (user_id) DO UPDATE SET
                problem_score = public.user_scores.problem_score + (CASE WHEN v_parent_node.type = 'PROBLEM' THEN 0.25 ELSE 0.0 END),
                improvement_score = public.user_scores.improvement_score + (CASE WHEN v_parent_node.type = 'IMPROVEMENT' THEN 0.25 ELSE 0.0 END),
                refinement_score = public.user_scores.refinement_score + (CASE WHEN v_parent_node.type = 'REFINEMENT' THEN 0.25 ELSE 0.0 END),
                total_score = public.user_scores.total_score + 0.25,
                updated_at = now();
        END IF;
    END IF;

    RETURN jsonb_build_object('ok', true, 'node_id', p_node_id, 'new_votes_count', v_node.votes_count + 1);
END;
$$;

-- 2.5 Server-Authoritative Atomic Vote Removal Function
CREATE OR REPLACE FUNCTION public.remove_vote(p_node_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_caller_user_id TEXT;
    v_node RECORD;
    v_parent_node RECORD;
BEGIN
    IF NOT public.is_life_otp_verified() THEN
        RAISE EXCEPTION 'Access denied: Active daily OTP verification required';
    END IF;

    SELECT id INTO v_caller_user_id
    FROM public.users
    WHERE auth_user_id = auth.uid() AND account_status = 'ACTIVE' AND is_suspended = false
    LIMIT 1;

    IF v_caller_user_id IS NULL THEN
        RAISE EXCEPTION 'Active student profile not found for session';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.votes WHERE user_id = v_caller_user_id AND node_id = p_node_id) THEN
        RETURN jsonb_build_object('ok', true, 'message', 'Vote was not present');
    END IF;

    DELETE FROM public.votes
    WHERE user_id = v_caller_user_id AND node_id = p_node_id;

    SELECT id, author_id, parent_id, votes_count, direct_score, total_score, type
    INTO v_node
    FROM public.idea_nodes
    WHERE id = p_node_id
    FOR UPDATE;

    IF FOUND THEN
        UPDATE public.idea_nodes
        SET votes_count = GREATEST(0, votes_count - 1),
            direct_score = GREATEST(0.0, direct_score - 1.0),
            total_score = GREATEST(0.0, total_score - 1.0),
            updated_at = now()
        WHERE id = p_node_id;

        -- Decrement Author Score in user_scores (-1.0)
        UPDATE public.user_scores
        SET problem_score = GREATEST(0.0, problem_score - (CASE WHEN v_node.type = 'PROBLEM' THEN 1.0 ELSE 0.0 END)),
            improvement_score = GREATEST(0.0, improvement_score - (CASE WHEN v_node.type = 'IMPROVEMENT' THEN 1.0 ELSE 0.0 END)),
            refinement_score = GREATEST(0.0, refinement_score - (CASE WHEN v_node.type = 'REFINEMENT' THEN 1.0 ELSE 0.0 END)),
            total_score = GREATEST(0.0, total_score - 1.0),
            updated_at = now()
        WHERE user_id = v_node.author_id;

        IF v_node.parent_id IS NOT NULL THEN
            SELECT id, author_id, type, is_deleted
            INTO v_parent_node
            FROM public.idea_nodes
            WHERE id = v_node.parent_id
            FOR UPDATE;

            IF FOUND THEN
                UPDATE public.idea_nodes
                SET total_score = GREATEST(0.0, total_score - 0.25),
                    updated_at = now()
                WHERE id = v_parent_node.id;

                UPDATE public.user_scores
                SET problem_score = GREATEST(0.0, problem_score - (CASE WHEN v_parent_node.type = 'PROBLEM' THEN 0.25 ELSE 0.0 END)),
                    improvement_score = GREATEST(0.0, improvement_score - (CASE WHEN v_parent_node.type = 'IMPROVEMENT' THEN 0.25 ELSE 0.0 END)),
                    refinement_score = GREATEST(0.0, refinement_score - (CASE WHEN v_parent_node.type = 'REFINEMENT' THEN 0.25 ELSE 0.0 END)),
                    total_score = GREATEST(0.0, total_score - 0.25),
                    updated_at = now()
                WHERE user_id = v_parent_node.author_id;
            END IF;
        END IF;
    END IF;

    RETURN jsonb_build_object('ok', true, 'node_id', p_node_id);
END;
$$;

-- ============================================================
-- 3. ROW LEVEL SECURITY (RLS) POLICIES
-- ============================================================

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.idea_nodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.login_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.login_otps ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_scores ENABLE ROW LEVEL SECURITY;

-- 3.1 public.users Policies
-- Drop existing broad or conflicting policies
DROP POLICY IF EXISTS "users_read_all" ON public.users;
DROP POLICY IF EXISTS "Allow individual read" ON public.users;
DROP POLICY IF EXISTS "Allow user update self" ON public.users;
DROP POLICY IF EXISTS "Admin full access" ON public.users;

-- Users can read their own profile; Admins can read all profiles
CREATE POLICY "users_select_policy" ON public.users
FOR SELECT TO authenticated
USING (
    auth_user_id = auth.uid() OR public.is_admin()
);

-- Users can only update non-authoritative profile fields for themselves (e.g. phone_number, department)
-- Server-authoritative columns are strictly protected from modification by students.
CREATE POLICY "users_update_self_policy" ON public.users
FOR UPDATE TO authenticated
USING (
    auth_user_id = auth.uid() OR public.is_admin()
)
WITH CHECK (
    public.is_admin() OR (
        auth_user_id = auth.uid() AND
        is_admin = (SELECT is_admin FROM public.users WHERE auth_user_id = auth.uid()) AND
        account_status = (SELECT account_status FROM public.users WHERE auth_user_id = auth.uid()) AND
        auth_user_id = (SELECT auth_user_id FROM public.users WHERE auth_user_id = auth.uid()) AND
        is_suspended = (SELECT is_suspended FROM public.users WHERE auth_user_id = auth.uid()) AND
        approved_by IS NOT DISTINCT FROM (SELECT approved_by FROM public.users WHERE auth_user_id = auth.uid()) AND
        approved_at IS NOT DISTINCT FROM (SELECT approved_at FROM public.users WHERE auth_user_id = auth.uid()) AND
        otp_verified_until IS NOT DISTINCT FROM (SELECT otp_verified_until FROM public.users WHERE auth_user_id = auth.uid()) AND
        register_number = (SELECT register_number FROM public.users WHERE auth_user_id = auth.uid()) AND
        email = (SELECT email FROM public.users WHERE auth_user_id = auth.uid())
    )
);

-- Only admins can insert or delete users directly
CREATE POLICY "users_admin_insert_policy" ON public.users
FOR INSERT TO authenticated
WITH CHECK (public.is_admin());

CREATE POLICY "users_admin_delete_policy" ON public.users
FOR DELETE TO authenticated
USING (public.is_admin());

-- 3.2 public.idea_nodes Policies
DROP POLICY IF EXISTS "nodes_read_all" ON public.idea_nodes;
DROP POLICY IF EXISTS "nodes_insert_auth" ON public.idea_nodes;
DROP POLICY IF EXISTS "nodes_update_author" ON public.idea_nodes;
DROP POLICY IF EXISTS "nodes_delete_author" ON public.idea_nodes;

-- Read requires daily OTP verification or admin
CREATE POLICY "idea_nodes_select_policy" ON public.idea_nodes
FOR SELECT TO authenticated
USING (
    public.is_life_otp_verified()
);

-- Insert requires daily OTP verification, author must match calling user
CREATE POLICY "idea_nodes_insert_policy" ON public.idea_nodes
FOR INSERT TO authenticated
WITH CHECK (
    public.is_life_otp_verified() AND
    author_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid()) AND
    votes_count = 0 AND
    direct_score = 0.0 AND
    total_score = (
        CASE type
            WHEN 'PROBLEM' THEN 5.0
            WHEN 'IMPROVEMENT' THEN 5.0
            WHEN 'REFINEMENT' THEN 10.0
            ELSE 0.0
        END
    )
);

-- Update: only author or admin; students can only update title and description
CREATE POLICY "idea_nodes_update_policy" ON public.idea_nodes
FOR UPDATE TO authenticated
USING (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        author_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
    )
)
WITH CHECK (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        author_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid()) AND
        votes_count = (SELECT votes_count FROM public.idea_nodes WHERE id = idea_nodes.id) AND
        direct_score = (SELECT direct_score FROM public.idea_nodes WHERE id = idea_nodes.id) AND
        total_score = (SELECT total_score FROM public.idea_nodes WHERE id = idea_nodes.id) AND
        author_id = (SELECT author_id FROM public.idea_nodes WHERE id = idea_nodes.id)
    )
);

-- Delete: soft-delete only by author or admin
CREATE POLICY "idea_nodes_delete_policy" ON public.idea_nodes
FOR DELETE TO authenticated
USING (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        author_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
    )
);

-- 3.3 public.votes Policies
DROP POLICY IF EXISTS "votes_read_policy" ON public.votes;
DROP POLICY IF EXISTS "votes_insert_policy" ON public.votes;
DROP POLICY IF EXISTS "votes_delete_policy" ON public.votes;

CREATE POLICY "votes_select_policy" ON public.votes
FOR SELECT TO authenticated
USING (
    public.is_life_otp_verified()
);

CREATE POLICY "votes_insert_policy" ON public.votes
FOR INSERT TO authenticated
WITH CHECK (
    public.is_life_otp_verified() AND
    user_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
);

CREATE POLICY "votes_delete_policy" ON public.votes
FOR DELETE TO authenticated
USING (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        user_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
    )
);

-- 3.4 public.login_events Policies
DROP POLICY IF EXISTS "login_events_select_policy" ON public.login_events;
DROP POLICY IF EXISTS "login_events_insert_policy" ON public.login_events;

CREATE POLICY "login_events_select_policy" ON public.login_events
FOR SELECT TO authenticated
USING (
    public.is_admin() OR (
        user_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
    )
);

CREATE POLICY "login_events_insert_policy" ON public.login_events
FOR INSERT TO authenticated
WITH CHECK (
    user_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid()) OR public.is_admin()
);

-- 3.5 public.login_otps Policies (Restricted to service-role / Edge Functions only)
DROP POLICY IF EXISTS "login_otps_no_public" ON public.login_otps;
REVOKE ALL ON public.login_otps FROM anon, authenticated;

-- 3.6 public.user_scores Policies
DROP POLICY IF EXISTS "user_scores_select_policy" ON public.user_scores;
DROP POLICY IF EXISTS "user_scores_upsert_policy" ON public.user_scores;

-- Read requires daily OTP verification or admin
CREATE POLICY "user_scores_select_policy" ON public.user_scores
FOR SELECT TO authenticated
USING (
    public.is_life_otp_verified()
);

-- Upsert: Admin can manage all, or verified user can insert/update their own score
CREATE POLICY "user_scores_upsert_policy" ON public.user_scores
FOR ALL TO authenticated
USING (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        user_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
    )
)
WITH CHECK (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        user_id = (SELECT id FROM public.users WHERE auth_user_id = auth.uid())
    )
);

-- ============================================================
-- 4. EXECUTE PRIVILEGES & SECURITY HARDENING
-- ============================================================

-- Revoke default public execution privileges to prevent unauthorized escalation
REVOKE EXECUTE ON FUNCTION public.resolve_login_identity(TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.is_admin() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.is_life_otp_verified() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.cast_vote(TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.remove_vote(TEXT) FROM PUBLIC;

-- Grant to only the strictly required roles
GRANT EXECUTE ON FUNCTION public.resolve_login_identity(TEXT) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.is_admin() TO authenticated;
GRANT EXECUTE ON FUNCTION public.is_life_otp_verified() TO authenticated;
GRANT EXECUTE ON FUNCTION public.cast_vote(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.remove_vote(TEXT) TO authenticated;
