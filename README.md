# console-cost-service

The customer console's cost service (hub decision 25): what every console resource costs, priced
from the platform's price lists; the Cost Explorer's queries by account, service, type, region,
tag and cost centre; monthly budgets with forecasts, alerts and an optional block on creates; and
the monthly chargeback that goes to finance. Work packages 7.3, 9.3 and 9.9 of the
[customer console plan](https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC).

The contract is `contracts/openapi/console-cost-service.yaml` on the hub, pinned in
`contracts.lock` (hub PR #18 until it merges) and copied under `src/test/resources/contracts`;
`RoutesMatchContractTest` holds the handlers to it. Port **18086**.

## How a resource's cost is kept

1. **Price.** A type's `cost` binding (P-22) names a price list, a provider action and the
   attribute whose value picks the price (`vm` by `cpu`, `database` by `tier`). The value comes
   from what the provider last observed, else from the desired attributes, so a drifted resource
   costs what it is. Until the platform serves price lists (P-22), `console.pricing.source=bundled`
   reads the copy of the hub's list prices in this jar; the default, `platform`, reads
   `/api/v1/price-lists` and the types' `cost` blocks and re-reads them every 5 minutes. A changed
   price re-prices the resources of the types it touched, from that moment.
2. **Ingest.** Under `kafka` the service follows `state.resources` (and `identity.changes` for cost
   centres) and reads each changed resource from the state service as itself (a BFF service
   token, hub decision 23); without Kafka it polls the state service's lists. An empty ledger is
   filled by a sync at startup; `POST /v1/ledger:sync` re-reads accounts on demand.
3. **Ledger.** One rate record per resource (compare-and-set on `rev`) says what it adds to which
   bucket: an account, region, service, type and exact tag set. A change of rate is one or two
   signed deltas with deterministic ids, stored on the record as pending first and written after,
   so a crash between the two is mended by writing them again. Charged states: `active`,
   `updating`, `deleting`.
4. **Days.** A day closes 30 minutes after it ends into one row per bucket (its opening and closing
   rate and what it cost, accrued by the millisecond at `monthly / days in the month`). A late
   delta marks its day dirty and the closer re-closes from there. Reports read the closed rows
   plus the open days computed from their deltas, so a query is bounded by tag sets, never by
   resources, and never waits for the closer.
5. **Forecast.** Month to date plus the run rate for the rest of the month; the band is two
   standard deviations of the last 14 closed days, widened by √(days left).

Money is micros in a `long` throughout and rounded to cents only at the API edge.

## Budgets

Monthly, for an account or one tag in it, with thresholds (default 80 and 100) that each alert at
most once a month as `BudgetThresholdCrossed` on `cost.events` (the outbox and its key make a
repeat a no-op). `blockCreates` makes `POST /v1/budgets:check` deny a create in the scope while
the budget is over (`budget.exceeded`); a forecast over the amount with the new resources is a
warning (`budget.forecast-over`). The BFF asks before a preview and before the create. `If-Match`
is the version on PUT (428 without it) and DELETE; `Idempotency-Key` answers a retried create.

## Chargeback

Once last month's last day is closed in an account's ledger (and nothing reopened it), the month
is stored final with the account's name and cost centre at the time and its amounts per service
and tag set, `ChargebackPublished` goes to `cost.events`, and finance's export takes the CSV
(`console.chargeback.export`: `log`, or `webhook` with one POST per account and month and an
`Idempotency-Key`). An export that failed is retried every pass. `GET /v1/chargeback` and
`/v1/chargeback.csv` answer a published month exactly as published (with its sha256) and any
other month from the ledger, provisional. Rows split by any tag at read time.

## Layout

Hexagonal slices under `com.iaf.console.cost`: `pricing`, `ledger`, `budgets`, `chargeback` and
`auth`, each `domain` (pure), `ports.outbound`, `application` and `adapters`; `boot` wires them.
The console's ArchUnit rules hold: every account read on a port takes an `AccountScope`.

| Profile | What it changes |
| --- | --- |
| `mongo` | rates, deltas, closed days, ledgers, checkpoints, budgets and their months, published months, the outbox and processed events in MongoDB (else in memory) |
| `kafka` | follows `state.resources` and `identity.changes`; publishes `cost.events` (else polls the state service; events stay in the in-memory outbox) |
| `demo` | dev headers, bundled prices, first sightings spread over 90 days, fast closers |

Every background job (day closer, pending repair, budget evaluator, month closer) is safe on every
replica: each write is a compare-and-set or keyed, so a second replica's pass changes nothing.

## Running it

- `./gradlew build` with Java 21 and console-libs in `mavenLocal` (see CI). The MongoDB store tests
  run when `CONSOLE_TEST_MONGO_URI` names a server (CI starts one).
- `scripts/demo.sh` runs the demo next to the state service's demo; `scripts/demo.sh journey`
  walks the Cost Explorer, an estimate, a budget, a check and the chargeback.
- `deploy/helm/console-cost-service` is the chart; the BFF must register the client
  `console-cost-service` for the pod's service account (hub decision 23).
