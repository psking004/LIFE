-- 1. Drop existing RLS Policies
DROP POLICY IF EXISTS "users_select_own_or_admin" ON public.users;
DROP POLICY IF EXISTS "users_update_admin_only" ON public.users;
DROP POLICY IF EXISTS "users_insert_admin_only" ON public.users;
DROP POLICY IF EXISTS "users_delete_admin_only" ON public.users;

DROP POLICY IF EXISTS "idea_nodes_select_otp_or_admin" ON public.idea_nodes;
DROP POLICY IF EXISTS "idea_nodes_insert_otp_or_admin" ON public.idea_nodes;
DROP POLICY IF EXISTS "idea_nodes_update_otp_or_admin" ON public.idea_nodes;
DROP POLICY IF EXISTS "idea_nodes_delete_otp_or_admin" ON public.idea_nodes;

DROP POLICY IF EXISTS "votes_select_otp_or_admin" ON public.votes;
DROP POLICY IF EXISTS "votes_insert_otp_or_admin" ON public.votes;
DROP POLICY IF EXISTS "votes_delete_otp_or_admin" ON public.votes;

DROP POLICY IF EXISTS "login_events_select_admin" ON public.login_events;
DROP POLICY IF EXISTS "login_events_insert_otp_or_admin" ON public.login_events;

-- 2. Drop existing Functions
DROP FUNCTION IF EXISTS public.is_admin();
DROP FUNCTION IF EXISTS public.is_life_otp_verified();

-- 3. Create public.firebase_uid() identity extractor
CREATE OR REPLACE FUNCTION public.firebase_uid()
RETURNS TEXT
LANGUAGE sql
STABLE
AS $$
  SELECT nullif(current_setting('request.jwt.claims', true)::jsonb ->> 'sub', '');
$$;

-- 4. Alter the auth_user_id column to remove Supabase Auth constraint and change to TEXT
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_auth_user_id_fkey;
ALTER TABLE public.users ALTER COLUMN auth_user_id TYPE TEXT;

-- 5. Recreate Functions (replacing auth.uid() with public.firebase_uid())

CREATE OR REPLACE FUNCTION public.is_admin()
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
    select exists (
        select 1
        from public.users
        where auth_user_id = public.firebase_uid()
          and is_admin = true
          and account_status = 'ACTIVE'
    );
$function$;

CREATE OR REPLACE FUNCTION public.is_life_otp_verified()
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
    select exists (
            select 1
                    from public.users
                            where auth_user_id = public.firebase_uid()
                                      and account_status = 'ACTIVE'
                                                and otp_verified_until is not null
                                                          and otp_verified_until > now()
                                                              );
$function$;

-- 6. Recreate RLS Policies (replacing auth.uid() with public.firebase_uid())

-- public.users
CREATE POLICY "users_select_own_or_admin" ON public.users
FOR SELECT USING (
    (auth_user_id = public.firebase_uid()) OR public.is_admin()
);

CREATE POLICY "users_update_admin_only" ON public.users
FOR UPDATE USING (public.is_admin()) WITH CHECK (public.is_admin());

CREATE POLICY "users_insert_admin_only" ON public.users
FOR INSERT WITH CHECK (public.is_admin());

CREATE POLICY "users_delete_admin_only" ON public.users
FOR DELETE USING (public.is_admin());

-- public.idea_nodes
CREATE POLICY "idea_nodes_select_otp_or_admin" ON public.idea_nodes
FOR SELECT USING (
    public.is_admin() OR (public.is_life_otp_verified() AND (is_deleted = false OR author_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid())))
);

CREATE POLICY "idea_nodes_insert_otp_or_admin" ON public.idea_nodes
FOR INSERT WITH CHECK (
    public.is_admin() OR (public.is_life_otp_verified() AND author_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
);

CREATE POLICY "idea_nodes_update_otp_or_admin" ON public.idea_nodes
FOR UPDATE USING (
    public.is_admin() OR (public.is_life_otp_verified() AND author_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
) WITH CHECK (
    public.is_admin() OR (public.is_life_otp_verified() AND author_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
);

CREATE POLICY "idea_nodes_delete_otp_or_admin" ON public.idea_nodes
FOR DELETE USING (
    public.is_admin() OR (public.is_life_otp_verified() AND author_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
);

-- public.votes
CREATE POLICY "votes_select_otp_or_admin" ON public.votes
FOR SELECT USING (
    public.is_admin() OR public.is_life_otp_verified()
);

CREATE POLICY "votes_insert_otp_or_admin" ON public.votes
FOR INSERT WITH CHECK (
    public.is_admin() OR (public.is_life_otp_verified() AND user_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
);

CREATE POLICY "votes_delete_otp_or_admin" ON public.votes
FOR DELETE USING (
    public.is_admin() OR (public.is_life_otp_verified() AND user_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
);

-- public.login_events
CREATE POLICY "login_events_select_admin" ON public.login_events
FOR SELECT USING (public.is_admin());

CREATE POLICY "login_events_insert_otp_or_admin" ON public.login_events
FOR INSERT WITH CHECK (
    public.is_admin() OR (public.is_life_otp_verified() AND user_id = (SELECT id FROM public.users WHERE auth_user_id = public.firebase_uid()))
);
