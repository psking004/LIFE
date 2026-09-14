import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ ok: false, error: "Method not allowed" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  try {
    const body = await req.json().catch(() => ({}));
    const rawReg = (body.register_number ?? body.p_register_number ?? "").toString().trim();
    const cleanReg = rawReg.toUpperCase();

    if (!cleanReg || cleanReg.length < 3) {
      return new Response(JSON.stringify({ ok: false, error: "Please enter a valid Register Number" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    // Server-side authoritative admin client
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);

    // Look up student record
    const { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, auth_user_id, register_number, email, account_status, is_suspended, is_admin, otp_verified_until")
      .ilike("register_number", cleanReg)
      .single();

    // Anti-enumeration: If user does not exist, or account is NOT active (PENDING, SUSPENDED, REJECTED),
    // return identical generic error without revealing existence or status.
    if (userError || !userRecord || userRecord.account_status !== "ACTIVE" || userRecord.is_suspended) {
      return new Response(JSON.stringify({
        ok: false,
        error: "Invalid Register Number or Password",
      }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Retrieve Supabase Auth identity email from auth.users
    let authEmail = userRecord.email;
    if (userRecord.auth_user_id) {
      const { data: authUser } = await supabaseAdmin.auth.admin.getUserById(userRecord.auth_user_id);
      if (authUser?.user?.email) {
        authEmail = authUser.user.email;
      }
    }

    // Expose ONLY the minimum required for the authentication flow (auth_email for GoTrue token grant).
    // NEVER expose user_id, auth_user_id, is_admin, name, department, or account_status to an unauthenticated caller.
    return new Response(JSON.stringify({
      ok: true,
      auth_email: authEmail,
    }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ ok: false, error: "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
