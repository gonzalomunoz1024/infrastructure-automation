# Security review: console-cost-service

The review covered branch `console-cost-service--phase-7`. It only read the code.

## High

### 1. The shared `console` audience is accepted
- **Where:**
  - `application.yaml:68`
  - `boot/CostProperties.java:157`
  - `auth/adapters/web/JwtVerifier.java:123-125`
- **Problem:** A token the BFF minted for another service passes the check here.
  - The BFF's own service token has no `acct`, so it becomes internal. For 60s it can read any account's cost, chargeback and budgets, and change budgets anywhere.
  - A person's token minted for another service also skips the BFF's `cost:list`, `cost:export` and `budgets:*` checks.
- **Fix:**
  - Accept only `console-cost-service`.
  - Fail closed when `audiences` or `issuer` is empty (lines 120 and 124 skip the checks today).

## Medium

### 2. An internal caller is recognised only by a missing `acct`
- **Where:** `ConsolePrincipal.java:318-320,331` and `JwtVerifier.java:131-136`.
- **Problem:** Any verified token without `acct` gets organisation-wide reads (up to 500 accounts) and budget writes. That includes `/v1/ledger:sync` and `ledger/status`.
- **Fix:**
  - Count a caller as internal only when it has no `acct`, its `svc` equals its `sub`, and that service is on an allow-list.
  - Answer every other such token with a 401.

## Low

### 3. CSV formula escaping is incomplete
- **Where:** `Chargebacks.java:93-101`.
- **Problem:** A cell that starts with a tab, a CR, or spaces followed by `=+-@` is not neutralised.
- **Fix:** Prefix those cells as well, and quote them.

### 4. The actuator is unauthenticated, although a comment says it authenticates itself
- **Where:** `ConsoleAuthWebFilter.java:51-53`.
- **Problem:** `/actuator/metrics` and `/actuator/prometheus` are open to anything that can reach the pod.
- **Fix:** Put the actuator on a separate management port.

### 5. The service does no action-level authorization of its own
- **Where:** `ChargebackController.java:39-62` and `BudgetController`.
- **Problem:** The `cost:list` versus `cost:export` split exists only in the BFF.
- **Fix:** As defence in depth, require `svc=console-bff` for these endpoints, or check `roles` for budget writes.

## Checked clean
- Tenant isolation in the stores.
- Mongo injection.
- SSRF: every outbound URL comes from config.
- Service token client.
- JWT signature, expiry and algorithm.
- Secrets in logs.
- The Kafka consumer.
- Dev headers: only in the demo profile.
