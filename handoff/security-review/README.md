# Customer console cross-service security review (2026-10-23)

Each service's newest branch was reviewed. Each finding went to the thread that owns the service, with a suggested fix. The details, with file:line references, are in the per-service files in this folder.

## Findings by service

| Service | High | Medium | Low | File |
|---|---|---|---|---|
| BFF | 3 | 0 | 5 | bff.md |
| State | 2 | 5 | 2 | state-service.md |
| Drift (including scorecards) | 1 | 4 | 2 | drift-service.md |
| Search indexer | 0 | 2 | 3 | search-indexer.md |
| Cost | 1 | 1 | 3 | cost-service.md |
| Web | 1 | 0 | 4 | console-web.md |
| Identity | 1 | 2 | 0 | identity-service.md (all fixed on PR #24) |

## The systemic issue: the shared `console` audience

The BFF mints every internal token with `aud [<target>, "console"]`, and every service accepts `console`. A token meant for one service therefore works at all of them.

Most services also treat any token without `acct` as internal across every account, and never check `svc`. Together, a BFF service token sent to identity could be replayed for 60 seconds as an all-account principal against state (shred, transfer, reveal), cost, drift and the indexer.

This breaks hub decision 23. Three changes fix it together:
- The BFF mints only the target audience.
- Every service accepts only its own name.
- Every service recognises an internal caller only by an allow-listed `svc`.

## Other high findings

1. **BFF: session roles are frozen for 12 hours.** An ended elevation or a revoked role keeps working downstream, because services trust the token's `roles`.
2. **BFF: `:batch` is authorized only on the type wildcard.** Resource-specific denies and guardrails can be bypassed by putting the denied resources in a batch.
3. **Drift: no write is authorized in the service itself.** A viewer token can revert, accept or remediate.
4. **State: a confused deputy through the registry-id fallback.** It can copy another account's resource record, sensitive fields included, into the caller's resource.
5. **Web: the console still reads the platform's `/api/v1` directly.** It sends a client-set `X-User` and no account scope.

## Fixed in identity

Fixed on PR #24 at 62dc61e, with the contract wording on hub #22 at 63ac333:
- Identity now accepts only its own audience.
- Access key secrets are no longer kept in the idempotency store.
- No auto-approval rule ships by default. The previous default made any operator an admin with no approver, and an admin can make that permanent.
