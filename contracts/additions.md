# Endpoints beyond the pinned contract

`contracts.lock` pins the hub's `console-bff.yaml`. The web app's phase-1 thread asked for the
following additions (its note: `console-web-phase-1/bff-contract-additions.md`), which this
service serves today and which the hub should fold into the contract.

## Session

`Session.account` carries `roles` (the managed roles the person holds in the account) and
`groups`.

## Denial on every 403

Every 403 but a missing CSRF token carries `denied`:

```yaml
Denial:
  required: [layer, action, reason, requestable]
  properties:
    layer: { enum: [console-iam, platform] }
    action: { type: string }          # <service>:<verb>
    resource: { type: string }        # the wfrn or pattern checked
    reason: { type: string }
    role: { type: string }            # console-iam
    policyId: { type: string }        # console-iam
    statement: { type: string }       # console-iam
    scope: { type: string }           # platform
    requestable: { type: boolean }
    suggestedRole: { type: string }
```

A step-up refusal (fresh sign-in needed) is a 403 with `detail: { stepUp: true, action, login }`.

## Descriptors

`ConsoleAction` carries `allowed` (boolean) and `denied` (a Denial when false), decided per
session with `<service>:<verb>` on `wfrn:<partition>:<service>:<region>:<account>:<type>/*`.

## IAM pass-through

`/console/v1/iam/{users,groups,roles,policies,access-keys,quotas}` and their sub-paths map to the
identity service's `/v1/accounts/{accountId}/…` for the session's account, with the identity
service's schemas unchanged. `If-Match`, `If-None-Match`, `Idempotency-Key` and `Content-Type` are
forwarded; the status, `ETag` and body come back as they were; a 403 without a `denied` block gets
one. Writes need `X-Csrf-Token` like every other write.

## Access requests

```
GET  /console/v1/access-requests?status=&scope=mine|account&limit=&after=
POST /console/v1/access-requests            { action, resource?, role?, reason }  -> 202 AccessRequest, 409 when the same open request exists
POST /console/v1/access-requests/{id}:decide { decision: approve|reject, comment? } -> 202 AccessRequest (admins)
```

`AccessRequest`: `requestId, accountId, requester {userId, email, name}, action, resource, role,
reason, status (pending|approved|rejected|expired), requestedAt, decidedAt, decidedBy, comment,
approvers[]`.

## Sign-in

`GET /auth/login` takes `login_hint` (passed to the provider; the demo provider skips its picker)
and `stepUp=true`.

## Keys

`GET /.well-known/jwks.json`: the public keys other console services verify this service's
internal tokens and on-behalf-of assertions with.
