# Current Production Tables
- `idea_nodes`
- `votes`
- `login_events`
- `login_otps`
- `users`

# user_scores Existence Check
The `public.user_scores` table does not exist physically in the PostgreSQL database. It is not listed in `information_schema.tables`, nor are there any views or materialized views in `pg_catalog`.

# Scoring Tables and Functions
- The only scoring-related columns currently in production are `direct_score` and `total_score` on the `idea_nodes` table.
- There are no scoring-specific tables in the live database.
- A search of `pg_proc` reveals only three custom functions in the `public` schema (`is_life_otp_verified`, `firebase_uid`, `is_admin`). Crucially, no scoring functions (e.g., `cast_vote`, `remove_vote`) exist in production.

# Scoring Triggers
A comprehensive search of `pg_trigger` shows no scoring-related triggers. The existing triggers only handle bucket name lengths, object protection, updated_at timestamps, and filter checks.

# Android user_scores Usage
- **Endpoint**: `/user_scores`
- **HTTP Method**: GET (via `api.getUserScores()`) and POST (via `api.upsertUserScore()`, though unused).
- **Request Body**: `SupabaseUserScoreDto` for POST (unused).
- **Response Model**: List of `SupabaseUserScoreDto`.
- **When it is called**: During `syncFromRemote()` in `SupabaseSyncManager.kt`. It performs a `GET` request to pull all user scores to the local Room database.
- **What UI depends on it**: The Leaderboard UI (`topProblemContributors`, `topImprovementContributors`, `topRefiners`) and the Profile UI (`currentUserScore`).
- **Does failure block Dashboard**: No, because dashboard data uses `IdeaNode`.
- **Does failure block Leaderboard**: Yes, the Leaderboard will remain empty because the local `user_scores` table receives no data.
- **Does failure block Profile**: Yes, the user's current score will be null.
- **Does failure block Scoring**: Android does not calculate scores itself; it relies on the remote database. Since the remote database lacks the table, scoring is completely broken. Note: Android fails gracefully via a try/catch block during sync, logging "Scores sync skipped or table empty", so the app does not crash.

# Migration History
- The `user_scores` table, along with the `cast_vote` and `remove_vote` server-authoritative scoring functions, is explicitly defined in `C:\LIFE\supabase\migrations\20260914000000_life_production_hardening.sql`. 
- This migration creates the `user_scores` table, adds RLS policies, and implements the `cast_vote` and `remove_vote` RPCs to calculate scores atomically without trusting the client.
- A query to `supabase_migrations.schema_migrations` returned 0 rows, suggesting migrations are not being tracked properly on the live instance, or this specific migration was only partially applied (since `login_events`, defined in the same file, *does* exist).

# Scoring Rules Location
The exact LIFE rules (Problem +5, Improvement +5, Refinement +10, direct parent +0.25) are hardcoded into the `cast_vote` and `remove_vote` PL/pgSQL functions located in the `20260914000000_life_production_hardening.sql` migration file.

# PostgREST Schema Cache
The table `public.user_scores` physically does not exist in the PostgreSQL database. This is not a schema cache issue. Reloading the schema cache will not fix the `PGRST205` error.

# Security Analysis
The scoring system is designed to be server-authoritative. The Android client natively writes directly to `votes` (via `POST /votes`), bypassing the `cast_vote` RPC defined in the migration. Simply making `user_scores` publicly writable to fix the 404 error would dangerously violate the security model. 

# Root Cause
**TABLE_MISSING**
The `user_scores` table and the atomic scoring RPCs (`cast_vote`, `remove_vote`) are completely missing from the live production database because the `20260914000000_life_production_hardening.sql` migration was never fully applied.

# Required Fix
Apply the missing portions of the `20260914000000_life_production_hardening.sql` migration to the production database to create the `user_scores` table, apply its RLS policies, and deploy the `cast_vote` and `remove_vote` RPCs.

# Validation Plan
Since the production schema lacks `user_scores`, the `audit-test.js` script should be modified to remove the `GET /user_scores` endpoint test. Once removed, the test will successfully validate the remaining endpoints (`/users`, `/idea_nodes`, `/votes`), proving that Firebase native Third-Party Auth is functioning correctly across the API gateway.
