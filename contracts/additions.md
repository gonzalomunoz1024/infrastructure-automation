# What this service serves beyond the pinned contract

`contracts.lock` pins the hub's `contracts/openapi/console-search-indexer.yaml`. The BFF's thread
asked for the additions below while it built the console's resource lists (2026-10-23); this service
serves them today and the hub should fold them into the contract. `RoutesMatchContractTest` fails
when a handler is neither in the pinned contract nor named here.

## `GET /v1/counts`: a list's numbers

The header and the state chips of a resource list. It takes the filters of `/v1/search`: `q`
(optional here), `type`, `region`, `state`, `tag.<name>`, `drift`, and `accountId` for the console's
own services. Same errors as `/v1/search` (400 with `problems`, 401, 404 for another account, 429
with `Retry-After`), within the same per-account query budget.

```yaml
ResourceCounts:
  type: object
  required: [total, byState, drift, cappedAt]
  properties:
    total:
      type: integer
      description: What the list shows with every filter; tombstones only with state=deleted or an exact wfrn in q
    byState:
      type: object
      description: >-
        Every filter but the state, tombstones included (under deleted), so each chip keeps its
        number while another is picked; every state is present, 0 when empty
      required: [pending, active, updating, deleting, deleted, failed]
      additionalProperties: { type: integer }
    drift:
      type: integer
      description: Of total, the resources with an open drift finding
    cappedAt:
      type: [integer, 'null']
      description: The cap a count stopped at; null, the counts are exact (an account's documents share one shard)
```

## `drift` on `/v1/search`

`drift=true|false` narrows like the `drift:true|false` term of `q`; a parameter and a term that
disagree are a 400 (`/drift`).

## Seven sorts on `/v1/search`

`sort` is one of `score` (the default), `name`, `-name`, `created`, `-created`, `updated`, `-updated`.
Ties go by wfrn. A missing time sorts last both ways; a resource without a name sorts after every
name with `name` and before them with `-name`. A cursor belongs to its sort: one from another sort
is a 400 (`/after`).

## Highlights are HTML-safe

`SearchHit.highlights` holds, per field (`name`, or `text` for a match in the id, the tags or the
searchable fields), the matching text with the match in `<em>` and everything else HTML-escaped
(`&lt;`, `&amp;`, `&quot;`, `&#x27;`, `&#x2F;`), so a page may render it as markup.

## The console's own endpoints answer 404 to people

`POST /v1/index:rebuild`, `GET /v1/index/rebuilds/{runId}` and `GET /internal/sources` are for the
console's own services (a token with `svc` and no `acct`). A person's token gets 404, as for
anything out of their scope, so the endpoints cannot be probed.

## `/v1/index/status` counts the caller's account

The contract's rule that nothing of another account can be counted holds for the status too: a
person's `documents` are their own account's, tombstones included, and another account's
`accountId` answers 404. The console's own services see the whole index, or the account they name
with `accountId`. `lastEventAt` and `lagSeconds` describe the indexer itself: the newest event it
applied and how long ago that event happened, which grows while nothing changes.

## `GET /internal/sources`

Where the events come from, for operators: `[{kind: kafka|poll, running, lastEventAt, lastError, handled}]`.
