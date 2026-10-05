# Current Architecture
The current architecture relies on Firebase Auth for identity and passwords, while storing application data in Supabase PostgreSQL. Android clients authenticate via Firebase, retrieve a Firebase RS256 JWT, and attach it to standard Retrofit `Authorization: Bearer` headers aimed at Supabase PostgREST endpoints and Edge Functions. Currently, Supabase PostgREST rejects these requests with `PGRST301: No suitable key or wrong key type` because it attempts to verify the RS256 signature using a symmetric `HS256` secret, breaking all REST API data access. The `public.firebase_uid()` RLS bridge works perfectly at the database layer but is rendered unreachable by the API gateway.

# Confirmed Supabase Platform Capabilities
Based on current official Supabase documentation:
- **A. PostgREST external JWKS/RS256**: **YES**. PostgREST itself supports verifying tokens signed with asymmetric keys if configured with a JWKS (JSON Web Key Set).
- **B. Firebase/Google public keys configuration**: **YES**. The hosted Supabase platform offers a native "Third-Party Auth" integration explicitly designed for Firebase, Auth0, and AWS Cognito. When configured via the dashboard/CLI, Supabase automatically fetches the provider's JWKS and trusts its tokens.
- **C. Custom JWT signing**: **YES**. You can mint custom JWTs using the Supabase project's `JWT_SECRET`.
- **D. Edge Functions verifying RS256**: **YES**. Deno Edge Functions can import standard JWT libraries or the Firebase Admin SDK to verify external tokens.
- **E. Edge Functions issuing Supabase JWTs**: **YES**. Edge Functions have access to the project secret and can mint a Supabase-compatible `HS256` JWT payload.
- **F. Official Supabase Third-Party mechanism**: **YES**. Native OIDC/Third-Party Auth integration natively bridges this gap without custom token exchange code.

# Firebase JWT Verification Model
Firebase ID Tokens are standard JWTs signed asymmetrically using the `RS256` algorithm. The private key remains securely with Google, while the public keys rotate frequently and are published at Google's public JWKS endpoint (`https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com`). Any verifying party (Supabase PostgREST or Edge Functions) must dynamically fetch and cache these public keys to validate the token's cryptographic signature and check the `aud` and `iss` claims against the specific Firebase project.

# Android Supabase Operations
The Android client makes the following network calls to Supabase, all currently intercepting and injecting the Firebase ID token (unless anonymous/self-signup):

**REST API (PostgREST)**
- `getUsers`: READ (Expects: Firebase ID Token)
- `upsertUser`: CREATE/UPDATE (Expects: Firebase ID Token)
- `getIdeaNodes`: READ (Expects: Firebase ID Token)
- `insertIdeaNode`: CREATE (Expects: Firebase ID Token)
- `updateIdeaNode`: UPDATE (Expects: Firebase ID Token)
- `deleteIdeaNode`: DELETE (Expects: Firebase ID Token)
- `getVotes`: READ (Expects: Firebase ID Token)
- `insertVote`: CREATE (Expects: Firebase ID Token)
- `deleteVote`: DELETE (Expects: Firebase ID Token)
- `getUserScores`: READ (Expects: Firebase ID Token)
- `upsertUserScore`: CREATE/UPDATE (Expects: Firebase ID Token)

**RPC & Edge Functions**
- `resolve_login_identity` (RPC): READ (Expects: Anonymous)
- `resolve-login-identity` (Edge): READ (Expects: Anonymous)
- `self-signup` (Edge): CREATE (Expects: Anonymous)
- `send-login-otp` (Edge): CREATE (Expects: Firebase ID Token)
- `verify-login-otp` (Edge): UPDATE (Expects: Firebase ID Token)
- `admin-manage-user` (Edge): UPDATE (Expects: Firebase ID Token)
- `complete-first-login` (Edge): UPDATE (Expects: Firebase ID Token)

*Supabase Auth GoTrue endpoints (signup, user, refresh, logout) exist in `SupabaseAuthApi.kt` but have been deprecated in favor of Firebase Auth by the application architecture.*

# Architecture 1 Analysis
*Firebase JWT → Supabase PostgREST → PostgreSQL RLS*
(Relies on configuring Supabase's native Third-Party Auth / JWKS integration)
- **Security model**: High. Supabase natively trusts Firebase's JWKS.
- **RLS behavior**: Seamless. `request.jwt.claims->>'sub'` extracts the Firebase UID automatically.
- **Token verification**: Supabase PostgREST handles RS256 JWKS fetching and validation.
- **Token lifetime**: Managed by Firebase (1 hour).
- **Refresh behavior**: Android Firebase SDK handles refresh; Retrofit interceptor attaches the fresh token.
- **Android implementation complexity**: Zero. The current code perfectly supports this.
- **Impact on existing Retrofit APIs**: None.
- **Impact on existing RLS policies**: None.
- **Impact on existing RPC functions**: None.
- **Admin/Student/OTP authorization**: Continues relying on PostgreSQL functions (`is_admin()`, `is_life_otp_verified()`).
- **Scalability**: High (handled by Supabase API gateway).
- **Operational complexity**: Low (configuration-only).
- **Failure modes**: Supabase JWKS cache desyncing with Google's key rotation (rare but possible).
- **Migration complexity**: Very low. Requires dashboard/config changes, no code rewrites.

# Architecture 2 Analysis
*Firebase JWT → Supabase Edge Function → verify token → Service Role DB Operation → return result*
- **Security model**: Centralized server-side authority. RLS is bypassed entirely.
- **RLS behavior**: Bypassed (Service Role ignores RLS).
- **Token verification**: Manual verification in Deno using Firebase Admin SDK.
- **Token lifetime**: Firebase standard.
- **Refresh behavior**: Standard Firebase refresh.
- **Android implementation complexity**: Extremely high. Every Retrofit PostgREST call must be rewritten to hit a new Edge Function endpoint.
- **Impact on existing Retrofit APIs**: Total rewrite of all Data API calls.
- **Impact on existing RLS policies**: RLS becomes obsolete. Logic must be rewritten in TypeScript.
- **Impact on existing RPC functions**: Replaced by Edge Functions.
- **Admin/Student/OTP authorization**: Must be manually queried and validated in TypeScript before executing data operations.
- **Scalability**: Lower than PostgREST due to cold starts and Deno proxy overhead.
- **Operational complexity**: High. You maintain a custom backend router in Deno.
- **Failure modes**: Edge Function timeouts, cold start latency.
- **Migration complexity**: Severe. Requires rewriting the entire backend data access layer.

# Architecture 3 Analysis
*Firebase JWT → Secure Token Exchange (Edge) → Supabase HS256 JWT → PostgREST → PostgreSQL RLS*
- **Security model**: Custom OIDC bridge.
- **RLS behavior**: Seamless. Standard Supabase JWTs are passed to Postgres.
- **Token verification**: Edge function validates RS256 token, signs new HS256 token.
- **Token lifetime**: Defined by the Edge Function (can be 1hr to match Firebase).
- **Refresh behavior**: Android must explicitly call the exchange endpoint every time Firebase refreshes the token.
- **Android implementation complexity**: Moderate. Requires intercepting Firebase token refresh events and chaining them to the Edge Function before returning the new token to Retrofit.
- **Impact on existing Retrofit APIs**: None for data access, but requires new authentication orchestration logic.
- **Impact on existing RLS policies**: None.
- **Impact on existing RPC functions**: None.
- **Admin/Student/OTP authorization**: Handled normally via Postgres functions.
- **Scalability**: Good (PostgREST handles data load, Edge function only handles auth bursts).
- **Operational complexity**: Moderate. You maintain a critical path token-exchange service.
- **Failure modes**: Exchange service downtime completely locks users out.
- **Migration complexity**: Moderate (Android orchestration rewrite).

# Architecture 4 Analysis
*Firebase JWT → API Gateway (Edge) → Database access through RPC/Server*
*(This is effectively a blend of Architecture 2 and 1 where Edge acts as an API gateway that validates tokens and calls Postgres RPCs passing the user identity context, or sets local variables before querying).*
- **Security model**: API Gateway pattern.
- **RLS behavior**: Can be maintained if the Edge function calls Postgres with `set_config('request.jwt.claims')` before querying, but complex.
- **Token verification**: Handled in Deno.
- **Android implementation complexity**: High. Changes all endpoints from PostgREST defaults to custom endpoints.
- **Migration complexity**: High.

# Security Implications
- **Data Integrity**: Preserving direct PostgREST/RLS access maintains the strict database-level security policies currently written. Architectures that bypass RLS (Arch 2) introduce massive risk of business logic bugs allowing unauthorized data access.
- **Key Management**: Architectures 1 relies on platform JWKS fetching, which is highly secure and automated. Architecture 3 requires securely maintaining the Supabase `JWT_SECRET` inside an Edge Function.
- **DebugDemoAuth Removal**: The removal of `DebugDemoAuth.kt` closes a catastrophic vulnerability where synthetic identities bypassed Firebase and OTP entirely. The production path is now strictly tied to genuine Firebase JWTs.

# Migration Impact
- **Data Model**: The data model (Problems, Improvements, Refinements, voting, scoring) is fully preserved across all architectures since it lives in PostgreSQL tables and triggers.
- **Codebase**: Architecture 1 requires **zero** Android API changes and **zero** SQL changes. Architecture 3 requires moderate Android changes. Architectures 2 and 4 require rewriting the entire Android network layer and Supabase backend.

# Recommended Decision Framework
- **If preserving direct PostgREST/RLS access is mandatory**: Architectures 1 and 3 satisfy it. Architectures 2 and 4 do not.
- **If minimizing Android API changes is mandatory**: Architecture 1 is the only option that requires zero code changes. Architecture 3 requires moderate auth orchestration changes.
- **If keeping PostgreSQL RLS as the authorization boundary is mandatory**: Architecture 1 and 3 preserve it natively.
- **If Supabase's native Third-Party Auth integration is available and configurable on this tier**: Architecture 1 is strictly the optimal choice.
- **If Supabase Third-Party Auth is NOT available (e.g., restricted tier)**: Architecture 3 is the fallback that preserves the Data API and RLS.

# DebugDemoAuth Cleanup
- `c:\LIFE\app\src\main\java\com\example\data\repository\DebugDemoAuth.kt` was completely deleted.
- Calls to `DebugDemoAuth` in `AuthRepository.kt` have been removed from the `login` function.
- Synthetic email logic and demo login interception are no longer active in the authentication flow.
- A local Gradle build check failed due to the specific environment's `JAVA_HOME` configuration, but the code modifications are syntactically sound.

# Implementation Prerequisites
To unblock the application, the exact capabilities of the specific Supabase project must be confirmed:
1. Does the project have access to the "Authentication > Providers > Third-Party Auth" dashboard?
2. If yes, the Firebase JWKS URL (`https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com`) must be configured there.

# Open Questions
1. Is the current Supabase project tier eligible for native Third-Party Auth (OIDC) configuration?
2. If Third-Party Auth is not accessible, is the token exchange Edge Function (Architecture 3) acceptable despite the Android lifecycle orchestration complexity?

BRIDGE STATUS:
NOT READY TO IMPLEMENT
