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
    const authHeader = req.headers.get("Authorization");
    if (!authHeader || !authHeader.startsWith("Bearer ")) {
      return new Response(JSON.stringify({ ok: false, error: "Missing authorization token" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const token = authHeader.replace("Bearer ", "").trim();
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    // 1. Authenticate caller using client JWT
    const supabaseUserClient = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: `Bearer ${token}` } },
    });

    const { data: { user: callerAuth }, error: callerAuthError } = await supabaseUserClient.auth.getUser();
    if (callerAuthError || !callerAuth) {
      return new Response(JSON.stringify({ ok: false, error: "Invalid or expired session" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 2. Authoritative Database Client
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);

    // 3. Verify caller has ACTIVE admin privileges in public.users
    const { data: adminRecord, error: adminError } = await supabaseAdmin
      .from("users")
      .select("id, register_number, is_admin, account_status, is_suspended")
      .eq("auth_user_id", callerAuth.id)
      .single();

    if (adminError || !adminRecord || !adminRecord.is_admin || adminRecord.account_status !== "ACTIVE" || adminRecord.is_suspended) {
      return new Response(JSON.stringify({ ok: false, error: "Unauthorized: Administrator privileges required" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const body = await req.json().catch(() => ({}));
    const action = body.action; // 'create_student' | 'reset_password' | 'set_status'

    if (action === "create_student") {
      const registerNumber = (body.register_number ?? "").toString().trim().toUpperCase();
      const name = (body.name ?? "").toString().trim();
      const email = (body.email ?? "").toString().trim().toLowerCase();
      const phone = (body.phone_number ?? "").toString().trim();
      const department = (body.department ?? "Computer Science & Engineering").toString().trim();
      const initialPassword = (body.initial_password ?? "").toString().trim();

      if (!registerNumber || !email || !initialPassword || initialPassword.length < 6) {
        return new Response(JSON.stringify({
          ok: false,
          error: "Invalid input: Register Number, Email, and minimum 6-character initial password are required.",
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Check if student already exists in public.users
      const { data: existingUser } = await supabaseAdmin
        .from("users")
        .select("id")
        .or(`register_number.eq.${registerNumber},email.eq.${email}`)
        .maybeSingle();

      if (existingUser) {
        return new Response(JSON.stringify({
          ok: false,
          error: "A student with this Register Number or Email already exists.",
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Securely create Supabase Auth User via Admin API
      const { data: authCreated, error: createAuthError } = await supabaseAdmin.auth.admin.createUser({
        email: email,
        password: initialPassword,
        email_confirm: true,
        user_metadata: {
          register_number: registerNumber,
          name: name,
        },
      });

      if (createAuthError || !authCreated?.user) {
        return new Response(JSON.stringify({
          ok: false,
          error: `Failed to create auth identity: ${createAuthError?.message ?? "unknown error"}`,
        }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const authUserId = authCreated.user.id;
      const studentId = `user_${registerNumber.toLowerCase()}`;

      // Insert into public.users linked to auth_user_id
      const { error: insertUserError } = await supabaseAdmin
        .from("users")
        .insert({
          id: studentId,
          auth_user_id: authUserId,
          register_number: registerNumber,
          name: name || `Student ${registerNumber}`,
          email: email,
          phone_number: phone,
          department: department,
          account_status: "ACTIVE",
          is_first_login: true,
          is_suspended: false,
          is_admin: false,
          approved_at: new Date().toISOString(),
          approved_by: adminRecord.register_number,
        });

      if (insertUserError) {
        // Rollback created auth user
        await supabaseAdmin.auth.admin.deleteUser(authUserId);
        return new Response(JSON.stringify({
          ok: false,
          error: `Failed to save student profile: ${insertUserError.message}`,
        }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(JSON.stringify({
        ok: true,
        user_id: studentId,
        register_number: registerNumber,
      }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "reset_password") {
      const targetUserId = body.target_user_id;
      const newPassword = (body.new_password ?? "").toString().trim();

      if (!targetUserId || !newPassword || newPassword.length < 6) {
        return new Response(JSON.stringify({
          ok: false,
          error: "Invalid input: target user and minimum 6-character password required.",
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { data: targetUser } = await supabaseAdmin
        .from("users")
        .select("auth_user_id")
        .eq("id", targetUserId)
        .single();

      if (!targetUser?.auth_user_id) {
        return new Response(JSON.stringify({ ok: false, error: "Target student auth account not found" }), {
          status: 404,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { error: resetError } = await supabaseAdmin.auth.admin.updateUserById(targetUser.auth_user_id, {
        password: newPassword,
      });

      if (resetError) {
        return new Response(JSON.stringify({ ok: false, error: resetError.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "set_status") {
      const targetUserId = body.target_user_id;
      const newStatus = (body.status ?? "").toString().trim().toUpperCase();

      if (!targetUserId || !["ACTIVE", "PENDING", "SUSPENDED", "REJECTED"].includes(newStatus)) {
        return new Response(JSON.stringify({
          ok: false,
          error: "Invalid input: target_user_id and valid status (ACTIVE, PENDING, SUSPENDED, REJECTED) required.",
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const updates: Record<string, any> = {
        account_status: newStatus,
        is_suspended: newStatus === "SUSPENDED",
      };

      if (newStatus === "ACTIVE") {
        updates.approved_at = new Date().toISOString();
        updates.approved_by = adminRecord.register_number;
      }

      const { error: updateError } = await supabaseAdmin
        .from("users")
        .update(updates)
        .eq("id", targetUserId);

      if (updateError) {
        return new Response(JSON.stringify({ ok: false, error: updateError.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "update_email") {
      const targetUserId = body.target_user_id;
      const newEmail = (body.new_email ?? "").toString().trim().toLowerCase();

      if (!targetUserId || !newEmail || !newEmail.includes("@")) {
        return new Response(JSON.stringify({
          ok: false,
          error: "Invalid input: target_user_id and valid new_email required.",
        }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { data: targetUser } = await supabaseAdmin
        .from("users")
        .select("auth_user_id")
        .eq("id", targetUserId)
        .single();

      if (targetUser?.auth_user_id) {
        await supabaseAdmin.auth.admin.updateUserById(targetUser.auth_user_id, {
          email: newEmail,
          email_confirm: true,
        });
      }

      const { error: updateDbError } = await supabaseAdmin
        .from("users")
        .update({ email: newEmail })
        .eq("id", targetUserId);

      if (updateDbError) {
        return new Response(JSON.stringify({ ok: false, error: updateDbError.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    return new Response(JSON.stringify({ ok: false, error: "Unknown action" }), {
      status: 400,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ ok: false, error: "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
