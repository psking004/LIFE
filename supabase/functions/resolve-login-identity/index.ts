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

function maskEmail(email: string): string {
  const trimmed = (email || "").trim();
  if (!trimmed || !trimmed.includes("@")) return "your registered email";
  const atIndex = trimmed.indexOf("@");
  const local = trimmed.slice(0, atIndex);
  const domain = trimmed.slice(atIndex);
  if (local.length <= 2) return `${local[0] || ""}***${domain}`;
  return `${local[0]}***${local[local.length - 1]}${domain}`;
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
    const { data: callerUser, error: callerError } = await supabaseAdmin
      .from("users")
      .select("id, auth_user_id, account_status, is_suspended, is_admin")
      .eq("auth_user_id", firebaseUid)
      .maybeSingle();

    if (callerError || !callerUser || callerUser.account_status !== "ACTIVE" || callerUser.is_suspended) {
      return new Response(JSON.stringify({ ok: false, error: "Unauthorized" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, auth_user_id, register_number, email, account_status, is_suspended, is_admin")
      .ilike("register_number", cleanReg)
      .maybeSingle();

    if (userError || !userRecord || userRecord.account_status !== "ACTIVE" || userRecord.is_suspended) {
      return new Response(JSON.stringify({ ok: false, error: "Invalid Register Number or Password" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (userRecord.id !== callerUser.id && !callerUser.is_admin) {
      return new Response(JSON.stringify({ ok: false, error: "Unauthorized" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const email = (userRecord.email || "").trim();
    return new Response(JSON.stringify({
      ok: true,
      auth_email: email,
      masked_email: maskEmail(email),
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
