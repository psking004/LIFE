import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function calculateNextMidnightAsiaKolkata(): string {
  // Asia/Kolkata is UTC+5:30 (330 minutes)
  const now = new Date();
  const utcMillis = now.getTime();
  const kolkataOffsetMillis = (5 * 60 + 30) * 60 * 1000;
  const kolkataDate = new Date(utcMillis + kolkataOffsetMillis);

  // Set to next day 00:00:00 in Kolkata time
  kolkataDate.setUTCDate(kolkataDate.getUTCDate() + 1);
  kolkataDate.setUTCHours(0, 0, 0, 0);

  // Convert back to UTC ISO
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
    const body = await req.json().catch(() => ({}));
    const submittedOtp = (body.otp ?? "").toString().trim();

    if (!submittedOtp || submittedOtp.length !== 6 || !/^\d{6}$/.test(submittedOtp)) {
      return new Response(JSON.stringify({ ok: false, error: "Please enter a valid 6-digit code" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const otpHashSecret = Deno.env.get("OTP_HASH_SECRET") ?? "life_otp_salt_secret";

    // 1. Authenticate user from JWT
    const supabaseUserClient = createClient(supabaseUrl, supabaseAnonKey, {
      global: { headers: { Authorization: `Bearer ${token}` } },
    });

    const { data: { user: authUser }, error: userAuthError } = await supabaseUserClient.auth.getUser();
    if (userAuthError || !authUser) {
      return new Response(JSON.stringify({ ok: false, error: "Invalid or expired session" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 2. Authoritative Database Client
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);

    // 3. Look up user record
    const { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, register_number, account_status, is_suspended")
      .or(`auth_user_id.eq.${authUser.id},email.eq.${authUser.email}`)
      .single();

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

    // 4. Fetch the newest active OTP for this user
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

    // Check attempt limit
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

    // Hash submitted OTP with the same salt & user id
    const encoder = new TextEncoder();
    const hashBuffer = await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(`${submittedOtp}:${otpHashSecret}:${userRecord.id}`)
    );
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const submittedHash = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");

    // Timing-safe comparison
    const isMatch = timingSafeEqual(submittedHash, activeOtp.otp_hash);

    if (!isMatch) {
      // Increment failed attempts
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

    // OTP Matched: Mark used and set otp_verified_until to next midnight Asia/Kolkata
    await supabaseAdmin
      .from("login_otps")
      .update({ is_used: true })
      .eq("id", activeOtp.id);

    const nextMidnightKolkata = calculateNextMidnightAsiaKolkata();

    await supabaseAdmin
      .from("users")
      .update({
        otp_verified_until: nextMidnightKolkata,
        auth_user_id: authUser.id,
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
    return new Response(JSON.stringify({ ok: false, error: "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
