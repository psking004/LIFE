CREATE OR REPLACE FUNCTION public.resolve_login_identity(p_register_number text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user record;
  v_email text;
  v_masked text;
  v_at_pos int;
  v_local text;
  v_domain text;
BEGIN
  SELECT id, auth_user_id, register_number, email, account_status, is_suspended, is_admin
  INTO v_user
  FROM public.users
  WHERE upper(trim(register_number)) = upper(trim(p_register_number))
  LIMIT 1;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'Account Not Found: We couldn''t find a LIFE account with this register number.');
  END IF;

  IF v_user.account_status = 'PENDING' THEN
    RETURN jsonb_build_object('ok', false, 'status', 'PENDING', 'error', 'Your account is awaiting administrator approval.');
  ELSIF v_user.account_status = 'SUSPENDED' OR v_user.is_suspended THEN
    RETURN jsonb_build_object('ok', false, 'status', 'SUSPENDED', 'error', 'Your account has been suspended. Contact the administrator.');
  ELSIF v_user.account_status = 'REJECTED' THEN
    RETURN jsonb_build_object('ok', false, 'status', 'REJECTED', 'error', 'Your account is not authorized.');
  END IF;

  v_email := trim(coalesce(v_user.email, ''));
  v_at_pos := position('@' in v_email);
  IF v_at_pos > 1 THEN
    v_local := substr(v_email, 1, v_at_pos - 1);
    v_domain := substr(v_email, v_at_pos);
    IF length(v_local) <= 2 THEN
      v_masked := substr(v_local, 1, 1) || '***' || v_domain;
    ELSE
      v_masked := substr(v_local, 1, 1) || '***' || substr(v_local, length(v_local), 1) || v_domain;
    END IF;
  ELSE
    v_masked := 'your registered email';
  END IF;

  RETURN jsonb_build_object(
    'ok', true,
    'user_id', v_user.id,
    'register_number', v_user.register_number,
    'auth_email', v_email,
    'masked_email', v_masked,
    'status', v_user.account_status,
    'is_admin', v_user.is_admin
  );
END;
$$;

GRANT EXECUTE ON FUNCTION public.resolve_login_identity(text) TO anon, authenticated, service_role;
