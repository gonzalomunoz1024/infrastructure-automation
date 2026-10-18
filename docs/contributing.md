# Contributing

## Build and run

```bash
# backend (Java 21, Maven)
cd backend && mvn -B -q compile && mvn -B -q test
cd backend && mvn spring-boot:run                       # http://localhost:8080, demo profile
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=demo,postgres   # state in PostgreSQL (docker compose up postgres)

# frontend (Node 22)
npm install                                             # workspaces: packages/ui, packages/workflow-checks, frontend
npm run dev                                             # http://localhost:3000, proxies /api and /ws to :8080
npm run lint && npm run typecheck && npm run build && npm test

# both
scripts/dev.sh

# config as code checks (what CI runs)
npm run check:workflows                                 # packages/workflow-checks over workflows/, providers/, resource-types/
npm run lint:playbooks                                  # ansible-lint on every pinned git playbook, at its commit (pip install ansible-lint);
                                                        # GITLEAKS=<gitleaks> also scans each pinned source for secrets
cd backend && mvn -B -q test -Dtest=SampleWorkflowsDryRunTest   # every sample workflow dry-runs against its recorded samples
```

CI also scans the files and the new commits for secrets with gitleaks. A finding reviewed as a
false positive goes in `.gitleaksignore` by its fingerprint; never commit a real secret, even
in a test (use an obviously fake value and a `vault:<path>` reference).

The live runners have optional tests against real tools: `IAF_ANSIBLE_BIN` (a directory with
`ansible-runner` and `ansible-playbook`) enables `AnsibleRealRunTest`, `IAF_TERRAFORM_BIN` (a
terraform executable) enables `TerraformRealRunTest`.

## Backend conventions

- Package root `com.iaf.platform`; one package per slice with `adapters/{inbound,outbound}`,
  `application`, `domain`, `ports/{inbound,outbound}` (see `docs/architecture.md`).
- `ports/inbound/<Slice>Commands` + `<Slice>Queries` are interfaces returning `Mono`/`Flux`;
  `application/<Slice>CommandHandler` + `<Slice>QueryHandler` implement them (`@Service`,
  `@RequiredArgsConstructor`); `adapters/inbound/RestController<Slice>Adapter` implements both and
  maps `POST` to commands and `GET` to queries under `/api/v1`. The handlers are also `@Primary`:
  the REST adapter implements the same ports, so without it any constructor that asks for
  `<Slice>Commands` would find two beans and the context would not start.
- Commands return `Mono<CommandAccepted>` and publish a `DomainEvent` on the `EventBus`
  (`events.publish(type, aggregate, id, actor, data)`); projections are event listeners
  registered in a `@PostConstruct` (`events.register(this::on)`).
- The caller is `CurrentIdentity.current()` (a `Mono<Identity>` from the Reactor context);
  RBAC through `AccessRules`.
- Outbound ports are interfaces; the demo adapter is a plain `@Component`; a production adapter
  carries `@Profile("<name>")` and `@Primary`, so it replaces the demo one when its profile is on.
- Blocking work (JGit, process runners, Temporal client) goes through
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- Stores: a store port has one contract test, an abstract `<Port>Contract`, that every adapter's
  test extends, so the in-memory and the JDBC adapter behave the same. A JDBC adapter
  (`@Profile("postgres") @Primary`, over `Jdbc`) runs it on `TestDatabase.h2()` (H2 in PostgreSQL
  mode, migrated by Flyway, no Spring) and, when `PLATFORM_TEST_POSTGRES_URL` names a PostgreSQL
  (`jdbc:postgresql://localhost:5432/platform?user=platform` with `docker compose up postgres`), on
  `TestDatabase.postgres()`, a fresh schema per test. A schema change is a new migration: portable
  SQL in `db/migration/common`, PostgreSQL-only DDL in `db/migration/postgresql` (with its H2 twin
  under `src/test/resources`); never edit one that has shipped. A JDBC store blocks, so it is
  reached from `boundedElastic` or a worker thread, never from a timer or `parallel` thread.
- Records everywhere; enums serialise lowercase with `@JsonValue`; Jackson `non_null`; one
  injectable `Clock`; ids via `Ids.next("job")`.
- Errors: `IllegalArgumentException` (400), `NotFoundException` (404), `ForbiddenException`
  (403), `ConflictException` (409 + detail). Never return a secret value through the API.
- Tests: plain JUnit 5 + AssertJ for domain rules; Mockito + `StepVerifier` for handlers;
  `WebTestClient.bindToController(...)` for adapters; no Spring context unless the test is about
  wiring. Name tests after the behaviour (`startsACanaryWhenAPreviousVersionExists`).
- Say "workflow", never "capability". Nothing in the platform knows what a VM is: product names
  live only in the sample content under `workflows/`, `providers/` and `resource-types/`.

## Frontend conventions

- Two rules from `docs/design-language.md`: the canvas is n8n; everything else is Atlas
  (`packages/ui`). No UI, icon or motion library; `@xyflow/react` only inside
  `frontend/src/designer`.
- Colour through semantic utilities (`bg-surface`, `text-secondary`, `border-stroke-light`),
  never raw hex in components. Type through the HIG scale (`text-footnote`, `text-title3`).
- Every list has an `EmptyState`; every async view a `Skeleton`; every destructive action a
  confirm. The server judges facts (health bands, thresholds, checks); the UI renders them.
- The API client is `frontend/src/lib/api.ts`, typed from `docs/api.md`. YAML is parsed and
  rendered by the backend (`/workflows:parse`, `/workflows:render`); the browser never parses YAML.
- Tests: Vitest for pure modules (`packages/workflow-checks`, layout, expressions); Playwright
  for the end-to-end smoke (`frontend/e2e`).

## Commits

Conventional, imperative subjects (`runs: pause a job on step failure`). Every commit ends with
the attribution lines of the session that made it.
