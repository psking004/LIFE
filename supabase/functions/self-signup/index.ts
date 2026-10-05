import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { cert, getApps, initializeApp } from "npm:firebase-admin/app";
import { getAuth } from "npm:firebase-admin/auth";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type",
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
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ ok: false, error: "Method not allowed" }), { status: 405, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }

  try {
    const body = await req.json().catch(() => ({}));
    const registerNumber = (body.register_number ?? "").toString().trim().toUpperCase();
    const initialPassword = (body.initial_password ?? "").toString().trim();
    const email = (body.email ?? "").toString().trim().toLowerCase();
    const phone = (body.phone_number ?? "").toString().trim();
    const name = (body.name ?? "").toString().trim();
    const department = (body.department ?? "Computer Science & Engineering").toString().trim();

    if (!registerNumber || !initialPassword || initialPassword.length < 6 || !email) {
      return new Response(JSON.stringify({ ok: false, error: "Invalid input" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (!/\S+@\S+\.\S+/.test(email)) {
      return new Response(JSON.stringify({ ok: false, error: "Invalid email" }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    ensureFirebaseAdmin();
    const firebaseAuth = getAuth(getApps()[0]);

    // Create Firebase user
    let created: any = null;
    try {
      created = await firebaseAuth.createUser({
        email,
        password: initialPassword,
        emailVerified: false,
        displayName: name || registerNumber,
      });
    } catch (e: any) {
      return new Response(JSON.stringify({ 
        ok: false, 
        error: "Firebase create user failed", 
        code: e.code, 
        message: e.message 
      }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    try {
      await firebaseAuth.setCustomUserClaims(created.uid, { role: "authenticated" });
    } catch (e: any) {
      console.error("Failed to set custom claim on new user:", e.message);
    }

    // Insert into Supabase users table
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

    if (!supabaseUrl || !supabaseServiceKey) {
      // Rollback Firebase user
      try { await firebaseAuth.deleteUser(created.uid); } catch (_) {}
      return new Response(JSON.stringify({ ok: false, error: "Missing Supabase configuration" }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    const supabase = createClient(supabaseUrl, supabaseServiceKey);

    try {
      const insert = await supabase.from("users").insert({
        id: crypto.randomUUID(),
        auth_user_id: created.uid,
        register_number: registerNumber,
        name: name || `Student ${registerNumber}`,
        phone_number: phone,
        department: department,
        email: email,
        email_verified: false,
        account_status: "PENDING",
        is_first_login: true,
        is_suspended: false,
        is_admin: false,
        created_at: new Date().toISOString()
      }).select("id").single();

      if (insert.error || !insert.data?.id) {
        // Rollback Firebase
        try { await firebaseAuth.deleteUser(created.uid); } catch (_) {}
        return new Response(JSON.stringify({ 
          ok: false, 
          error: "Database insert failed", 
          code: insert.error?.code, 
          message: insert.error?.message, 
          details: insert.error?.details, 
          hint: insert.error?.hint 
        }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
      }

      return new Response(JSON.stringify({ 
        ok: true, 
        user_id: insert.data.id, 
        auth_user_id: created.uid, 
        register_number: registerNumber 
      }), { status: 200, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    } catch (e: any) {
      try { await firebaseAuth.deleteUser(created.uid); } catch (_) {}
      return new Response(JSON.stringify({ 
        ok: false, 
        error: "Failed to create user record", 
        details: e.message || e.toString() 
      }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
  } catch (err: any) {
    return new Response(JSON.stringify({ 
      ok: false, 
      error: "Internal server error", 
      details: err.message || err.toString()
    }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
});
