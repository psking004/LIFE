import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { cert, getApps, initializeApp } from "npm:firebase-admin/app";
import { getAuth } from "npm:firebase-admin/auth";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function ensureFirebaseAdmin() {
  if (getApps().length === 0) {
    const projectId = Deno.env.get("FIREBASE_PROJECT_ID") ?? "";
    const clientEmail = Deno.env.get("FIREBASE_CLIENT_EMAIL") ?? "";
    const privateKey = Deno.env.get("FIREBASE_PRIVATE_KEY") ?? "";

    if (!projectId || !clientEmail || !privateKey) {
      throw new Error("Missing Firebase admin configuration");
    }

    initializeApp({
      credential: cert({
        projectId,
        clientEmail,
        privateKey: privateKey.replace(/\\n/g, "\n"),
      }),
    });
  }
}

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
    if (!token) {
      return new Response(JSON.stringify({ ok: false, error: "Missing Firebase ID token" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    if (!supabaseUrl || !supabaseServiceKey) {
      return new Response(JSON.stringify({ ok: false, error: "Missing Supabase configuration" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    ensureFirebaseAdmin();
    const firebaseAuth = getAuth(getApps()[0]);
    const decodedToken = await firebaseAuth.verifyIdToken(token);
    const firebaseUid = decodedToken.uid;
    if (!firebaseUid) {
      return new Response(JSON.stringify({ ok: false, error: "Invalid Firebase token" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);

    const { data: adminRecord, error: adminError } = await supabaseAdmin
      .from("users")
      .select("id, register_number, is_admin, account_status, is_suspended")
      .eq("auth_user_id", firebaseUid)
      .maybeSingle();

    if (adminError || !adminRecord || !adminRecord.is_admin || adminRecord.account_status !== "ACTIVE" || adminRecord.is_suspended) {
      return new Response(JSON.stringify({ ok: false, error: "Unauthorized: Administrator privileges required" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const body = await req.json().catch(() => ({}));
    const action = body.action;

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

      if (!email.includes("@") || !email.includes(".")) {
        return new Response(JSON.stringify({ ok: false, error: "Please provide a valid email address." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const lowerEmail = email.toLowerCase();
      if (lowerEmail.endsWith("@life.edu") || lowerEmail.endsWith("@campus.edu")) {
        return new Response(JSON.stringify({ ok: false, error: "Use the student's real email address. Synthetic campus email addresses are not allowed." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

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

      const authCreated = await firebaseAuth.createUser({
        email,
        password: initialPassword,
        emailVerified: true,
        displayName: name || registerNumber,
      });

      const authUserId = authCreated.uid;

      try {
        await firebaseAuth.setCustomUserClaims(authUserId, { role: "authenticated" });
      } catch (e: any) {
        console.error("Failed to set custom claim on new user:", e.message);
      }

      const { data: insertedUser, error: insertUserError } = await supabaseAdmin
        .from("users")
        .insert({
          id: crypto.randomUUID(),
          auth_user_id: authUserId,
          register_number: registerNumber,
          name: name || `Student ${registerNumber}`,
          email,
          phone_number: phone,
          department,
          account_status: "PENDING",
          is_first_login: true,
          is_suspended: false,
          is_admin: false,
          created_at: Date.now(),
          approved_at: null,
          approved_by: null,
        })
        .select("id")
        .single();

      if (insertUserError || !insertedUser?.id) {
        await firebaseAuth.deleteUser(authUserId);
        return new Response(JSON.stringify({
          ok: false,
          error: `Failed to save student profile: ${insertUserError?.message || "Unknown error"}`,
        }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(JSON.stringify({
        ok: true,
        user_id: insertedUser.id,
        auth_user_id: authUserId,
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

      await firebaseAuth.updateUser(targetUser.auth_user_id, { password: newPassword });

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

      if (targetUserId === adminRecord.id || targetUserId === firebaseUid) {
        return new Response(JSON.stringify({
          ok: false,
          error: "Administrators cannot change their own account status.",
        }), {
          status: 403,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { data: targetUser } = await supabaseAdmin
        .from("users")
        .select("id, auth_user_id, register_number, is_admin")
        .or(`id.eq.${targetUserId},auth_user_id.eq.${targetUserId}`)
        .maybeSingle();

      if (!targetUser) {
        return new Response(JSON.stringify({ ok: false, error: "Target student not found." }), {
          status: 404,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (targetUser.is_admin) {
        return new Response(JSON.stringify({ ok: false, error: "Cannot modify status of another administrator." }), {
          status: 403,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const updates: Record<string, any> = {
        account_status: newStatus,
        is_suspended: newStatus === "SUSPENDED",
      };

      if (newStatus === "ACTIVE") {
        updates.approved_at = Date.now();
        updates.approved_by = adminRecord.id;
      }

      const { error: updateError } = await supabaseAdmin
        .from("users")
        .update(updates)
        .eq("id", targetUser.id);

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
        await firebaseAuth.updateUser(targetUser.auth_user_id, {
          email: newEmail,
          emailVerified: true,
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

    if (action === "delete_student") {
      const targetUserId = body.target_user_id;

      if (!targetUserId) {
        return new Response(JSON.stringify({ ok: false, error: "Target user ID required." }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (targetUserId === adminRecord.id || targetUserId === firebaseUid) {
        return new Response(JSON.stringify({ ok: false, error: "Administrators cannot delete their own accounts." }), {
          status: 403,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { data: targetUser } = await supabaseAdmin
        .from("users")
        .select("id, auth_user_id, is_admin")
        .or(`id.eq.${targetUserId},auth_user_id.eq.${targetUserId}`)
        .maybeSingle();

      if (!targetUser) {
        return new Response(JSON.stringify({ ok: false, error: "Student not found in database." }), {
          status: 404,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (targetUser.is_admin) {
        return new Response(JSON.stringify({ ok: false, error: "Cannot delete another administrator." }), {
          status: 403,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { error: deleteError } = await supabaseAdmin
        .from("users")
        .delete()
        .eq("id", targetUser.id);

      if (deleteError) {
        let msg = deleteError.message;
        if (msg.includes("violates foreign key constraint") && msg.includes("idea_nodes")) {
          msg = "Cannot delete student because they have authored ideas. Their account must be preserved to maintain the evolution tree.";
        }
        return new Response(JSON.stringify({ ok: false, error: msg }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      if (targetUser.auth_user_id) {
        try {
          await firebaseAuth.deleteUser(targetUser.auth_user_id);
        } catch (e: any) {
          console.error("Failed to delete Firebase identity:", e);
        }
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
  } catch (err: any) {
    console.error("admin-manage-user unhandled error:", err?.message ?? err);
    return new Response(JSON.stringify({ ok: false, error: err?.message ?? "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
