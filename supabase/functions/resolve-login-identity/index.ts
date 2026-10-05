import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";

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

    if (!supabaseUrl || !supabaseServiceKey) {
      return new Response(JSON.stringify({ ok: false, error: "Missing Supabase configuration" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabaseAdmin = createClient(supabaseUrl, supabaseServiceKey);
    
    const { data: userRecord, error: userError } = await supabaseAdmin
      .from("users")
      .select("id, auth_user_id, register_number, email, account_status, is_suspended, is_admin")
      .ilike("register_number", cleanReg)
      .maybeSingle();

    if (userError || !userRecord) {
      return new Response(JSON.stringify({ ok: false, error: "Account Not Found: We couldn't find a LIFE account with this register number." }), {
        status: 404,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (userRecord.account_status === "PENDING") {
      return new Response(JSON.stringify({ ok: false, status: "PENDING", error: "Your account is awaiting administrator approval." }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (userRecord.account_status === "SUSPENDED" || userRecord.is_suspended) {
      return new Response(JSON.stringify({ ok: false, status: "SUSPENDED", error: "Your account has been suspended. Contact the administrator." }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (userRecord.account_status === "REJECTED") {
      return new Response(JSON.stringify({ ok: false, status: "REJECTED", error: "Your account is not authorized." }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const email = (userRecord.email || "").trim();
    if (!email) {
      return new Response(JSON.stringify({ ok: false, error: "Identity resolution failed: no registered email found for this account." }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    return new Response(JSON.stringify({
      ok: true,
      user_id: userRecord.id,
      register_number: userRecord.register_number,
      auth_email: email,
      masked_email: maskEmail(email),
      status: userRecord.account_status,
      is_admin: userRecord.is_admin
    }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err: any) {
    console.error("resolve-login-identity error:", err?.message ?? err);
    return new Response(JSON.stringify({ ok: false, error: err?.message ?? "Internal server error" }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});
