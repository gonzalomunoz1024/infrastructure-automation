# Endpoints beyond the pinned contract

`contracts.lock` pins the hub's contracts (console-root--state-retry at c6c22ab: decision 24 on console-root--phase-0). The web
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

## Changing resources safely (phase 4)

New in phase 4. Every shape below is served by the demo profile as written.

**Blast radius (WP 4.5).** `GET /r/{type}/{wfrn}/blast-radius?depth=` (1 to 5, 3 by default; as
`<service>:get` on the wfrn allows) answers what depends on the resource:

```yaml
BlastRadius:
  resource: ResourceRef
  total: integer          # dependents within depth, every account
  direct: integer         # those that depend on the resource itself
  byAccount: [{ accountId, count, current }]   # current: the session's account
  dependents: [{ wfrn, type, name, state, id, depth }]   # named in the session's account only, nearest first; other accounts are counted
  edges: [{ from, to, via }]                  # wfrns; via is the lineage relation
  depth: integer
  truncated: boolean      # true when the walk stopped at 500 dependents
```

- The verb preview `POST /r/{type}/{wfrn}:{verb}:preview` of a delete or a destructive verb
  carries `blastRadius`, and its named dependents join `affectedResources`. A delete's preview
  with live dependents has `policy.decision: deny` with the rule `dependents.exist`. A delete
  whose workflow publishes no preview still answers, with the console's part alone.
- A delete (`POST /r/{type}/{wfrn}:delete`) while live resources depend on it is a 409:
  `{ error: "dependents.exist", message, problems, correlationId, detail: { dependents:
  [Dependent], total, byAccount } }`. Nothing runs.
- `POST /r/{type}:batch:preview` takes the `:batch` body and answers, before anything runs:
  `{ verb, items: [{ index, wfrn, name, state, dependents, named: [Dependent], problems }],
  affected: BlastRadius (what the rows reach together, a shared dependent counted once),
  policy }`. A row's problems: `not-found`, `state`, `dependents.exist` (a delete row),
  `required` (a create row's missing field), `tags.required` / `tags.invalid` (a create row's
  tags). `policy.decision` is `deny` with one rule per kind of problem.
- A batch delete with a row that has live dependents is a 409 `dependents.exist` with
  `detail.rows: [{ wfrn, dependents, total }]`.

**Retry, skip, abort (WP 4.2).** On `GET /console/v1/jobs/{jobId}`, `can` lists what the policies
allow and the platform offers: `cancel` (running), `retry`, `skip`, `abort`.

- `POST /console/v1/jobs/{jobId}:retry` → 202 `CommandAccepted { wfrn, jobId, requestId,
  approval }`. A job paused on a failed step (`needs-attention`) resumes with the same `jobId`. A
  failed, cancelled or rolled-back job runs again as a new job (the platform's `:rerun`, from its
  failed step, or from its first step when it rolled back), judged like the first request
  (`<service>:<verb>` on its wfrn, a fresh sign-in for a destructive one). A finished batch runs
  its failed rows again. A failed create's resource goes back to `pending` on the state service
  (`RetryRequested` in its activity, naming the rerun as `jobId` and the failed job as
  `retryOf`). 409 `job.not-failed` with `detail.status` when there is nothing to retry; 409 when
  the resource moved on (a create whose resource is no longer failed).
- `POST /console/v1/jobs/{jobId}:skip` with optional `{ step }` (the step the job waits on when
  left out) → 202 `CommandAccepted`, the same `jobId`; asks what the request's own action asks.
- `POST /console/v1/jobs/{jobId}:abort` → 202 `CommandAccepted`; the compensations run and the
  job ends `rolled-back` or `failed`; asks `jobs:cancel`.
- Skip and abort of a job that is not paused are a 409 `job.not-paused` with `detail.status`.

**Bulk in waves (WP 4.2).** `POST /r/{type}:batch` takes `rollout: { waves: [percent...],
maxParallel, stopIfFailedPercent }` (cumulative percentages, the workflow's own rollout when left
out). More rows than the workflow takes in one batch is a 400 `batch.too-large` at `/items`.

- The batch's job (`GET /console/v1/jobs/{jobId}`) carries `progress { done, total, failed }`,
  `children` (the rows' jobs), and
  `batch: { rollout, waves: [{ index, percent, size, status, startedAt, endedAt }], rows: [{
  index, wfrn, jobId, status, error, wave }], stoppedReason }`. A row names its wfrn once its job
  started.
- While the batch runs, its `approvals` include its rows' pending approvals, each with
  `canDecide`.
- `POST /console/v1/jobs/{jobId}/approvals:decide { decision: approve|reject, comment? }` decides
  every pending approval of the request and its rows that the person may decide, each as their
  own platform decision: 202 `{ decision, decided: [approvalId], left: [{ approvalId, jobId,
  reason }] }`. `left` names the approvals of a group the person is not in, the person's own
  request, or one decided a moment ago. It asks `approvals:decide` (403 with a Denial for a
  viewer). Call it once per wave.

**Required tags (WP 4.4).** `GET /console/v1/tag-policy` (or `?account=<id>` for another account
of the person's, else 404) answers `{ accountId, required: [RequiredTag] }`:

```yaml
RequiredTag:
  key: string             # owner, cost-centre, data-class, environment
  title: string
  description: string
  values: [string]        # when the tag takes one of a list (data-class, environment)
  pattern: string         # when it takes a pattern (cost-centre: ^cc-[0-9]{4}$)
  default: string         # what the create form pre-fills
  defaultFrom: string     # account | organization | input.<field> (copy the create's own field)
```

- Every create names every required tag itself: `POST /r/{type} { input, tags }`, and
  `:batch { verb: create, tags, items: [{ input, tags }] }` (the batch's tags, a row's over them).
  The BFF never adds one the request left out, so API and MCP callers are held to it too.
- A missing or blank tag is a 400 `tags.required`, a value outside the tag's list or pattern a
  400 `tags.invalid`, each with a problem at `/tags/<key>` (`/items/<i>/tags/<key>` in a batch).
  Nothing is minted.
- `POST /r/{type}:preview { input, tags }` answers `policy.decision: deny` with the rule
  `tags.required` (message: the missing keys) or `tags.invalid` while the tags fall short.
- The tags are set on the minted resource before its workflow starts, so the resource carries
  them from `pending` on.
- `PUT /r/{type}/{wfrn}/tags` may not remove a required tag the resource carries (400
  `tags.required` at `/tags/<key>`) or give one a value it may not take (400 `tags.invalid`). A
  resource that never carried one (imported, or older than the policy) is not made to.
- Configuration: `console.tags.required` (key, title, description, values or pattern, default,
  default-from: `account.owning-team`, `account`, `input.<field>`); `console.tags.accounts.<accountId>.<key>`
  for an account's own value until the identity service's account record carries one.

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

## Resource Groups

New: saved tag queries over the account's resources, per account and region, as AWS Resource
Groups keeps them, and the resources each one matches.

```
GET    /console/v1/resource-groups                               -> 200 { items: [ResourceGroup], next: null }, by name
POST   /console/v1/resource-groups            { name, description?, query }  -> 201 ResourceGroup, Location, ETag
GET    /console/v1/resource-groups/{groupId}                     -> 200 ResourceGroup, ETag; 304 on If-None-Match
PATCH  /console/v1/resource-groups/{groupId}  { description?, query? }       -> 200 ResourceGroup, ETag
DELETE /console/v1/resource-groups/{groupId}                     -> 204
GET    /console/v1/resource-groups/{groupId}/resources?limit=&after=  -> 200 { items: [ConsoleResource], next }
```

```yaml
ResourceGroup:
  groupId: string        # group-<ulid>, lowercase
  wfrn: string           # wfrn:<partition>:resource-groups:<region>:<account>:group/<groupId>
  name: string           # 1 to 128 of [A-Za-z0-9._-]; unique per account and region; never changes
  description: string    # at most 512 characters; "" when not sent
  query:
    types: [string]      # type names of the region's catalogue (vm, volume); [] is every type
    tags: { <name>: <value> }   # 1 to 20; a name is [a-z0-9-]{1,32}, a value at most 256 characters
  accountId: string
  region: string
  createdBy: string      # the user id
  createdAt: date-time
  updatedAt: date-time
  version: integer       # 1 on create, one more on each change that changes something; the ETag
```

- Every call asks `resource-groups:<verb>`: `list` and `create` on
  `wfrn:<partition>:resource-groups:<region>:<account>:group/*`, `get` (the group and its
  resources), `update` and `delete` on the group's wfrn. A refusal is a 403 with a Denial, so with
  the managed roles a viewer reads, an operator also creates and changes, and only an admin
  deletes. A delete needs a fresh sign-in, as every `*:delete` does (`console.auth.step-up`).
- A group of another account or region, or an id that is no group id, is a 404.
- A body's problems are a 400 with `problems`, each at its pointer: `required` or `group.name` at
  `/name`; `length` at `/description`; `type.unknown` at `/query/types/<i>`, naming the type;
  `group.query.tags-required` at `/query/tags` when the query names no tag, `length` there over
  20; `tag.name` or `tag.value` at `/query/tags/<name>` (the name escaped as a JSON pointer).
- A name the account and region hold already is a 409 `group.name-taken` with `detail.name`, in
  the shape of the other coded conflicts (`error`, `message`, `problems`, `detail`).
- `POST` honours `Idempotency-Key` (at most 255 characters): the same person's repeat with the
  same body within 24 hours answers 201 with the first group and `Idempotent-Replay: true`; a
  repeat with another body is a 409 `idempotency.key-reused`.
- `PATCH` replaces the description, and the whole query, when sent; the query is checked as on
  create. A `name` other than the group's own is a 400 `group.name-immutable`. `If-Match`
  (`"<version>"`; `W/` and `*` pass) against a group that moved on is a 409 with
  `detail.version`; without it the change applies to the current version. A change that changes
  nothing keeps the version.
- `/resources` reads the state service as the person, as a resource list without `state` does:
  the resources of the group's types that carry every tag of its query, in the state service's
  order. One type goes to the state service as its filter; with several or none the page is read
  without one and keeps the group's types. Rows of a type the person may not list
  (`<service>:list` on the type's pattern) are left out, so a page can hold fewer rows than
  `limit` (1 to 200, 50 by default) while `next`, the state service's cursor, goes on.

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
`resources.<type>` for every resource type of the catalog (its `metadata.name`, as the state
service enforces it). Viewers, operators and admins may ask for
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
