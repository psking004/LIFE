-- ============================================================
-- LIFE PLATFORM: SCORING RECONCILIATION MIGRATION
-- ============================================================

-- 1. User Scores table (authoritative scoring engine)
CREATE TABLE IF NOT EXISTS public.user_scores (
    user_id UUID PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
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

-- 2. User Scores RLS Policies
ALTER TABLE public.user_scores ENABLE ROW LEVEL SECURITY;

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
        user_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid())
    )
)
WITH CHECK (
    public.is_admin() OR (
        public.is_life_otp_verified() AND
        user_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid())
    )
);

-- 3. Block direct mutation on Votes by clients
-- We enforce that votes must be created via the SECURITY DEFINER RPCs `cast_vote` and `remove_vote`.
-- Existing SELECT policy remains untouched.
DROP POLICY IF EXISTS "votes_insert_policy" ON public.votes;
DROP POLICY IF EXISTS "votes_delete_policy" ON public.votes;

CREATE POLICY "votes_insert_policy" ON public.votes
FOR INSERT TO authenticated
WITH CHECK (
    public.is_admin()
);

CREATE POLICY "votes_delete_policy" ON public.votes
FOR DELETE TO authenticated
USING (
    public.is_admin()
);


-- 4. Server-Authoritative Atomic Vote Cast Function
CREATE OR REPLACE FUNCTION public.cast_vote(p_node_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_caller_user_id UUID;
    v_node RECORD;
    v_parent_node RECORD;
    v_vote_id UUID;
BEGIN
    -- Authorization check: must have completed OTP today or be admin
    IF NOT public.is_life_otp_verified() THEN
        RAISE EXCEPTION 'Access denied: Active daily OTP verification required';
    END IF;

    -- Find calling student ID from auth.uid()
    SELECT id INTO v_caller_user_id
    FROM public.users
    WHERE auth_user_id = public.firebase_uid() AND account_status = 'ACTIVE' AND is_suspended = false
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
    v_vote_id := gen_random_uuid();
    INSERT INTO public.votes (id, node_id, user_id, created_at)
    VALUES (v_vote_id, p_node_id, v_caller_user_id, EXTRACT(EPOCH FROM now()) * 1000);

    -- Update Target Node Scores (+1.0 direct score to node author)
    UPDATE public.idea_nodes
    SET votes_count = votes_count + 1,
        direct_score = direct_score + 1.0,
        total_score = total_score + 1.0,
        updated_at = EXTRACT(EPOCH FROM now()) * 1000
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
                updated_at = EXTRACT(EPOCH FROM now()) * 1000
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

-- 5. Server-Authoritative Atomic Vote Removal Function
CREATE OR REPLACE FUNCTION public.remove_vote(p_node_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_caller_user_id UUID;
    v_node RECORD;
    v_parent_node RECORD;
BEGIN
    IF NOT public.is_life_otp_verified() THEN
        RAISE EXCEPTION 'Access denied: Active daily OTP verification required';
    END IF;

    SELECT id INTO v_caller_user_id
    FROM public.users
    WHERE auth_user_id = public.firebase_uid() AND account_status = 'ACTIVE' AND is_suspended = false
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
            updated_at = EXTRACT(EPOCH FROM now()) * 1000
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
                    updated_at = EXTRACT(EPOCH FROM now()) * 1000
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

-- 6. Grants
REVOKE EXECUTE ON FUNCTION public.cast_vote(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.remove_vote(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.cast_vote(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.remove_vote(UUID) TO authenticated;
