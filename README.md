# LIFE — *Ideas evolve here.*

LIFE is an Android app where students post problems they see on campus and in daily life, and the community evolves them into solutions together. Every idea is a tree: a **Problem** at the root, **Improvements** that propose fixes, and **Refinements** that sharpen those fixes. Votes and scores surface the best thinking.

> Status: student project, under active development. See [Known limitation](#known-limitation-supabase-must-trust-firebase-tokens) before running against a live backend.

## Features

- **Idea trees** — Problem → Improvement → Refinement nodes, with a tree view and parent-context cards.
- **Feeds** — separate Problems and Improvements feeds, plus search.
- **Voting & scoring** — upvote nodes; a score engine rolls contributions into per-user scores.
- **Leaderboard** — top contributors by score.
- **Profiles** — your nodes, score and account status.
- **Accounts** — register-number / email sign-in via Firebase Auth, email OTP verification at login, first-login password setup, self sign-up with admin approval, suspended-account handling.
- **Admin panel** — admins approve, suspend and manage users (authorization is enforced server-side from `public.users.is_admin`).
- **Offline cache** — Room database mirrors remote data and syncs from Supabase.

## Stack

| Layer | Tech |
|---|---|
| App | Kotlin, Jetpack Compose (Material 3), Navigation, ViewModel, Coroutines |
| Local data | Room, DataStore |
| Networking | Retrofit, OkHttp, Moshi |
| Auth | Firebase Authentication (email/password, custom claims) |
| Backend | Supabase: Postgres + Row Level Security, PostgREST, Edge Functions (Deno) |
| Build | Gradle 9.3.1 (wrapper), Android Gradle Plugin 9.1, Kotlin 2.2, KSP, JDK 17 |
| Tests | JUnit, MockK, Robolectric, Roborazzi screenshot tests |

```
app/src/main/java/com/example/
  data/        local (Room), remote (Supabase REST/auth), repository (Auth, LIFE, session)
  domain/      models + engines (tree, score, validation)
  ui/          Compose screens, components, theme, LifeViewModel
supabase/
  migrations/  schema, RLS, scoring, Firebase identity bridge
  functions/   resolve-login-identity, send/verify-login-otp, self-signup,
               complete-first-login, admin-manage-user
```

## Setup

### Prerequisites
- Android Studio (latest stable) or the command line with **JDK 17** and the Android SDK (compileSdk 36).
- A Firebase project and a Supabase project you control.

### 1. Clone
```bash
git clone https://github.com/psking004/LIFE.git
cd LIFE
```

### 2. Firebase
1. In the [Firebase Console](https://console.firebase.google.com/) create a project (or use yours).
2. **Authentication → Sign-in method →** enable **Email/Password**.
3. **Project settings → Your apps → Add app → Android**, package name `com.aistudio.life.evotree`.
4. Download `google-services.json` and save it as `app/google-services.json`.
   This file is **gitignored** — never commit it. `app/google-services.json.example` shows the expected shape.
5. Restrict the Android API key in Google Cloud Console (**APIs & Services → Credentials**) to your package name + SHA-1 signing fingerprint.

### 3. Supabase
1. Create a project at [supabase.com](https://supabase.com/).
2. Apply the schema: `supabase link --project-ref <your-ref>` then `supabase db push` (runs `supabase/migrations/`).
3. Set Edge Function secrets (Dashboard → **Edge Functions → Secrets**, or `supabase secrets set ...`):
   `SUPABASE_SERVICE_ROLE_KEY`, `OTP_HASH_SECRET` (a long random string), and the Firebase Admin service-account credentials the functions expect. **Never** put the service-role key or Firebase Admin key in the app or in git.
4. Deploy functions: `supabase functions deploy <name>` for each folder in `supabase/functions/`.
5. Complete [Supabase Third-Party Auth → Firebase](#known-limitation-supabase-must-trust-firebase-tokens) — required.

### 4. Local app config
```bash
cp local.properties.example local.properties
```
Edit `local.properties` (gitignored):
```properties
sdk.dir=/path/to/Android/Sdk
SUPABASE_URL=https://<your-project-ref>.supabase.co
SUPABASE_ANON_KEY=<anon / publishable key from Project Settings → API>
```
These become `BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_ANON_KEY` via the Secrets Gradle Plugin. If they are missing, the app builds but fails fast at runtime with a clear "not configured" error instead of silently using someone else's backend.

### 5. Build & run
```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # unit tests
```
Or open the project in Android Studio and press Run.

## Known limitation: Supabase must trust Firebase tokens

The app signs users in with **Firebase Auth** and sends the Firebase **ID token** as `Authorization: Bearer <token>` to Supabase PostgREST. Out of the box Supabase only accepts JWTs it signed itself, so every REST call fails with:

```
HTTP 401  PGRST301: No suitable key was found to decode the JWT
```

This is the cause of the 401s. Fix it by registering Firebase as a Third-Party Auth provider ([Supabase docs](https://supabase.com/docs/guides/auth/third-party/firebase-auth)):

1. **Get your Firebase Project ID** — Firebase Console → ⚙ **Project settings → General → Project ID**.
2. **Add the integration in Supabase** — Supabase Dashboard → your project → **Authentication → Third-Party Auth** (sidebar under *Configuration*; direct link `https://supabase.com/dashboard/project/<project-ref>/auth/third-party`) → **Add provider → Firebase** → paste the Firebase Project ID → **Create/Save**.
   CLI equivalent, in `supabase/config.toml`:
   ```toml
   [auth.third_party.firebase]
   enabled = true
   project_id = "<firebase-project-id>"
   ```
3. **Give every Firebase user the `role: "authenticated"` custom claim.** Without it Supabase maps the request to the `anon` Postgres role and RLS denies access.
   - New users: `self-signup` and `admin-manage-user` already call `setCustomUserClaims(uid, { role: "authenticated" })`; alternatively deploy a Firebase blocking function (`beforeUserCreated` / `beforeUserSignedIn`).
   - Existing users: run a one-off Firebase Admin SDK script that lists all users and sets the claim (example in the Supabase doc linked above).
4. **Force a token refresh** on the device (`getIdToken(true)`, or sign out and back in) so the new claim is in the token.
5. **Verify**: decode a fresh ID token — it must have `iss = https://securetoken.google.com/<firebase-project-id>`, `aud = <firebase-project-id>`, `role = authenticated`. A `GET <SUPABASE_URL>/rest/v1/users?select=id&limit=1` with headers `apikey: <anon key>` and `Authorization: Bearer <firebase id token>` should now return 200 (rows filtered by RLS), not 401.

RLS policies read the Firebase UID from the JWT `sub` claim (`public.firebase_uid()`), not `auth.uid()`. Self-hosted Supabase must also add restrictive RLS policies that check `iss`/`aud` so tokens from unrelated Firebase projects are rejected (the hosted platform does this for you).

If Third-Party Auth isn't available on your plan, the fallback is an Edge Function that verifies the Firebase token and mints a short-lived Supabase-signed JWT (token exchange).

## Security notes

- Secrets live only in `local.properties`, `app/google-services.json` and Supabase function secrets — all outside git.
- The Firebase API key and Supabase anon key previously committed to this repo were purged from history and must be treated as compromised; they have been / must be rotated.
- Report security issues privately to the maintainer rather than in public issues.

## Project docs

- `DESIGN.md` — design system (colours, type, components).
- `FIREBASE_SUPABASE_THIRDPARTY_AUTH_IMPLEMENTATION.md`, `SUPABASE_FIREBASE_BRIDGE_RESEARCH.md` — auth bridge investigation.
- `USER_SCORES_INVESTIGATION.md` — scoring notes.
- `ADMIN_ACCOUNT_SETUP.md` — how the first admin was provisioned.
