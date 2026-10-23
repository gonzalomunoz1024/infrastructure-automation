# Additions to the pinned contract

Handlers this service serves beyond `contracts/openapi/console-cost-service.yaml` at the commit
`contracts.lock` pins: none. The contract came with the service (hub PR #18), so every route is in it.

## Events the ledger follows beyond the pin

Moves between accounts, from hub PR #27 (decision 30, `console-root--state-phase-9`), which the
state contract at the pinned commit does not have yet:

- `Transferred` on `state.resources`: the old wfrn, `data.state: deleted`, with `transferredTo`,
  `toAccountId` and `registryId`. `GET /v1/wfrn/{wfrn}` then answers 410 `resource.transferred`
  (`Tombstone` with `transferredTo`, `deletedAt` the move); the ledger ends the old wfrn's rate there
  as for a delete, at the `at` of the `Transferred` when the 410 gives no time.
- `Imported (source transfer)`: the new wfrn in the other account, with `transferredFrom` (on the
  event and on `ConsoleResource`), the same `registryId`, the attributes and the tags; its
  `createdAt` is the import, where its rate starts.

The copies under `src/test/resources/contracts` stay at the pinned commit; `contracts.lock` moves
once that change is merged into the commit this service pins.
