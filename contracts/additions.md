# Endpoints beyond the pinned contract

`contracts.lock` pins the hub's `console-bff.yaml` (console-root--phase-0 at 9edd017). The web
app's phase-1 thread asked for the additions below (its note:
`console-web-phase-1/bff-contract-additions.md`); the hub folded them into the contract at
29cdcf9 and e8992fb, and this file now only records what this service answers beyond the pinned
text, for the next contract revision.

## Resources

The resource operations (`/console/v1/r/{type}…`) are served as the contract says, over the state
service, with these details the contract does not spell out yet:

- The list asks `<service>:list` on the type's pattern and every page of one resource (detail,
  activity, drift, lineage) `<service>:get` on its wfrn; a refusal is a 403 with a Denial.
- The list's `sort` takes the console's form, `name`, `-name`, `created`, `-created`, `updated`
  and `-updated` (the state service's `createdAt` and `updatedAt` spellings pass too); the
  default is `-updated`, or relevance with `q`; anything else is a 400 naming the six.
- With `q` (at most 256 characters, the indexer's query syntax) the page comes from the search
  indexer and each hit is read again from the state service, in the index's order; a hit the
  state service no longer holds is left out, so such a page can hold fewer rows than `limit`
  while `next` goes on. Without `q` the page is the state service's.
- Every page writes `next`, `null` on the last page.

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

## Search

`GET /console/v1/search` and `GET /console/v1/suggest` are served as the contract says, through
the search indexer (`console.search.url`, audience `console-search-indexer`), with these details:

- `q` is required: at most 256 characters for a search, 1 to 128 for type-ahead (400 with a
  problem on `/q` otherwise); the indexer's 400 for a query it cannot read passes on with its
  problems, its 429 with `Retry-After`.
- A search asks `<service>:list` on every type of the catalogue and leaves out the hits of the
  types the person may not list; a page can therefore hold fewer hits than `limit` while `next`
  goes on. With `type`, an unknown type is a 404 and one the person may not list a 403.
- `SearchHit` also carries `service`, `region`, `drift`, `tags` and `updatedAt`, as the indexer
  answers them; `highlights` wraps each match in `<em>` inside HTML-escaped text.
- Type-ahead answers `{ items }`, at most `limit` (1 to 20, 8 by default): a resource or a wfrn
  when its type is listable, a type when listable, a tag term (`tag:<name>=<value>`) when any
  type is. `Suggestion` also carries `type` and `wfrn` where the indexer gives them.

`GET /console/v1/r/{type}:counts` is new: how many resources a list's filters match.

```yaml
parameters: q (as the list's), state, tag.<name>, drift (true|false)
200:
  total: integer       # what the list would page through: every filter, tombstones only with state=deleted
  byState:             # every state whatever the state filter, in this order; tombstones under deleted
    pending: integer
    active: integer
    updating: integer
    deleting: integer
    deleted: integer
    failed: integer
  drift: integer       # of total, those with an open drift finding
  cappedAt: integer | null   # set when counting stopped at a cap (show "1,000+"); null while exact
```

It asks `<service>:list` on the type's pattern (403 otherwise); an unknown type is a 404, an
unknown `state` a 400.

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

A step-up refusal (fresh sign-in needed) is a 403 with `error: "step-up.required"`, the sentence
in `message`, and `detail: { stepUp: true, action, login }`. `login` is a path:
`/auth/login?stepUp=true`, with `&returnTo=` when the request's `Referer` reduces to a console
path other than the root. A `Referer` never carries a hash route, so a console with hash routes
appends its own `returnTo` (for example `/#/console/compute/vm/<wfrn>?action=delete`), which
`/auth/login` honours alongside `stepUp=true`.

A console service that does not answer within its timeout is a 504 (`upstream service timed
out`); one that cannot be reached, or answers an error the BFF has no mapping for, is a 502.

## Descriptors

`ConsoleAction` carries `allowed` (boolean) and `denied` (a Denial when false), decided per
session with `<service>:<verb>` on `wfrn:<partition>:<service>:<region>:<account>:<type>/*`.

## IAM pass-through

`/console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}` and their
sub-paths map to the identity service's `/v1/accounts/{accountId}/…` for the session's account,
with the identity service's schemas unchanged. `quota-requests` is new beyond the pinned
`IamCollection` enum: `GET /console/v1/iam/quota-requests/{requestId}` follows an increase asked
with `POST /console/v1/iam/quotas/{quotaKey}:request-increase` (the quota's `pendingRequestId`
names it) and `DELETE` withdraws it; the decision is the platform team's, internal only. `If-Match`, `If-None-Match`, `Idempotency-Key` and `Content-Type` are
forwarded; the status, `ETag` and body come back as they were; a 403 without a `denied` block gets
one, named as the identity service names it (`quota:request-increase`, `quota:set`,
`access-request:<verb>`, else `iam:<verb>`, on `wfrn:wf:iam::<account>:<kind>/<id or *>`). Writes
need `X-Csrf-Token` like every other write.

The quota keys are the identity service's: `jobs-per-minute` (the platform's bucket, `source:
platform`, with `burst`), `access-keys` (`used` counts the account's active keys) and
`resources.<service>` for every service of the catalog. Viewers, operators and admins may ask for
an increase (`quota:request-increase`); auditors only read.

## Access requests

```
GET  /console/v1/access-requests?status=&requester=&limit=&after=
GET  /console/v1/access-requests/{id}
POST /console/v1/access-requests             { action, resource?, role?, reason }  -> 202 AccessRequest; 409 with the pending one of the same requester and role
POST /console/v1/access-requests/{id}:decide { decision: approve|reject, comment? } -> 202 AccessRequest; 409 once decided or expired
```

Passed through to the identity service as it decides them: a person always sees their own
requests; the account's queue (no `requester`, or someone else's) needs `access-request:list`
and one request `access-request:get`, which the managed `account-admin` and `auditor` policies
grant; deciding needs `access-request:decide` (admins, and the approver groups an admin granted
it, so gate the approve button on the request's `approvers`); `reason` is 1 to 2000 characters;
a request pending for 30 days is served `expired`. Nobody decides their own request: the
requester is never among its `approvers`, and their own `:decide` is a 403 whose `denied` names
`access-request:decide` on the request's wfrn with `requestable: false` and the reason "nobody
decides their own access request".

`AccessRequest`: `requestId, accountId, requester {userId, email, name}, action, resource, role,
reason, status (pending|approved|rejected|expired), requestedAt, decidedAt, decidedBy, comment,
approvers[]`.

## Preferences

`Preferences.savedViews`: per type key (`service/type`), at most 50 `SavedView`, kept as sent:

```yaml
SavedView:
  required: [id, name, filters, createdAt]
  properties:
    id: { type: string, pattern: '^v_[A-Za-z0-9_-]{1,64}$' }
    name: { type: string, minLength: 1, maxLength: 80 }   # unique per type, ignoring case
    filters:
      type: array
      maxItems: 50
      items:
        type: object
        required: [property, operator, value]
        properties:
          property: { type: [string, 'null'] }           # null is free text
          operator: { enum: ['=', '!=', ':', '!:', '>', '<', '>=', '<='] }
          value: { type: string, maxLength: 1024 }
    sort: { type: string }                               # the list's form, -created
    pageSize: { type: integer, minimum: 1, maximum: 200 }
    createdAt: { type: string, format: date-time }
```

A view the console could not show again is a 400; a `PUT` with a stale `version` is a 409, as
for every other preference.

## Sign-in

`GET /auth/login` takes `login_hint` (passed to the provider; the demo provider skips its picker)
and `stepUp=true`.

## Keys

`GET /.well-known/jwks.json`: the public keys other console services verify this service's
internal tokens and on-behalf-of assertions with.
