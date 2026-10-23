# Endpoints beyond the pinned contract

`contracts.lock` pins the hub's `console-bff.yaml` (console-root--phase-0 at b40ccc9). The web
app's phase-1 thread asked for the additions below (its note:
`console-web-phase-1/bff-contract-additions.md`); the hub folded them into the contract at
29cdcf9 and e8992fb, and this file now only records what this service answers beyond the pinned
text, for the next contract revision.

## Resources

The resource operations (`/console/v1/r/{type}…`) are served as the contract says, over the state
service, with these details the contract does not spell out yet:

- `ConsoleResource` also carries `observedAt`, `observedStatus`, `changes` (while `updating`),
  `lastError`, `lastJobId`, `requestedBy` and `deletedAt`, as the state service's record has them.
- `ConsoleResourceDetail.can` lists the catalog's verbs the account's policies allow on the
  resource and `tag` when `<service>:tag` is allowed; `Finding.can` is `revert, accept, ignore`
  when `<service>:update` is allowed.
- `ResourceRef` carries `id`, the platform's registry id, beside the wfrn, so a lineage node the
  platform has no wfrn for is still addressable.
- `GET /r/{type}/{wfrn}` answers the state service's `ETag` and honours `If-None-Match` (304);
  `PATCH` honours `If-Match` against the resource's `version` (409 when it moved on).
- `410` carries the tombstone as `detail` (`wfrn`, `deletedAt`, `tombstoneUntil`, `lastVersion`);
  `/activity` stays readable for a deleted resource.
- A destructive verb (`delete` and anything the catalog marks destructive) needs `confirm: true`
  (400 `confirm.required` otherwise) and a fresh sign-in (`console.auth.step-up`).
- `POST /r/{type}:batch` answers 400 `bulk.unsupported` for a verb without a batch route and
  400 `required` for a row without `wfrn` on a verb other than create.

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
