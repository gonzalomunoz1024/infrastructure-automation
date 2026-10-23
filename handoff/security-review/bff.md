# Security review: console-bff

Branches reviewed: `console-bff--drift` (which includes phases 1 and 4) and `console-bff--cost`. The cost branch was reviewed against its parent commit, ca9f17c. The review only read the code.

## High

### 1. Session roles are frozen at sign-in for 12 hours
- **Where:** `session/application/Login.java:108`, `identity/application/IdentityClient.java:58`, `HttpStatePort.java:282`, `HttpDriftPort.java:472` and `RelayClient.java:228` (cost branch). `Sessions.refreshAccounts` (`Sessions.java:105`) is never called.
- **Problem:** The roles returned by `principals:resolve`, including elevations and break-glass, are copied into every internal JWT's `roles` claim for the whole session. Downstream services trust that claim; for example, state grants `reveal=sensitive` and evidence packs to account-admin. Someone whose one-hour elevation has ended, or whose role was revoked, therefore stays account-admin downstream for up to 12 hours. `OrganizationScope.java:40` (cost branch) reads the same stale membership list.
- **Fix:**
  - Re-resolve the person's memberships on `identity.changes` and `ElevationChanged`, or every few minutes.
  - Cap the session's role validity at the earliest elevation `until` (identity lists it in `accounts[].elevations`).
  - Alternatively, stop putting roles in tokens.

### 2. Every minted token carries the shared audience `console`
- **Where:** `trust/application/Trust.java:97` (both branches).
- **Problem:** A token minted for one service is accepted by every other service. The BFF's own internal tokens have no `acct` and can name any account. They go to identity on every authorize and resolve, and on the cost branch to cost and drift. Whoever receives one can replay it for 60 seconds against state, drift or cost as the all-accounts internal principal. This also bypasses the hub decision 23 registry.
- **Fix:**
  - Mint `aud` as the single target service and drop CONSOLE_AUDIENCE.
  - Every service drops `console` from its accepted audiences. Identity already has, on PR #24 at 62dc61e.

### 3. Batch commands are authorized only on the type wildcard
- **Where:** `resources/application/Resources.java:617-619`, and `previewBatch` at about line 668 (both branches).
- **Problem:** The batch is checked once, on `…:<type>/*`. Identity's evaluator treats `*` in a requested resource as a literal, so a deny such as `Deny compute:restart on …:vm/prod-*` never matches that check. Putting the denied resources in a batch therefore runs the denied verb on them. `TagEditor.apply` already does this correctly, per row.
- **Fix:**
  - Decide one check per row, on its wfrn, before calling the platform.
  - Refuse the batch, or drop and name the denied rows.
  - Do the same in `previewBatch`.

## Low

### 4. The IAM proxy forwards raw path segments
- **Where:** `IamController.java:38-43` and `IamProxy.java:48-49`.
- **Problem:** A path such as `/console/v1/iam/users/../../222222222222/users`, or one using `%2e%2e`, reaches identity as is. Identity decides on the account in the path against the person's own memberships, so this does not cross tenants there. It is still fragile.
- **Fix:**
  - Reject segments that are empty, `.` or `..`, or that contain `%2e`, `%2f` or `\`.
  - Build the URI from encoded segments, as RelayClient does.

### 5. Scorecard remediation fails open when the pack lookup fails
- **Where:** `Scorecards.java:150` (cost branch).
- **Problem:** When the action is null, the verb check and step-up are skipped. The region is not checked either.
- **Fix:**
  - Refuse with a 409 or 503 instead of skipping the action check.
  - Check that the wfrn is in the session's region.

### 6. The decision cache key ignores region
- **Where:** `Access.java:29,98` and `HttpAuthorization.java:215`.
- **Problem:** A decision made in one region is reused for 60 seconds after switching to a region a guardrail denies.
- **Fix:** Add the region, or the whole context, to the key.

### 7. A logged-out session can come back, and the raw session id is the Mongo `_id`
- **Where:** `Sessions.java:58` and `MongoSessionStore.java:53-58`.
- **Problem:** Writes are upserts, so a request in flight re-inserts a session that was logged out. Anyone who can read the database also holds live session ids.
- **Fix:**
  - Use conditional updates, not upserts.
  - Store only a SHA-256 of the session id.

### 8. Replay protection on `POST /internal/v1/token` is weak
- **Where:** `AssertionReplays.java:11-23` and `ServiceTokens.java:197-199`.
- **Problem:**
  - The `jti` cache is per replica, so one signed assertion can be redeemed once on each replica.
  - Kubernetes service-account tokens are never replay-checked, and their lifetime is not capped.
  - There is no rate limit.
- **Fix:**
  - Keep used `jti`s in a shared store.
  - Cap the lifetime of cluster tokens.
  - Add a per-client rate limit.

## Checked clean
- CSRF and CORS.
- The SSO callback: sealed ticket, state, PKCE, nonce, and ID token checks.
- The ReturnTo redirect.
- Tenant isolation on the resource, drift, cost, budget, tag, group and jobs paths.
- Secrets in logs.
- SSRF: every outbound URL comes from config.
- Path and parameter injection.
- Routes that exist only in the demo profile.
- The BFF accepts no bearer tokens, so an internal token cannot become a session.
