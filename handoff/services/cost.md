# console-cost-service

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-cost-service


Built 2026-10-23 in thread "Cost, budgets and scorecards".
- Branch console-cost-service--phase-7, PR #28 (CI green at ed3806e incl. Mongo store tests), port 18086, Mongo db console_cost.
- Pinned to hub 4a016b8 = hub PR #18 (console-root--cost-contract), which is stacked on PR #15 (state-retry, c6c22ab) so the BFF can pin both. Hub decision 25 (cost IAM: cost:list, cost:export, budgets:*), P-22 platform price lists (22 platform changes).
- Money in micros (long); days closed by DayBook/DayCloser; budget thresholds alert once per month to Kafka cost.events (BudgetThresholdCrossed); POST /v1/budgets:check answers deny budget.exceeded / warn budget.forecast-over; chargeback published once per account-month with sha256 and FinanceExportPort (log|webhook).
- groupBy accepts costCentre; executive home spend = BFF /console/v1/cost?scope=organization&groupBy=costCentre (decision 27).
- BFF side: PR #34 console-bff--cost → console-bff--phase-4 (green), adds cost/budgets routes, budget check in preview (fail open, 2 s), budget.alert SSE, scorecards pass-through (pending hub #19), and monthlyCost(session, wfrn) for the copilot (com.iaf.console.bff.cost.application.Costs#monthlyCost). Create denied by a budget = 409 budget.exceeded. Ledger handles account transfers (Transferred/Imported source transfer, hub decision 30).
- Scorecards live in the drift service: hub #19, drift #21, see [[console-scorecards]].
- Gotchas: @SpringBootTest(classes=…) ignores nested @TestConfiguration, use @Import; quote YAML flow-map descriptions containing commas (hub check from PR #25). See [[console-ci-libs-publish]].

