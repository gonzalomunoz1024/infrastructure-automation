# Security review: console-identity-service

All three findings are fixed on PR #24 at 62dc61e. The contract wording is on hub PR #22 at 63ac333.

## Fixed

### 1. The shared `console` audience was accepted
- **Severity:** high.
- **Problem:** Identity accepted any token addressed to `console`, so a token the BFF minted for another service could be replayed against it.
- **Fix:** It now accepts only `console-identity-service`, and a test covers the refusal.

### 2. Access key secrets were written to MongoDB
- **Severity:** medium.
- **Problem:** When a key mint or rotation carried an Idempotency-Key, the stored replay answer included `secretPart` in clear for 24 hours.
- **Fix:**
  - Answers that carry a secret are now sent with `Cache-Control: no-store`.
  - The idempotency store keeps only the fact that the request was carried out. A retry gets a 409, and the secret stays shown once.

### 3. The default elevation rule let any operator become account-admin with no approver
- **Severity:** medium.
- **Problem:** The rule approved such asks at once, with no person involved. As admin, the operator could then make the role permanent by adding themselves to the owners group.
- **Fix:**
  - No auto-approval rule ships by default.
  - The demo profile and the tests keep the rule.

## Checked clean
- **JWT checks:** ES256 only; issuer, audience, lifetime and skew are verified. A token without an account must carry `svc`.
- **Dev headers:** enabled only in the demo profile.
- **Mongo regex:** quoted.
- **Self-decisions:** refused on access requests, access reviews, elevations and break-glass.
- **Paths for another account:** decided against the person's own memberships.
- **Secrets in records, events and logs:** none found.
- **Outbound URLs:** all come from configuration.
