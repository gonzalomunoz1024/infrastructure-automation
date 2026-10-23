# Contracts

The source of truth for every API and event the console services share. See the hub README, "How
services share contracts". Phase 0 drafted them (2026-10-23); `npm run check` at the root of this
branch validates everything here.

## What is here

| Path | What it is | Status |
| --- | --- | --- |
| `platform/openapi-target.yaml` | The platform's HTTP API as it will be after P-01 to P-21 (`platform-changes/README.md`), OpenAPI 3.1: every operation the console uses, with `operationId`, tags, `x-scopes` and `x-platform-change`, the P-02, P-04, P-13 and P-21 headers as reusable parameters, opt-in paging with a `Link` header, the generated-route convention and the sample workflows' routes as concrete paths | the contract the console is built against |
| `platform/asyncapi-target.yaml` | The platform's event feed after P-01, AsyncAPI 3.0: the envelope, the four topics with partition keys and retention, and a `data` schema for every event type of the first catalog plus P-19's `content.published` and the audit topic's events | the contract the console's consumers are built against |
| `platform/used-operations.txt` | One operation id per line of every target operation the console uses; the nightly check reads it | kept in step with the target |
| `platform/openapi-c9529d5.json` | The platform's own OpenAPI document captured at `c9529d5`, read-only, for reference and for the local run of the platform check | pinned |
| `platform/sample-content/` | The platform's `resource-types/`, `providers/` and `workflows/` at `c9529d5`, read-only; the overlays merge over these types and name these providers' actions | pinned |
| `asyncapi/console-events.yaml` | The console's own topics, AsyncAPI 3.0: `state.resources`, `drift.findings`, `identity.changes`, with partition keys, deterministic keys and the resource state machine | draft |
| `openapi/console-bff.yaml`, `console-identity-service.yaml`, `console-state-service.yaml`, `console-drift-service.yaml`, `console-search-indexer.yaml` | One OpenAPI 3.1 file per service, `x-status: draft`: the agreed paths and names, the account scope rule, cursor paging, the error shape and the wfrn-in-URL rule; field lists will grow | draft |
| `schemas/` | JSON Schema (draft 2020-12) for `kind: ResourceType` as P-06 shapes it, `kind: Region` (P-07), the hub's `kind: TypeOverlay`, and the wfrn fixtures file | settled for phase 0 |
| `wfrn/SPEC.md`, `wfrn/fixtures.json`, `wfrn/reference.mjs` | The wfrn specification, the fixtures every wfrn library runs, and the JavaScript reference that passes them (`node contracts/wfrn/reference.mjs contracts/wfrn/fixtures.json`) | settled |

## The rules every service contract follows

- **Account scope.** Every path under an account is scoped by the caller's account (the `acct`
  claim of the console's internal JWT, or the BFF's session). A record of another account answers
  404, never 403; 403 is only ever answered within the caller's own account, by its policies.
- **Paging.** Lists answer `{ items, next }`: `next` is an opaque cursor, null on the last page,
  sent back as `after`; `limit` is bounded per service. The platform's own lists page differently
  (an array body and a `Link` header, P-05), and the BFF hides that difference.
- **Errors.** `{ error, problems?, correlationId? }`, the platform's shape: `problems` carry the
  field as a JSON pointer so a form can mark it, `correlationId` finds the cause in a log.
- **wfrns in URLs.** A wfrn holds `:` and `/`, so in a path it is percent-encoded
  (`/v1/wfrn/wfrn%3Awf%3A...`), and where the platform uses a query parameter (`?wfrn=`) the
  console does too. A path never serves two shapes under one template, which is why the BFF's
  detail route repeats the type: `/console/v1/r/{type}/{wfrn}`.
- **Calls between services.** Every console service verifies the BFF's internal JWT against the
  BFF's JWKS and nothing else. A call behind a person carries the token the BFF minted for the
  session (`acct`, `groups`, `roles`); a service calling on its own carries a service token the
  BFF issued it at `POST /internal/v1/token` (no `acct`; `sub` and `svc` the caller's service id),
  for which it authenticates with its Kubernetes service-account token or its own registered key
  (decision 23). No service holds the BFF's signing key or a static token.
- **Idempotency.** A command takes `Idempotency-Key`; the same key with the same body answers the
  first result, with another body it is 409.
- **Events.** Consumers de-duplicate platform events on `key`, never `id`; console topics are
  partitioned by wfrn (identity changes by account), and every console event carries the
  subject's `version` so a consumer can tell what it has applied.

## How a service pins this branch

A service never copies a contract by hand. It records the hub commit it builds against in a
`contracts.lock` file at its root, and its build fetches the files from that commit and refuses to
build when a hash differs:

```yaml
# contracts.lock
hub: https://github.com/gonzalomunoz1024/infrastructure-automation
branch: console-root
commit: <the full hash of the hub commit the service builds against>
files:
  contracts/platform/openapi-target.yaml: sha256:<the file's sha256 at that commit>
  contracts/platform/asyncapi-target.yaml: sha256:<...>
  contracts/openapi/console-state-service.yaml: sha256:<...>
  contracts/wfrn/fixtures.json: sha256:<...>
```

`commit` is the full hash; `files` lists every contract the service reads with the sha256 of its
content at that commit (`git show <commit>:<path> | sha256sum`). Moving to a newer hub commit is a
pull request on the service that changes the lock and whatever the new contract asks for.
`console-libs` pins the same way and publishes the shared code with a version; a service depends
on a published version, never on a branch.

## Versions and breaking changes

- A change that adds an optional field, parameter, header, operation or event type is compatible:
  it lands in place, the version in `info.version` grows, and consumers pick it up when they move
  their lock.
- A change that removes or renames something, makes a field required or changes a type is
  breaking: it is a new version of that API (`/v2/...`, `info.version` 2.0.0) or a new event type
  (`job.finished.v2`), and both versions are served for one release while consumers move.
- The platform's target files follow the platform's own rule: additive to today's API, every new
  thing annotated with the change that brings it (`x-platform-change`), so the nightly check can
  tell pending from diverged. When the platform ships a change differently from the target, the
  target changes to match the platform and the services follow through their locks.

## Running the checks

```sh
npm ci
npm run check                                   # every file here, the overlays and the fixtures
npm run check:platform -- --from contracts/platform/openapi-c9529d5.json   # the target against the capture
node scripts/platform-contract-check.mjs --from http://localhost:8080/api/v1/openapi.json --header 'X-User: gonzalo'   # against a running platform
```

`npm run check` fails on any problem; the platform check fails only when something the console
uses has diverged from the target, and prints `landed`, `pending` or `diverged` per operation,
grouped by platform change. CI runs both on every push and pull request, and the platform check
nightly against a freshly built platform `main` (hub README, "How services share contracts").
