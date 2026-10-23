# What this service serves beyond the pinned contract

Nothing, at hub `4e92bfb` (`contracts.lock`). The additions the BFF asked for while it built the
console's resource lists on 2026-10-23 (`GET /v1/counts`, the `drift` parameter, the seven sorts,
HTML-safe highlights, the status scoped to the caller's account, `GET /internal/sources`, the 404s
for people on the index's own surface, 503 when the BFF's keys cannot be read) are now in the hub's
`contracts/openapi/console-search-indexer.yaml` 1.1.0, through hub PR #11.

A handler this service adds before the hub has it is described here, and named in
`RoutesMatchContractTest`'s additions until the hub catches up.
