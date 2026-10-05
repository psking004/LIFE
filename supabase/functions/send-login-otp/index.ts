import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import { cert, getApps, initializeApp } from "npm:firebase-admin/app";
import { getAuth } from "npm:firebase-admin/auth";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function maskEmail(email: string): string {
  const trimmed = (email || "").trim();
  if (!trimmed || !trimmed.includes("@")) return "your registered email";
  const atIndex = trimmed.indexOf("@");
  const local = trimmed.slice(0, atIndex);
  const domain = trimmed.slice(atIndex);
  if (local.length <= 2) return `${local[0] || ""}***${domain}`;
  return `${local[0]}***${local[local.length - 1]}${domain}`;
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

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const supabaseServiceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const brevoApiKey = Deno.env.get("BREVO_API_KEY") ?? "";
    const otpHashSecret = Deno.env.get("OTP_HASH_SECRET") ?? "life_otp_salt_secret";

    if (!supabaseUrl || !supabaseServiceKey) {
      return new Response(JSON.stringify({ ok: false, error: "Missing Supabase configuration" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);
    let { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, register_number, email, name, account_status, is_suspended, is_admin, otp_verified_until")
      .eq("auth_user_id", firebaseUid)
      .maybeSingle();

    if (!userRecord && decodedToken.email) {
      const emailStr = decodedToken.email.toLowerCase();
      const { data: emailUser } = await supabaseAdmin
        .from("users")
        .select("id, register_number, email, name, account_status, is_suspended, is_admin, otp_verified_until, auth_user_id")
        .ilike("email", emailStr)
        .maybeSingle();
        
      if (emailUser) {
        await supabaseAdmin.from("users").update({ auth_user_id: firebaseUid }).eq("id", emailUser.id);
        userRecord = emailUser;
      }
    }

    if (!userRecord) {
      console.error("send-login-otp: user not found for firebaseUid:", firebaseUid);
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

    const emailStr = (userRecord.email || "").trim().toLowerCase();
    if (!emailStr) {
      return new Response(JSON.stringify({ ok: false, error: "No registered email found for this account" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const maskedEmail = maskEmail(emailStr);

    if (userRecord.otp_verified_until) {
      const verifiedUntil = new Date(userRecord.otp_verified_until).getTime();
      if (verifiedUntil > Date.now()) {
        return new Response(JSON.stringify({
          ok: true,
          alreadyVerifiedToday: true,
          expiresInSeconds: 0,
          emailMasked: maskedEmail,
        }), {
          status: 200,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    const oneMinAgo = new Date(Date.now() - 60 * 1000).toISOString();
    const { data: recentOtps, error: checkError } = await supabaseAdmin
      .from("login_otps")
      .select("id, created_at")
      .eq("user_id", userRecord.id)
      .is("used_at", null)
      .gt("created_at", oneMinAgo)
      .limit(1);

    if (recentOtps && recentOtps.length > 0) {
      return new Response(JSON.stringify({
        ok: false,
        error: "Please wait 60 seconds before requesting a new verification code.",
      }), {
        status: 429,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Invalidate previous unused OTPs
    await supabaseAdmin
      .from("login_otps")
      .update({ used_at: new Date().toISOString() })
      .eq("user_id", userRecord.id)
      .is("used_at", null);

    const otpNumber = crypto.getRandomValues(new Uint32Array(1))[0] % 900000 + 100000;
    const otpStr = String(otpNumber).padStart(6, "0");

    const encoder = new TextEncoder();
    const hashBuffer = await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(`${otpStr}:${otpHashSecret}:${userRecord.id}`)
    );
    const otpHash = Array.from(new Uint8Array(hashBuffer))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");

    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    const { error: insertOtpError } = await supabaseAdmin.from("login_otps").insert({
      user_id: userRecord.id,
      otp_hash: otpHash,
      purpose: "LOGIN",
      attempts: 0,
      expires_at: expiresAt,
      created_at: new Date().toISOString(),
    });

    if (insertOtpError) {
      console.error("send-login-otp: insert login_otps error:", insertOtpError);
      return new Response(JSON.stringify({ ok: false, error: "Failed to initiate verification" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (brevoApiKey) {
      try {
        const brevoResp = await fetch("https://api.brevo.com/v3/smtp/email", {
          method: "POST",
          headers: {
            "api-key": brevoApiKey,
            "Content-Type": "application/json",
            "Accept": "application/json",
          },
          body: JSON.stringify({
            sender: {
              name: Deno.env.get("BREVO_SENDER_NAME") ?? "LIFE Platform",
              email: Deno.env.get("BREVO_SENDER_EMAIL") ?? "noreply@campus.edu",
            },
            to: [{ email: emailStr, name: userRecord.name || userRecord.register_number }],
            subject: "Your LIFE Login Verification Code",
            htmlContent: `
              <div style="font-family: Arial, sans-serif; max-width: 600px; margin: auto; padding: 24px; border: 1px solid #e0e0e0; border-radius: 12px;">
                <h2 style="color: #2E7D32; margin-top: 0;">LIFE Platform Verification</h2>
                <p>Hello <strong>${userRecord.name || userRecord.register_number}</strong>,</p>
                <p>Use the following 6-digit verification code to complete your login to LIFE:</p>
                <div style="background: #f4f6f8; border-radius: 8px; padding: 18px; text-align: center; margin: 24px 0;">
                  <span style="font-size: 32px; font-weight: bold; letter-spacing: 6px; color: #1a1a1a;">${otpStr}</span>
                </div>
                <p style="color: #666; font-size: 13px;">This code will expire in 5 minutes. If you did not attempt to sign in, please contact the campus administrator immediately.</p>
                <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;" />
                <p style="color: #999; font-size: 11px;">LIFE: Living Ideas For Evolution — Campus Innovation Platform</p>
              </div>
            `,
          }),
        });

        if (!brevoResp.ok) {
          const errText = await brevoResp.text().catch(() => "");
          console.error("send-login-otp: Brevo delivery failed:", brevoResp.status, errText);
          throw new Error("Brevo delivery failed");
        }
      } catch (brevoErr: any) {
        console.error("send-login-otp: Brevo email sending error:", brevoErr?.message ?? brevoErr);
        return new Response(JSON.stringify({ ok: false, error: "Failed to send verification email" }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    return new Response(JSON.stringify({
      ok: true,
      alreadyVerifiedToday: false,
      expiresInSeconds: 300,
      emailMasked: maskedEmail,
    }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    console.error("send-login-otp unhandled error:", err?.message ?? err);
    return new Response(JSON.stringify({ ok: false, error: err?.message ?? "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
