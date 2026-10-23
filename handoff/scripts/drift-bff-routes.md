# Drift: the BFF routes for the drift UI and the drift-backed phase 9 pages

From the drift thread, 2026-10-23. The drift service's own API is hub PR #17 (branch
`console-root--drift-finder-monitoring`, `contracts/openapi/console-drift-service.yaml`). The
routes below go into the hub's `contracts/openapi/console-bff.yaml` on the same PR, and are built
on the BFF branch `console-bff--drift` (stacked on `console-bff--phase-1`) with a demo stand-in,
so the demo BFF answers them.

Every route is scoped to the session's account and region, pages as `{ items, next }` (`limit`
1 to 200, `after` the cursor), needs `X-Csrf-Token` on writes and answers 403 with a `Denial`
when the account's policies refuse. A list leaves out the rows of types the person may not list
(`<service>:list`), so a page can hold fewer rows than `limit` while `next` goes on.

## Findings (WP 5.2)

| Route | Answer | Needs |
|---|---|---|
| `GET /console/v1/findings?status=open\|closed\|all&severity&type&wfrn&openedAfter&limit&after` | page of `Finding` | list per type |
| `GET /console/v1/findings/summary` | `{ open, byType, bySeverity, lastScanAt }` | a session |
| `GET /console/v1/findings/stats?since&until` | `FindingStats` | a session |
| `GET /console/v1/findings/{findingId}` | `Finding` | `<service>:get` on the wfrn |
| `POST /console/v1/findings/{findingId}:revert` (`Idempotency-Key`) | 202 `{ findingId, jobId }` | `<service>:update` |
| `POST /console/v1/findings/{findingId}:accept` `{ reason? }` | `Finding`, closed as accepted | `<service>:update` |
| `POST /console/v1/findings/{findingId}:ignore` `{ reason?, until? }` | `Finding`, closed as ignored | `<service>:update` |

The resource page's tab keeps `GET /console/v1/r/{type}/{wfrn}/drift`.

`Finding`: `findingId, wfrn, accountId, region, type, status (open|closed), severity
(info|warning|critical), version, fields[{ path, expected, observed, ignored }], openedAt,
observedAt, closedAt, resolution (reverted|accepted|ignored|vanished), decidedBy, reason,
revertJobId, revertStatus (running|succeeded|failed), ignoredUntil, can[revert, accept, ignore]`.

- A revert runs the type's update workflow on behalf of the person. The finding stays open with
  `revertStatus: running` until the job succeeds and the next read agrees; a failed job leaves it
  open with `revertStatus: failed`.
- 409 codes on the decisions: `finding.closed`, `finding.reverting`, `finding.notRevertable`,
  `finding.unwatched`, `revert.needsPerson`.
- `FindingStats`: `accountId, since, until, opened, closed { total, reverted, accepted, ignored,
  vanished }, open, bySeverity, meanMinutesToClose`. Without `since` the last 30 days. The
  executive home's drift numbers (`found`, `fixed`, `open`) are `opened`, `closed.total` and `open`.

## Shadow infrastructure (WP 5.3)

| Route | Answer | Needs |
|---|---|---|
| `POST /console/v1/discover` `{ type }` | 202 `DiscoveryRun` | `<service>:observe` on the type |
| `GET /console/v1/discover/{runId}` | `DiscoveryRun` | a session |
| `GET /console/v1/unmanaged?type&owner=any\|mine\|unknown&limit&after` | page of `UnmanagedObject` | list per type |
| `POST /console/v1/unmanaged:import` `{ type, providerId, name? }` | 201 `{ wfrn }` | `<service>:create` on the type |
| `POST /console/v1/unmanaged:assign` `{ type, providerId, accountId, note? }` | `UnmanagedObject` | `<service>:create` on the type |

- `DiscoveryRun`: `runId, type, region, status (running|succeeded|failed), startedAt, endedAt,
  seen, unmanaged, error`.
- `UnmanagedObject`: `providerId, type, region, name, observed, seenAt, firstSeenAt, ownerGuess,
  monthlyCost, assignment, can[import, assign]`.
  - `ownerGuess`: `accountId, accountName, team, confidence (high|medium|low), reasons[]`, the
    reasons in plain words ("Tagged team=payments", "The name contains the account's name").
  - `monthlyCost`: `{ amount, currency, basis }`.
  - `assignment`: `accountId, accountName, fromAccountId, by, note, at`. An assigned object shows
    in both accounts' lists until either imports it.
- `owner=mine` keeps the objects guessed to be, or assigned to, this account; `owner=unknown` the
  ones with no guess.

## Monitoring (WP 9.21)

Show the Monitoring tab when the type descriptor has `observe.metrics: true`.

| Route | Answer | Needs |
|---|---|---|
| `GET /console/v1/r/{type}/{wfrn}/metrics?from&to&step` | `ResourceMetrics` | `<service>:observe` on the wfrn |
| `GET /console/v1/r/{type}/{wfrn}/alarms` | `{ items: Alarm[] }` | `<service>:get` |
| `GET /console/v1/alarms?state=ok\|alarm\|insufficient&limit&after` | page of `Alarm` | list per type |
| `PUT /console/v1/alarms/{alarmId}` `AlarmInput` | 201 new or 200 replaced `Alarm` | `<service>:update` on the wfrn |
| `DELETE /console/v1/alarms/{alarmId}` | 204 | `<service>:update` on the wfrn |

- `ResourceMetrics`: `wfrn, from, to, step, series[{ name, unit, points[{ at, value }] }],
  provider, action`. 404 `metrics.notSupported` when the type has no metrics read; 429 with
  `Retry-After` over the observe budget. Defaults: without `from` the last 24 hours; without
  `step` (an ISO-8601 duration, at least `PT1M`) 5 minutes for a range of a day or less, else 1 hour.
- `AlarmInput`: `wfrn, name, metric, comparison (above|below), threshold, windowMinutes (1 to
  1440, 10 by default)`. The client picks `alarmId` (`^[A-Za-z0-9_-]{1,64}$`), so a retried PUT
  is harmless.
- `Alarm`: the input plus `alarmId, accountId, type, state (ok|alarm|insufficient), stateSince,
  lastValue, lastEvaluatedAt, createdBy, createdAt, updatedAt`. Alarms are evaluated every
  minute against the average of the window.

## Rightsizing (WP 9.22)

| Route | Answer | Needs |
|---|---|---|
| `GET /console/v1/recommendations?type&kind=idle\|oversized&status=open\|applied\|dismissed\|superseded&wfrn&limit&after` | page of `Recommendation` | list per type |
| `GET /console/v1/recommendations/summary` | `{ open, byKind, monthlySaving, lastPassAt }` | a session |
| `GET /console/v1/recommendations/{recommendationId}` | `Recommendation` | `<service>:get` |
| `POST /console/v1/recommendations/{recommendationId}:apply` `{ confirm? }` (`Idempotency-Key`) | 202 `{ recommendationId, jobId }` | resize: `<service>:update`; delete: `<service>:delete` |
| `POST /console/v1/recommendations/{recommendationId}:dismiss` `{ reason?, until? }` | `Recommendation` | `<service>:update` |

- `Recommendation`: `recommendationId, wfrn, accountId, region, type, name, kind (idle|oversized),
  action (delete|resize), status, current, proposed, utilisation { from, to, samples, cpuP95,
  cpuMax, memoryP95, memoryMax }, monthlyCost, monthlySaving, jobId, decidedBy, reason,
  dismissedUntil, createdAt, updatedAt, can[apply, dismiss]`.
- A recommendation is `applied` once its job is started; the next daily pass judges the new size
  (and opens a fresh recommendation if the resource is still idle or oversized).
- Applying a delete needs `confirm: true` (400 `confirm.required`) and a fresh sign-in (403 with
  `detail.stepUp`), the same as a destructive verb. 409 codes: `recommendation.closed`,
  `recommendation.notApplicable`, `recommendation.changing`, `revert.needsPerson`.
