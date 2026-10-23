# Security review: console-state-service

Branch reviewed: `console-state-service--phase-9` at df9ba93. The review only read the code; nothing was changed. Paths are relative to the repository.

## High

### 1. The shared `console` audience is accepted, and any token without `acct` is treated as internal
- **Where:** `boot/StateProperties.java:95`, `application.yaml:66`, `deploy/helm/console-state-service/values.yaml:79`, `auth/adapters/web/JwtVerifier.java:125`, `auth/domain/ConsolePrincipal.java:30`.
- **Problem:** The BFF mints every token with `aud [<target>, "console"]`, so a token meant for identity, cost, drift or the indexer is also valid here. `internal()` only checks that `acct` is missing; `svc` is never checked. A replayed service token therefore gets every account: `reveal=sensitive`, `/internal/accounts/{id}:shred` (an irreversible crypto-shred), `:transfer` and `/internal/import`.
- **Fix:**
  - Accept only `aud == console-state-service`.
  - Treat a caller as internal only when `svc` is present, equals `sub`, and is on an allow-list.
  - Reject user tokens that have no `acct`.

### 2. A caller's registry id is resolved on the platform with no account check
- **Where:** `inventory/application/PlatformResources.java:28-31` (the by-id fallback). It is reached from `PendingReaper.java:140,207` and `JobOutcomes.java:169`. The entry point is the user `:import` in `InventoryService.java:99-124`, guarded only at :348-360.
- **Problem:** A user in account A can import an A wfrn that carries account B's `registryId`, then call `:request-update` without sending a job. When the job times out, the reaper's `fetch` falls back to `getResource(registryIdB)` using the console's privileged token. That copies B's attributes, sensitive ones included, into A's resource. It also lets A squat B's registry id.
- **Fix:**
  - In `fetch`, accept a record found by registry id only when its wfrn is null or equal, and its account matches.
  - Check registry ownership on a user import, or limit `:import` to internal callers.

## Medium

### 3. Dev-header authentication has no profile guard
- **Where:** `ConsoleAuthWebFilter.java:67-78`, `boot/AuthConfiguration.java:23`.
- **Problem:** Setting `CONSOLE_AUTH_DEVHEADERS=true` turns it on in any profile. Once it is on, `X-Console-Service: x` alone makes a request internal.
- **Fix:** Create the dev-header path only under `@Profile({"demo","dev"})`, and fail at startup if it is on alongside `mongo` or `kafka`.

### 4. Drift-only commands accept user tokens
- **Where:** `ResourceController.java:242-262` (`:record-observation`, `:record-drift`, `:resolve-drift`) and `:147` (`:import`).
- **Problem:** A user can send `record-observation status: not_found`, which records `Deleted` and skips the `<service>:delete` authorization and the platform job. A user can also forge drift findings and their resolutions.
- **Fix:** Require an internal caller, ideally `svc = console-drift-service`.

### 5. The audit actor can be spoofed from the request body
- **Where:** `Requests.java:19-21,30,103`.
- **Problem:** The `actor` field in the body overrides the token's `sub`, even for people. That value then goes into the event log, the hash chain and the evidence packs.
- **Fix:** Ignore `actor` unless the caller is internal, and record it as on-behalf-of next to the real `sub`.

### 6. Only top-level fields are sealed
- **Where:** `SensitiveData.java:116-143` and `PlatformResourceTypes.java:100-105`.
- **Problem:** A nested secret such as `{"connection":{"password":...}}`, or a nested property marked `x-console.sensitive`, is stored in clear. It is also published unmasked on `state.resources`, which means it reaches the indexer.
- **Fix:**
  - Seal recursively through objects and arrays.
  - Express `sensitiveFields` as JSON pointers.

### 7. A crypto-shred does not reach the other replicas
- **Where:** `SensitiveData.java:65,335-354` and `KafkaFeedConsumer.java:124-139`.
- **Problem:** Unwrapped keys are cached forever. Only one pod handles the shred, so the other pods keep decrypting the closed account's data until they restart.
- **Fix:**
  - Give the key cache a short TTL and re-check that the key still exists before decrypting.
  - Broadcast the shred to every pod, for example with a per-pod consumer group.
  - Never create a key for a closed account.

## Low

### 8. The transfer target account is not validated here
- **Where:** `InventoryService.java:180-189`.
- **Problem:** Any account principal can move its resource into any account. The service relies on the BFF having authorized both ends.
- **Fix:** Limit `:transfer` to internal callers.

### 9. The registry-collision error reveals another tenant's wfrn
- **Where:** `InventoryService.java:357-358`.
- **Problem:** The 409 detail names the wfrn that holds the registry id, even when it belongs to another account.
- **Fix:** Include that wfrn only when it is in the caller's scope.

## Checked clean
- **JWT handling apart from the audience:** ES256 is pinned, and issuer, exp and nbf are checked with 30s skew.
- **Mongo injection:** none (the queries use `Pattern.quote` and equality matches).
- **Account scoping:** every port is scoped, and another account answers 404.
- **Other:** idempotency is per account, outbound URLs come from config, and the feed checks that the event's account matches the wfrn's.
- **Reveal rule:** works as designed, honoured only for internal callers or account-admin in roles.
- **Retry flow:** checks the wfrn.
- **Logs:** no secrets in them.
