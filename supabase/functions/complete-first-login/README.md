# complete-first-login

Purpose: Verify a Firebase ID token (server-side) and mark the Supabase `users.is_first_login` flag false for the matching `auth_user_id`.

Environment variables required (set in Supabase Functions settings):
- `FIREBASE_PROJECT_ID`
- `FIREBASE_CLIENT_EMAIL`
- `FIREBASE_PRIVATE_KEY` (use literal newlines escaped as `\n` in the env value)
- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`

Local testing (Deno):
1. Install Deno (>=1.40 recommended).
2. Export required env vars locally, e.g. on Windows PowerShell:

```powershell
$env:FIREBASE_PROJECT_ID="your-project-id"
$env:FIREBASE_CLIENT_EMAIL="your-client-email"
$env:FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n"
$env:SUPABASE_URL="https://your.supabase.url"
$env:SUPABASE_SERVICE_ROLE_KEY="service_role_key"
```

3. Run the function locally:

```powershell
denon run --allow-env --allow-net index.ts
```

Deployment (Supabase CLI):

```powershell
supabase functions deploy complete-first-login --project-ref <ref>
```

Security notes:
- The function strictly verifies Firebase ID tokens and uses the Supabase service role key to modify the `users` table.
- Do not accept client-provided user IDs or passwords; the function only trusts the Firebase token's `uid`.

If you want, I can also add a small Deno test harness to exercise token verification (requires a test Firebase token).