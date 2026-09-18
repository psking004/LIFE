import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { cert, getApps, initializeApp } from "npm:firebase-admin/app";
import { getAuth } from "npm:firebase-admin/auth";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function calculateNextMidnightAsiaKolkata(): string {
  const now = new Date();
  const utcMillis = now.getTime();
  const kolkataOffsetMillis = (5 * 60 + 30) * 60 * 1000;
  const kolkataDate = new Date(utcMillis + kolkataOffsetMillis);
  kolkataDate.setUTCDate(kolkataDate.getUTCDate() + 1);
  kolkataDate.setUTCHours(0, 0, 0, 0);
  const nextMidnightUtc = new Date(kolkataDate.getTime() - kolkataOffsetMillis);
  return nextMidnightUtc.toISOString();
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let result = 0;
  for (let i = 0; i < a.length; i++) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return result === 0;
}

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

    const body = await req.json().catch(() => ({}));
    const submittedOtp = (body.otp ?? "").toString().trim();

    if (!submittedOtp || submittedOtp.length !== 6 || !/^\d{6}$/.test(submittedOtp)) {
      return new Response(JSON.stringify({ ok: false, error: "Please enter a valid 6-digit code" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const otpHashSecret = Deno.env.get("OTP_HASH_SECRET") ?? "life_otp_salt_secret";

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

    const { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, register_number, account_status, is_suspended")
      .eq("auth_user_id", firebaseUid)
      .maybeSingle();

    if (userError || !userRecord) {
      return new Response(JSON.stringify({ ok: false, error: "Student profile not found" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (userRecord.account_status !== "ACTIVE" || userRecord.is_suspended) {
      return new Response(JSON.stringify({ ok: false, error: "Account is not active or has been suspended" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const nowIso = new Date().toISOString();
    const { data: otps, error: otpFetchError } = await supabaseAdmin
      .from("login_otps")
      .select("id, otp_hash, attempts, max_attempts, expires_at, is_used")
      .eq("user_id", userRecord.id)
      .eq("is_used", false)
      .gt("expires_at", nowIso)
      .order("created_at", { ascending: false })
      .limit(1);

    if (otpFetchError || !otps || otps.length === 0) {
      return new Response(JSON.stringify({ ok: false, error: "Verification code expired or not found. Please request a new code." }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const activeOtp = otps[0];

    if (activeOtp.attempts >= activeOtp.max_attempts) {
      await supabaseAdmin
        .from("login_otps")
        .update({ is_used: true })
        .eq("id", activeOtp.id);

      return new Response(JSON.stringify({
        ok: false,
        error: "Too many failed attempts. Please request a new verification code.",
      }), {
        status: 429,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const encoder = new TextEncoder();
    const hashBuffer = await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(`${submittedOtp}:${otpHashSecret}:${userRecord.id}`)
    );
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const submittedHash = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");

    const isMatch = timingSafeEqual(submittedHash, activeOtp.otp_hash);

    if (!isMatch) {
      const nextAttempts = activeOtp.attempts + 1;
      const willInvalidate = nextAttempts >= activeOtp.max_attempts;

      await supabaseAdmin
        .from("login_otps")
        .update({
          attempts: nextAttempts,
          is_used: willInvalidate,
        })
        .eq("id", activeOtp.id);

      const remaining = activeOtp.max_attempts - nextAttempts;
      const errorMsg = remaining > 0
        ? `Incorrect code. ${remaining} attempt(s) remaining.`
        : "Too many failed attempts. Please request a new code.";

      return new Response(JSON.stringify({ ok: false, error: errorMsg }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    await supabaseAdmin
      .from("login_otps")
      .update({ is_used: true })
      .eq("id", activeOtp.id);

    const nextMidnightKolkata = calculateNextMidnightAsiaKolkata();

    await supabaseAdmin
      .from("users")
      .update({
        otp_verified_until: nextMidnightKolkata,
        auth_user_id: firebaseUid,
      })
      .eq("id", userRecord.id);

    return new Response(JSON.stringify({
      ok: true,
      verifiedUntil: nextMidnightKolkata,
    }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    const message = err instanceof Error && err.message === "Missing Firebase admin configuration"
      ? "Firebase server configuration is missing"
      : "Internal server error";

    return new Response(JSON.stringify({ ok: false, error: message }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
