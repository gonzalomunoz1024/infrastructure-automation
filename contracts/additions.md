# Endpoints beyond the pinned contract

`contracts.lock` pins the hub's contracts (console-root--stack at turn 10, e7f1404: the hub's merge order through #19, which carries #15, #17, #18 and #19). The web
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
- `GET /r/{type}/{wfrn}` answers the ETag `"<version>.<digest>"` (the state version, then a digest of the whole page) and honours `If-None-Match` (304), so a 304 never hides a last job, jobs, lineage or verbs that moved on;
  `PATCH` honours `If-Match` (the page ETag or the bare version) against the resource's `version` (409 when it moved on).
- `410` carries the tombstone as `detail` (`wfrn`, `deletedAt`, `tombstoneUntil`, `lastVersion`);
  `/activity` stays readable for a deleted resource.
- A destructive verb (`delete` and anything the catalog marks destructive) needs `confirm: true`
  (400 `confirm.required` otherwise) and a fresh sign-in (`console.auth.step-up`).
- `POST /r/{type}:batch` answers 400 `bulk.unsupported` for a verb without a batch route and
  400 `required` for a row without `wfrn` on a verb other than create.
- A batch on existing resources decides `<service>:<verb>` on every row's own wfrn, as the single
  verb does, before anything starts (a create batch decides `<service>:create` on the type's
  pattern, as a single create does). One refused row refuses the whole batch and nothing starts:
  403 with the first refused row's `denied` (its `resource` that row's wfrn) and `deniedRows`, every
  refused row as `{ index, wfrn }` in batch order. A row naming another type, account or region is a
  404, as on the single verb.

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
- Retry with fix: the optional body `{ input?, fromStep? }` merges `input` over the job's input
  (as the platform's `:rerun` and `:retry` do; an update's recorded change is the merged one) and
  runs again from `fromStep`. 400 `step.unknown` at `/fromStep` for a step the job does not have,
  `retry.fix-unsupported` for a batch (its rows run again as they were), `retry.name-fixed` at
  `/input/name` for a create's new name (the minted name stays).
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

**Tag Editor (WP 4.3).** As AWS's, in the session's account and region:

- `GET /console/v1/tags/resources?type=&tag.<name>=&limit=&after=` (`type` repeated or comma
  separated, every type when left out) → `{ items: [ConsoleResource + canTag], next }`: the
  resources that carry every named tag, in the state service's order; `canTag` is
  `<service>:tag` on the resource. A type the person may not list is left out, so a page can
  hold fewer rows than `limit` while `next` goes on. An unknown type is a 400 `type.unknown` at
  `/types/<i>`.
- `POST /console/v1/tags:apply { resources: [wfrn] (1 to 100), set: { name: value }, remove:
  [name] }` → 200 `{ applied: [{ wfrn, tags }], failed: [{ wfrn, error, message, denied? }] }`.
  Each resource is its own state service command, as `<service>:tag` on it allows; the other
  tags stay. `failed.error` is `denied` (with the Denial), `not-found` (another account or
  region, or no such name), `gone`, `conflict`, `invalid` or `error`, in the request's order.
  `Idempotency-Key` applies per resource.
- What the body gets wrong is a 400 before anything changes: `required` at `/resources` or
  `/set`, `tags.too-many-resources` at `/resources`, `tag.name` / `tag.value` at
  `/set/<name>`, `tags.conflict` (set and removed), `tags.invalid` at `/set/<key>` for a
  required tag's value, `tags.required` at `/remove/<i>` for a required tag.

**Launch more like this (WP 4.6).** `GET /r/{type}/{wfrn}/clone?account=` (the session's account
when left out; another account of the person's, else 404; as `<service>:get` on the resource
allows; 410 for a deleted one) answers the type's create form filled from the resource:

```yaml
CloneDraft:
  type: string
  workflow: string        # the create workflow
  source: { wfrn, name, accountId, region }
  accountId: string       # the target account
  input: object           # the create's fields: current attributes, else what it was created with;
                          # secrets left out, the name suggested as <name>-copy when it fits the field
  tags: { name: value }   # the source's; an account default (owner, cost-centre) becomes the target's
  notes: [{ field, code, message }]   # secret (not copied), name (choose one), reference (a resource
                                      # of the source account, when the target is another)
  targets: [{ accountId, name, current, allowed, denied? }]   # every account of the person's,
                                      # allowed = <service>:create on the type there
  tagPolicy: TagPolicy    # the target account's
```

Nothing is created: the wizard opens with the draft and creates as usual (`POST /r/{type}` with
`input` and `tags`); to create in another account, the console switches to it first.

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

## Search: plain English and grouped counts (phase 9)

Hub PR #16 (decision 27; see Phase 9 below) adds two search operations over the search indexer
1.2.0. They are not in the pinned contract; this service serves them with that branch's shapes
and with these details its text does not spell out:

`GET /console/v1/search:interpret?q=&type=` passes the indexer's `GET /v1/search:interpret` for
the session's account through, asked as the person, and the reading comes back as the indexer
answered it (`SearchInterpretation`; the indexer's `sort` can also be `score`, which PR #16's
enum leaves out).

- `q` is required and at most 500 characters (400 with a problem on `/q`); the indexer reads 256
  at most, and its 400 passes back with its problems, its 429 with `Retry-After`.
- With `type`, the person must be allowed to list the type (`<service>:list` on the type's
  pattern) before the indexer is asked: one they may not list is a 403 with a Denial, as its list
  gives it, and an unknown type a 404. PR #16's operation lists neither.
- A reading that names a type the person may not list is refused the same way.
- A reading of no type counts every type. When the person may list some types but not all, its
  `total` is counted again over the ones they may (the indexer's `/v1/counts` with the reading's
  filter and `groupBy=type`); when they may list none, `total` is 0.

`GET /console/v1/search:counts` counts resources by one grouping, with the filters of a search:

```yaml
parameters:
  groupBy: type | service | state | region | account | tag:<name>   # required; 400 at /groupBy otherwise
  scope: account | organization   # account when left out; 400 at /scope otherwise
  q: string                       # at most 500 characters, in the indexer's syntax
  type: string                    # one type; an unknown one is a 404
  state: string                   # one of the six states; 400 at /state otherwise
  drift: true | false             # 400 at /drift otherwise
  tag.<name>: string              # one per tag, an empty value any value; a name is [a-z0-9-]{1,32} (400 at /tag.<name>)
200:
  total: integer
  byState: { pending, active, updating, deleting, deleted, failed }   # every state, in this order
  drift: integer
  cappedAt: integer               # only when a count stopped at a cap
  groups:                         # at most 100, the largest total first, then by key
    - key: string                 # the type, service, state, region, account id or tag value
      total: integer
      byState: { ... }            # as above
      drift: integer
      service: string             # groupBy=type only
  asOf: date-time                 # the indexer's lastEventAt (/v1/index/status); absent when it cannot say
```

- Everything is counted in the session's region, so `groupBy=region` has one group.
- `scope=account` counts the session's account, asked as the person. With a type, the person
  must be allowed `<service>:list` on it; without one, on every type of the account's catalogue.
  A refusal is a 403 with a Denial, as the list gives it; PR #16's operation lists no 403.
- `scope=organization` counts every account of the person's that is not closed and in which they
  may list what is counted, each decided as for `account` with the session switched to that
  account; an account whose catalogue does not hold the type counts nothing. The counts are asked
  as the console-internal principal with `accountId` repeated, at most 200 accounts a call, and
  added up here, so the person's own token never names another account. Only when no account
  counts is the read refused: with the session's account's refusal (403), or a 404 for a type no
  account holds.
- `asOf` is asked as the person in the session's account; when that call fails, the counts are
  answered without it.
- The indexer's 400 (such as a tag name in `groupBy` it does not take, `tag:Cost.Centre`) passes
  back with its problems, and its 429 with `Retry-After`; counts over several accounts spend each
  account's query budget.

`GET /console/v1/search` also passes on the indexer's `didYouMean`, the query with a word found
only a typo away written as the hits spell it. It is read from the page's hits, so when hits of
types the person may not list were left out it passes only if each word it changes is in the
name or a tag of a hit that is shown; otherwise it is absent.

The console's own callers (the executive home, the copilot) use the search application instead:
`Search.countsAcross(session, accountIds, filters, groupBy)` counts accounts the caller has
already decided on, as the console-internal principal and without asking a policy, and
`Search.lastEventAt(session)` is the time of the index's last event.

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

## Cost and budgets

Served as the pinned contract says (hub PR #18), through the cost service, with these details
the contract does not spell out yet:

- Cost is decided on `wfrn:<partition>:cost::<account>:*`; a budget on
  `wfrn:<partition>:budgets::<account>:budget/<id>`, its id's underscore written as a hyphen
  (`bud_01j…` is `budget/bud-01j…`, a wfrn id takes no underscore), and the list and create on
  `…:budget/*`. A resource's cost is decided on the resource's own wfrn.
- `GET /console/v1/cost/chargeback` (JSON) asks `cost:list`, the CSV `cost:export`. With
  `scope=organization` each reads the accounts where its action is allowed; the JSON names the
  others in `skippedAccounts`, the CSV in the `X-Console-Skipped-Accounts` header (in the pinned
  contract). With no account allowed, the read is a 403, not an empty report.
- `PUT /console/v1/budgets/{budgetId}` without `If-Match` is a 400 (`required` at `If-Match`)
  before the cost service is asked; `DELETE` needs a fresh sign-in, as every `*:delete`.
- A budget create naming another account in `scope.accountId` is a 400 (`scope.account`); left
  out, the session's account is filled in.
- A create (`POST /r/{type}`, and a batch create) refused by a budget is a 409
  `budget.exceeded` (in the pinned contract) with `detail.budgets: [{ budgetId, name, message }]`; a batch preview's row
  carries the problem `budget.exceeded` at `/items/<i>/tags`, and its policy each budget's rule
  with `budgetId`. The `quota.resources` rule the pinned `Preview.policy.rules` description names
  is not added by the BFF yet (the state service enforces the quota on mint).

## Scorecards (from hub PR #19, folded in when it merges)

Not in the pinned contract: hub PR #19 (console-root--scorecards-contract) adds them, and this
service serves them with that branch's shapes until the pin moves past it.

```
GET  /console/v1/scorecards?days=                                   -> Scorecard (drift: GET /v1/scorecards/account)
GET  /console/v1/scorecards/packs                                   -> { items: [RulePackSummary] }
GET  /console/v1/scorecards/heatmap                                 -> ScorecardHeatmap, each row with accountName
GET  /console/v1/scorecards/packs/{pack}/rules/{ruleId}/failing?type=&limit=&after=  -> { items: [Evaluation], next }
GET  /console/v1/r/{type}/{wfrn}/compliance                         -> ResourceCompliance (drift: GET /v1/scorecards/resources?wfrn=)
POST /console/v1/scorecards/packs/{pack}/rules/{ruleId}:remediate   { wfrn, input?, reason? } -> 200 | 202 Remediation
```

- Every read asks `scorecards:list` on `wfrn:<partition>:scorecards::<account>:*` (403 with a
  Denial; PR #19 lists no 403 on `/packs`); the compliance page also the resource's own
  `<service>:get`. A pack or rule id outside `^[a-z][a-z0-9-]{0,62}[a-z0-9]$` is a 404.
- The heat map decides `scorecards:list` in each of the person's accounts and reads the allowed
  ones (at most 500) with the BFF's service token (`?accountIds=`, comma separated); none
  allowed is `{ packs: [], rows: [] }`.
- A fix asks `scorecards:remediate` on the resource's wfrn and the fix its pack describes:
  `<service>:tag` for a tags fix, `<service>:<verb>` for a workflow fix (with its step-up rule).
  The BFF relays the person's `X-On-Behalf-Of`, `Idempotency-Key` and `X-Console-Request-Id`; the
  drift service's 200, 202, 400 (`remediation.input`) and 409 (`evaluation.passing`,
  `remediation.none`, `remediation.unavailable`, `remediation.needsPerson`, `resource.inFlight`)
  come back as they were. PR #19's 409 list leaves out `remediation.needsPerson`.

## Drift

Drift, the shadow-infrastructure finder, monitoring and rightsizing (`/console/v1/findings…`,
`/discover…`, `/unmanaged…`, `/alarms…`, `/recommendations…` and a resource's `/drift`,
`/metrics` and `/alarms`) are served as the pinned contract says, through the drift service
(`console.drift.url`, audience `console.drift.service-name`, `console-drift-service` by default,
within `console.drift.timeout`, 5s by default), for the session's account and region, with these
details the contract does not spell out yet:

- Every list asks the policies once per page, for every row at once: a row of a type the person
  may not list (`<service>:list` on the type's pattern), of another account or region, or (the
  unmanaged objects) of a type the region does not know is left out, so a page can hold fewer
  rows than `limit` (1 to 200, 50 by default) while `next`, the drift service's cursor, goes on.
  With `type`, a type the region does not have is a 400 `type.unknown` at `/type`, and one the
  person may not list a 403 with a Denial, as that type's own list is.
- `can` is `[]` on a closed finding and on a recommendation that is not open. On a list,
  `Finding.can` follows `<service>:update` on its wfrn, `UnmanagedObject.can` (`import`,
  `assign`) `<service>:create` on its type's pattern, and `Recommendation.can` `apply` as the
  action's verb allows (`update` for a resize, `delete` for a delete) and `dismiss` as
  `<service>:update` does.
- `/r/{type}/{wfrn}/drift` asks `<service>:get` on the wfrn and answers `{ items }`, the
  resource's open and closed findings (the latest 200). It, `/metrics` and `/alarms` check the
  wfrn as every resource sub-route does: one that is not a wfrn, of another type than the path's,
  or of another account or region, is a 404.
- A revert and an apply also pass the step-up policy (`console.auth.step-up`) for the verb they
  run: with the default (`*:delete`) only a delete asks for a fresh sign-in, but a policy that
  names `*:update` covers reverts and resizes too. A delete apply is checked in this order:
  `<service>:delete`, the fresh sign-in, then `confirm: true`.
- `PUT /alarms/{alarmId}`: `alarmId` is 1 to 64 of `[A-Za-z0-9_-]` and `name` at most 128
  characters (400 otherwise); `wfrn` is required and must name the session's account and region
  (400 when it is not a wfrn, 404 otherwise). Moving an alarm to another resource also needs
  `<service>:update` on the one it watched. `DELETE` reads the alarm first, so one of another
  account or region is a 404. `GET /alarms` with `wfrn` takes a wfrn (400 otherwise).
- `GET /discover/{runId}` answers any member of the account; a run of another region is a 404.
  `POST /discover` needs `type` (400) of the region's catalogue (404). While a run of the type
  and region goes on, a second start is a 409 `discover.running` with the running run's `runId`
  in `detail`, so the web app can follow that run instead (a run the drift service has not seen
  finish within two hours lost its walk and no longer blocks one).
- Inputs are checked before the drift service is asked: the enums (`status`, `severity`,
  `owner`, `state`, `kind`), the date-times (`openedAfter`, `since`, `until`, `from`, `to`), a
  window whose start is before its end (`since` before `until`, `from` before `to`), `step` as
  an ISO-8601 duration of a minute at least, `until` of an ignore or a dismissal in the future,
  `reason` at most 1000 characters, `note` at most 500, `accountId` 12 digits; each is a 400
  with its problem at `/<field>`.
- The drift service's refusals pass on: a 400 with its `problems` (the drift service names a
  field, `accountId`; the BFF's problems point into the body, `/accountId`); a 403 (the drift service checks a person's writes too, so a policy that changed after the BFF's own check); a 404; a 409 as the BFF's
  coded conflict (`error` the drift service's code, such as `finding.reverting`, `message` the
  sentence, `problems`, and `detail` as the drift service gave it, a revert's `jobId` for one);
  a 429 with `Retry-After`; and a 502 with the drift service's `correlationId` (the platform's)
  and `detail`.
- Every write forwards `Idempotency-Key` and sends `X-Console-Request-Id` (`req_` and the
  request's correlation id); a revert and an apply send `X-On-Behalf-Of`, the person's P-02
  assertion, since their jobs run for the person. A decision, an assignment and an alarm carry
  the session's user id as `actor`.
- `ConsoleType.observe.metrics` is true when the type's observe block has a metrics read that
  names its action (P-09), as `/metrics` needs.
- The managed roles the BFF's demo decides with allow `<service>:observe` to every role, as the
  identity service's managed policies do, so a viewer reads metrics and starts a discovery.

The demo profile (`console.drift.url: ""`) answers from an in-process stand-in under
`/demo-drift`: findings on the demo's virtual machines and the orders database (two of them closed
before the demo began), two virtual machines no account manages with their owner guesses and
monthly costs, made-up metrics for every virtual machine (`console.types.demo-metrics: [vm]`, as
the demo platform's `vm` has no metrics read) and alarms over them, and two recommendations, one
idle (delete) and one oversized (resize). A revert's job "runs" for five seconds, a discovery run
ends at its first read, and no platform job runs: the job ids are the stand-in's own. For the
console-internal principal it answers `GET /demo-drift/v1/findings/stats` with or without
`accountId` (without: every account).
## Phase 9 (from hub PR #16, decision 27, folded in when it merges)

Not in the pinned contract: hub PR #16 (console-root--phase-9-web, at 1bda04e) adds these six
operations, and this service serves them with that branch's shapes until the pin moves past it.

```
GET  /console/v1/insights?period=&scope=                     -> Insights
GET  /console/v1/jobs/{jobId}/explanation                     -> JobExplanation
```

- `insights:get` is decided on `wfrn:<partition>:insights:*:<account>:*` in each account covered;
  every managed role reads it. `period` is `7d`, `30d` (the default) or `90d`, `scope` is
  `account` or `organization` (the default); anything else is a 400 (`period.invalid`,
  `scope.invalid`). None of the accounts allowed is a 403 with the session account's Denial.
- `Insights.drift.partial: true` (not in PR #16) says the drift service did not answer for one
  of the accounts, the numbers then covering the others.
- `Insights.from` is the period's start to the minute (the search indexer's time terms are to the
  minute); `to` and `asOf` are now.
- A rerun the console starts (a retry, with or without a fix) carries the platform job label
  `retry-of: <failed job id>`. The executive home counts such a request as one a person stepped
  in on, and the explainer finds the retry that fixed a failure by it.
- `JobExplanation` answers 404 for a job of another account and 409 `job.not-failed`
  (`detail.status`) for a job that is neither `failed`, `rolled-back` nor `needs-attention`.
  `status` is `needs-attention` for a paused job, `failed` otherwise.

## Copilot (phase 9)

Hub PR #16 (decision 27; see Phase 9 above) adds the copilot's two operations. They are not in
the pinned contract; this service serves them with that branch's shapes and with these details
its text does not spell out. The copilot reads only what the person may read with their own
session and never acts: what it proposes, the console opens for the person to review and run as
usual.

```
POST /console/v1/assistant/turns   AssistantAsk  -> 202 { conversationId, turnId }
GET  /console/v1/assistant/conversations/{conversationId}  -> 200 AssistantConversation
```

```yaml
AssistantAsk:
  conversationId: string    # optional: a new conversation when left out
  intent: ask | explain
  message: string           # required for ask; at most 2000 characters
  context:                  # where the person is; optional for ask
    path: string            # the console route, kept only when it starts with /console/
    service: string
    type: string
    wfrn: string            # required for explain: the resource to explain
    filter: SearchFilter    # the list's filter on the page

AssistantConversation:
  conversationId: string    # conv_<ulid>, lowercase
  status: thinking | ready | failed   # the last assistant turn's: under way, answered, failed
  startedAt: date-time
  turns: [AssistantTurn]    # oldest first

AssistantTurn:
  turnId: string            # turn_<ulid>, lowercase
  role: person | assistant
  at: date-time             # when the turn reached its status
  status: thinking | answered | failed   # assistant turns only
  text: string              # the person's words, or the answer in plain sentences (no markup)
  proposals: [AssistantProposal]          # at most 5; left out when none
  explanation: ResourceExplanation       # explain turns
  error: { code, message }  # failed turns: assistant.unavailable | assistant.timeout | assistant.refused

AssistantProposal:          # what the console opens; nothing runs until the person confirms it there
  kind: filter | create | action | open
  label: string
  service: string           # filter, create, action
  type: string              # filter, create, action
  filter: SearchFilter      # filter
  sort: name | -name | created | -created | updated | -updated   # filter, optional
  input: { <field>: value } # create, action: the form's fields the question gave
  missing: [string]         # create, action: the form's required fields still empty
  wfrn: string              # action
  verb: string              # action
  path: string              # open: a route under /console/

ResourceExplanation:
  wfrn: string
  summary: string
  sections: [{ title, text }]
  facts:                    # read by the BFF itself, never taken from the assistant
    monthlyCost: number     # the cost service's, left out when it answered none
    currency: string
    createdAt: date-time
    createdBy: string
    lastChangeAt: date-time
    lastChangeBy: string
    dependents: integer     # the blast radius's total, in every account
    drift: boolean

SearchFilter: { q, state, drift, region, tags: { <name>: <value> } }   # an empty value: has the tag
```

- The turn is answered in the background: `POST` stores the person's turn and an assistant turn
  `thinking`, answers 202, and the console polls the conversation until that turn is `answered`
  or `failed`. A turn not answered within `console.assistant.turn-timeout` (5 minutes) fails
  `assistant.timeout`; one still `thinking` 30 seconds past that (its instance stopped) reads as
  failed with that code. The assistant unreachable or erring is `assistant.unavailable`; a
  refusal, or an answer with nothing the console can show, `assistant.refused`.
- Every answer is reviewed against the person's own permissions before it is stored, and what
  fails is dropped and logged: at most five proposals, of the four kinds; a `filter` over a type
  the person may list (`<service>:list` on the type's pattern), reduced to `SearchFilter`'s fields
  and values; a `create` of a type the person may create (`<service>:create`) with its input
  reduced to the create form and `missing` worked out from it; an `action` on a resource of the
  session's account and region that is there, with a verb the person may run on that wfrn; an
  `open` of a route under `/console/` (no `..`, no scheme, no host). No proposal fills a secret
  field. Text, labels and sections lose their HTML and Markdown, and a sentence stating a cost
  other than the cost service's for the resource is dropped.
- What the assistant gets is gathered with the person's session: the account and region with the
  person's managed roles, the types the person may list or create with their create forms and the
  verbs the person may run (forms without secret fields), the resources the question names, the
  page's context, the conversation's last ten turns, and for `explain` the resource's detail,
  activity (created, last change), blast radius, open drift finding and, through
  `Costs.monthlyCost`, its monthly cost. The assistant never gets a credential and nothing in what
  it gets lets it call the console.
- 400 with `problems` (each at its `field` pointer): `required` or `enum` at `/intent`,
  `required` or `length` (over 2000 characters) at `/message`, `required` at `/context/wfrn` for
  `explain`, `conversation.full` at `/conversationId` once a conversation holds 50 questions.
- 404: a conversation that is another person's, of another account or region, or started more
  than `console.assistant.conversation-ttl` (24 hours) ago; a `context.wfrn` that is no wfrn or of
  another account or region; for `explain`, a resource that is not there (one the person may not
  read is the 403 with a Denial its own page answers).
- 429 with `Retry-After` (seconds) and `retryAfter` past `console.assistant.turns-per-minute` (10)
  turns of the person in any minute, across instances with the `mongo` profile. The failure
  explainer's readings (below) count too.
- The write needs the CSRF token, as every write under `/console/` does.
- `GET /console/v1/jobs/{jobId}/explanation` (WP 9.2) asks the copilot about a failure class no
  rule knows: the cause's title and the fix (with an input reduced to the workflow's form) are the
  copilot's, the cause's detail stays the platform's message, and `source` is `assistant`. The
  explanation waits `console.assistant.failure-wait` (5 seconds) for the reading and answers with
  its rules meanwhile; the reading goes on and the next explanation of the same failure shows it.

## Session

`Session.account` carries `roles` (the managed roles the person holds in the account) and
`groups`.

`Session.decisions` (phase 4) decides the account's own console actions for the person, so the
console greys a control without inferring it from role names. `GET` and `PUT
/console/v1/session` carry it; it is left out when the identity service could not answer (the
session still loads, and a refused call still explains itself).

```yaml
decisions:
  type: object
  description: keyed <kind>:<verb>, always these keys in this order
  additionalProperties:
    required: [allowed]
    properties:
      allowed: { type: boolean }
      denied: Denial          # only when refused: layer, action, resource, reason, role, requestable, suggestedRole
```

| Keys | Decided as |
| --- | --- |
| `user:create`, `user:update`, `user:delete`, the same for `group`, `role`, `policy` and `access-key` | `iam:<verb>` on `wfrn:wf:iam::<account>:<kind>/*` |
| `quota:request-increase` | itself, on `wfrn:wf:iam::<account>:quota/*` |
| `access-request:create`, `access-request:list`, `access-request:decide` | themselves, on `wfrn:wf:iam::<account>:access-request/*` |

Each is decided on every record of its kind, so a policy that allows one record only is not
reflected here; the identity service still decides each call. `access-request:list` is reading
the account's queue (admins, auditors and the groups given it, such as the approver groups);
`access-request:decide` is approving or rejecting (admins and the approver groups), never one's
own request. A person always manages their own access keys and sees their own access requests,
whatever these say.

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
need `X-Csrf-Token` like every other write. A path with a segment that is empty, `.` or `..`, or that holds `%2e`,
`%2f`, `%5c` or `\` (any case), is a 400 (`path.segment`) and never reaches the identity
service; every other segment is decoded once and encoded again before it is forwarded.

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

## Service tokens (hub decision 35)

`POST /internal/v1/token` follows decision 35 rather than the pinned text: `audience` is required
and names exactly one service (none, or more than one, whether as repeated fields or separated by
spaces, is 400 `invalid_target`), the answer's `audience` is that service as a string, and the
token's `aud` is that one service. The client's own assertion must name exactly `console-bff` as
its audience. Every internal token the BFF mints names exactly its callee; `console` is never
minted.
