# Security review: console-drift-service

Branches:
- **F** is `console-drift-service--finder-and-monitoring`.
- **S** is `console-drift-service--scorecards`. Only commit ad9cbcc was reviewed there.

The review only read the code.

## High

### 1. The service never authorizes writes itself
- **Where:**
  - `drift/adapters/web/FindingController.java:117-154` (F)
  - `scorecards/adapters/web/ScorecardController.java:109-124` (S)
  - `rightsizing/adapters/web/RecommendationController.java:99-123` (F)
  - `monitoring/adapters/web/AlarmController.java:163-174` (F)
- **Problem:** The service never calls `/v1/authorize` and never checks `roles`. The only check is "same account as the token". A viewer or auditor token can therefore revert, accept, ignore, remediate, apply recommendations, or create alarms. Accept rewrites the desired state through the drift service's own identity. The tags fix (`RemediationService.tags`, S:142-166) sends no X-On-Behalf-Of.
- **Fix:**
  - Call identity's `POST /v1/authorize` for each write, with an action such as `drift:revert`, `drift:accept` or `scorecards:remediate`, on the finding's or resource's wfrn.
  - At minimum, require operator or account-admin.
  - Make accept and the tags fix carry the person's assertion, as revert does.

## Medium

### 2. The shared `console` audience is accepted
- **Where:** `application.yaml:113` (F and S) and `auth/adapters/web/JwtVerifier.java:129-132`.
- **Problem:** A user token minted for another service can be replayed here.
- **Fix:** Accept only `console-drift-service`.

### 3. A caller can name any actor in the body
- **Where:**
  - `DriftService.java:239,256`
  - `DiscoveryController.java:131`
  - `AlarmService.java:178`
  - `RecommendationService.java:260`
  - `RemediationService.java:144,173` (S)
- **Problem:** `actorOr` takes the body's `actor` even when a person calls, so the audit trail can be forged.
- **Fix:**
  - When a person calls, use `principal.subject()`.
  - Honour `actor` only from `svc == console-bff`, or better, derive it from X-On-Behalf-Of.

### 4. The shadow finder lets any account import another team's unmanaged objects
- **Where:** `DiscoveryService.java:124-164,323-338` (F).
- **Problem:** Discovery walks the whole region with the internal token, so any account can `:import` another team's shadow VM and then manage it.
- **Fix:**
  - Allow an import only by the guessed-owner or assigned account, or require the owner to confirm.
  - Project only non-sensitive observed fields.

### 5. Any service token is an all-account operator
- **Where:** `ConsolePrincipal.java:295-297,312-322` and `Principals.java:211-234`.
- **Problem:** `internal()` only checks that `acct` is missing.
- **Fix:** Keep an allow-list of `svc` values per capability.
  - Only `console-bff` relays a person's actions.
  - Only the scheduler may call `/internal`.

## Low

### 6. Scorecard Rego is pushed to OPA without authentication or a package check
- **Where:** `OpaPolicyEngine.java:28-45` (S) and `Rule.java:61-62` (S).
- **Problem:** A rule pack could overwrite any package in a shared OPA, including the console's authorization policy.
- **Fix:**
  - Reject a rule whose `package` is not its `regoPackage()`.
  - Send a bearer token to OPA.
  - Turn `push-policies` off by default, or point it at a dedicated OPA.

### 7. Webhook signatures carry no timestamp
- **Where:** `WebhookService.java:62-127` (F).
- **Problem:** A captured signed body can be replayed to burn the observe budget.
- **Fix:**
  - Sign a timestamp with the body and reject anything older than 5 minutes.
  - Remember recent delivery ids.

## Checked clean
- **Tenant isolation:** every store read is scoped by account.
- **Kafka:** the consumer re-reads the resource from the state service.
- **JWT:** ES256, issuer and expiry are checked.
- **Service tokens:** handled correctly.
- **Secrets:** none in logs, records or errors.
- **Injection:** none found.
- **SSRF:** every outbound URL comes from config.
- **Dev headers:** only in the demo profile.
