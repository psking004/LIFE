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
    const brevoApiKey = Deno.env.get("BREVO_API_KEY") ?? "";
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

    // 2. Authoritative Database Client (Service Role on Server Only)
    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);

    // 3. Check public.users status
    const { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, register_number, email, name, account_status, is_suspended, is_admin, otp_verified_until")
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

    // Mask student email
    const emailStr = userRecord.email || authUser.email || "";
    const atIdx = emailStr.indexOf("@");
    let maskedEmail = "your registered email";
    if (atIdx > 1) {
      const local = emailStr.substring(0, atIdx);
      const domain = emailStr.substring(atIdx);
      maskedEmail = local.length <= 2 
        ? `${local[0]}***${domain}`
        : `${local[0]}***${local[local.length - 1]}${domain}`;
    }

    // 4. Check if already verified today
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

    // 5. Rate limiting: Check if an active OTP was generated in the last 60 seconds
    const oneMinAgo = new Date(Date.now() - 60 * 1000).toISOString();
    const { data: recentOtps } = await supabaseAdmin
      .from("login_otps")
      .select("id, created_at")
      .eq("user_id", userRecord.id)
      .eq("is_used", false)
      .gt("created_at", oneMinAgo);

    if (recentOtps && recentOtps.length > 0) {
      return new Response(JSON.stringify({
        ok: false,
        error: "Please wait 60 seconds before requesting a new verification code.",
      }), {
        status: 429,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Invalidate prior unused OTPs for this user
    await supabaseAdmin
      .from("login_otps")
      .update({ is_used: true })
      .eq("user_id", userRecord.id)
      .eq("is_used", false);

    // 6. Generate cryptographically secure 6-digit OTP
    const array = new Uint32Array(1);
    crypto.getRandomValues(array);
    const otpNumber = (array[0] % 900000) + 100000;
    const otpStr = otpNumber.toString();

    // 7. Hash OTP server-side with salt
    const encoder = new TextEncoder();
    const hashBuffer = await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(`${otpStr}:${otpHashSecret}:${userRecord.id}`)
    );
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    const otpHash = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");

    // 8. Store OTP record with 5 minutes expiry
    const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
    const { error: insertOtpError } = await supabaseAdmin
      .from("login_otps")
      .insert({
        user_id: userRecord.id,
        otp_hash: otpHash,
        attempts: 0,
        max_attempts: 5,
        is_used: false,
        expires_at: expiresAt,
      });

    if (insertOtpError) {
      return new Response(JSON.stringify({ ok: false, error: "Failed to initiate verification" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // 9. Dispatch Email via Brevo API
    if (brevoApiKey && emailStr) {
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
              email: Deno.env.get("BREVO_SENDER_EMAIL") ?? "noreply@campus.edu" 
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
          console.error("Brevo API delivery error", brevoResp.status);
        }
      } catch (brevoErr) {
        console.error("Brevo request failed", brevoErr);
      }
    }

    // 10. Return Response (NEVER returns plaintext OTP)
    return new Response(JSON.stringify({
      ok: true,
      alreadyVerifiedToday: false,
      expiresInSeconds: 300,
      emailMasked: maskedEmail,
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
