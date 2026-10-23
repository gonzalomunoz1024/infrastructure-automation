# console-search-indexer

> Appendix to HANDOFF.md. Engineering notes recorded by the build sessions on 2026-10-23 (UTC), copied as written. "Thread" means one of the parallel build sessions; their names are listed in HANDOFF.md section 2. Commits and PR heads named here were current when written: always re-check with `git ls-remote` and the PR page. `[[name]]` links point to another note in this folder set.

---

## Note: console-search-indexer


Built 2026-10-23 (thread "Search indexer"). Gradle, Java 21, WebFlux, hexagonal slice `com.iaf.console.search.index`; libs `com.iaf.console:*` via mavenLocal. Stack: PR #10 (`console-search-indexer--phase-6` → orphan root `console-search-indexer`, WP 6.1), PR #29 (`console-search-indexer--phase-9` → phase-6, opened 2026-10-23 ~16:00 UTC). Hub contract stack: PR #11 (`console-root--search-indexer-contract`, 1.1.0) → PR #20 (`console-root--search-copilot-contract`, 1.2.0, head b0a0d52). Phase 9 pins hub b0a0d52 in `contracts.lock`; PR #10 still pins 4e92bfb.

Serves on 18085: `/v1/search` (type:/state:/region:/tag:k=v/drift:/created:/updated: terms, wfrn patterns, seven sorts, cursors, `didYouMean`), `/v1/suggest`, `/v1/counts` (`groupBy=type|service|state|region|account|tag:<name>`, largest 100 groups; internal callers repeat `accountId` up to 200, summed in the application one scope at a time because ArchUnit forbids multi-account port reads), `GET /v1/search:interpret` (deterministic plain-English reader for the copilot: type, filter{q,state,drift,region,tags}, sort, explanation, confidence high/medium/low, unparsed, total), `/v1/index/status`, rebuilds and `/internal/sources` (console services only; people get 404). Netty request line raised to 16 KB so 200 accounts fit. Typos: fuzziness AUTO:4,7, prefix 1, boosts below exact clauses on both engines. Auth: BFF ES256 JWT, aud exactly console-search-indexer (decision 35); a token without acct only from allow-listed svc (console.auth.services [console-bff] full, counting-services [drift, cost] /v1/counts only), dev headers same rules; JWKS down → 503. Security-review fixes 2026-10-23 on #35 (081a1bc): actuator on port 18185 in the chart, NetworkPolicy on by default, rebuild/re-read require wfrn/JSON/scope accounts to agree, transfer tombstones drop tags and fields; pinned to hub stack turn 15 (37a99d6). Budget 30/s burst 60 per account per replica → 429 (raised from 20/40 by the load test; chart's 3 replicas = 90/s).

Index: alias `console-resources` → `console-resources-v1` (strict mapping), one doc per wfrn routed by account, `external_gte` versions, tombstones 90 days. Ingestion: `kafka` profile reads state.resources + drift.findings; otherwise polls the state service. Service tokens: [[console-service-tokens]].

Run/test: `CONSOLE_TEST_OPENSEARCH_URL`, `CONSOLE_TEST_KAFKA_BOOTSTRAP`, `CONSOLE_TEST_MONGO_URI` enable adapter tests; without Docker `scripts/opensearch.sh` runs OpenSearch from jars. Phase 9 full build: 151 tests green locally.

Hub hygiene (2026-10-23): YAML flow-mapping descriptions with commas were silently cut into null keys; hub PR #25 (`console-root--contract-descriptions` → phase-0) quotes 34 and makes `npm run check` fail on null entries. Coordinator asked this thread to own it; BFF, state, identity, drift and cost threads told to re-pin and quote their branches' own cut descriptions.

Later stack (2026-10-23): PR #35 `console-search-indexer--transfers` (decision 30: `Transferred` is a tombstone kept for good, the new wfrn an import) → PR #44 `console-search-indexer--load` (WP 6.2 search side: `src/gatling` Estate/SeedEstate/SearchSimulation, `./gradlew seedEstate` / `gatlingRun` with `-Pload.*`, Grafana dashboard `deploy/grafana`, CI "Load smoke"). Load finding: suggest's per-keystroke aggregations over a 1M-doc account saturated OpenSearch; type/tag suggestion counts are now cached per account+type for 30 s (top 1,000 tag values). Local result 1,000 people / 2M docs: 0 failures, p95 16 ms. The state thread plans to add its WP 6.2 scenarios to `src/gatling` (~21:00 UTC). Local gotcha: a sandbox OpenSearch blocks index creation at its disk watermark; set `cluster.routing.allocation.disk.threshold_enabled=false`.

**Why:** the BFF, web (lists, copilot, executive home) and the drift service build on these endpoints.
**How to apply:** see [[customer-console-build]], [[customer-console-bff]], [[console-state-service]], [[console-ci-libs-publish]].

---

## Note: console-service-tokens


Hub decision 23 (console-root--phase-0 1a71005, 2026-10-23): the BFF is the only issuer of internal JWTs. A service calling another on its own asks the BFF's `POST /internal/v1/token` (`issueServiceToken` in console-bff.yaml): form-encoded `grant_type=client_credentials`, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, `client_assertion`, optional `client_id`, `audience` once per callee. Answer: `{access_token, token_type: Bearer, expires_in: 300, audience}`; claims iss console-bff, aud only what was asked, sub = svc = the client id, no acct. Errors are RFC 6749 bodies (400 invalid_request/unsupported_grant_type/invalid_target, 401 invalid_client). The ingress never routes `/internal`.

Assertion: in a cluster the pod's projected service-account token (audience console-bff, expirationSeconds 3600, re-read per request); elsewhere a JWT signed with the service's own EC key (iss = sub = client id, aud console-bff, ≤ 5 min, fresh jti; ES256/ES384/RS256/PS256). The BFF registers clients under `console.trust.clients.<id>` (audiences, service-account `<ns>:<name>`, jwks or jwks-uri); console-search-indexer and console-drift-service are registered for console-state-service and console-identity-service.

Reference client: the search indexer, package `com.iaf.console.search.index.adapters.tokens` on PR #10 (ServiceTokens, ClientAssertion, ServiceCredentials as a WebClient filter): one token per callee, reused for 80% of its life, shared in-flight request, old token served while the BFF is down until 10 s before expiry, one retry after a callee's 401. Each BFF request must have a time limit (5 s, `console.auth.token-timeout`): callers share the in-flight request, so one that never ends stalls every call. Settings `console.auth.token-url`, `token-timeout`, `client-assertion-file`, `client-key`.

E2E recipe (verified 2026-10-23 14:07 UTC): state demo with `--console.auth.dev-headers=false`; BFF demo jar with `--console.platform.fake.enabled=false --console.state.url=http://localhost:18082 "--console.trust.clients.console-search-indexer.jwks=<public JWK>"`; indexer demo with `CONSOLE_AUTH_CLIENT_KEY=<private JWK with kid>`.

**Decision 35 (hub PR #46, 2026-10-23 18:30 UTC, from the BFF security review; supersedes the above where they differ):** every internal JWT has exactly one `aud`, the callee; the shared `console` audience is retired. `/internal/v1/token` takes exactly one `audience` (none or several is `invalid_target`; answer `audience` is a string). Every service refuses (401) a token whose `aud` isn't exactly its own id and accepts a no-`acct` token only from its own allow list of service principals (`console-bff` plus its decision 23 clients). `roles` never older than 5 min or past an elevation's `until`. No transition period.

**Why:** the drift service and any later service must call others the same way; no shared keys or static tokens.
**How to apply:** copy the indexer's client; see [[console-search-indexer]], [[customer-console-bff]], [[customer-console-build]].

