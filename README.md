# console-libs

The shared Java libraries of the customer console: the Gradle convention plugins every console
service builds with, the `wfrn` resource-name library, the `service-kit` the services are made
of, a hand-written client of the platform's target API and `platform-fake`, an in-memory
platform that implements that API for demos and tests. Published to GitHub Packages as
`com.iaf.console:*`.

This repository is one branch (`console-libs`) of the console work until the services move to
repositories of their own. It is shaped like that future repository: the Gradle root is the
branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock`.

## Modules

| Module | Artifact | What it is |
| --- | --- | --- |
| `build-logic` | `com.iaf.console:build-logic` | Convention plugins: `console.java-library`, `console.spring-service`, `console.publishing` |
| `wfrn` | `com.iaf.console:wfrn` | The wfrn resource name: parse, format, patterns, minted ids, ULIDs. No dependencies |
| `service-kit` | `com.iaf.console:service-kit` | Account scope, errors, outbox, idempotent consumer, event envelope, cursor paging, MongoDB and OpenTelemetry helpers, the ArchUnit rules (as a test fixture) |
| `platform-client` | `com.iaf.console:platform-client` | A reactive client of the platform's target API, typed against the hub's contract and contract tested; the contract test itself is its test fixture |
| `platform-fake` | `com.iaf.console:platform-fake` | The in-memory platform, a Spring WebFlux library a service's demo profile embeds |
| `platform-fake-app` | `com.iaf.console:platform-fake-app` | The fake as a runnable application, on port 18090 |

Every module is Java 21, compiled with `-Xlint:all -Werror`, without Lombok, with records and
sealed types where they fit. The fake keeps the services' package shape,
`com.iaf.console.fake.<slice>.{adapters,application,domain}`, and the ArchUnit rules of the
service kit check it.

## Build

Java 21 and nothing else: the Gradle wrapper fetches Gradle.

```sh
./gradlew build
```

`build` compiles every module and runs every test. Three suites depend on the world around them:

- the service kit's MongoDB adapter tests run against the database `CONSOLE_TEST_MONGO_URI`
  names and skip without it (CI starts a MongoDB service and sets it);
- `PlatformFakeContractTest` starts the fake in process on a free port and runs the client's
  contract test against it, always;
- `RealPlatformContractTest` runs the same contract test against a real platform when
  `CONSOLE_TEST_PLATFORM_URL` is set. It also needs `CONSOLE_TEST_PLATFORM_CONSOLE_TOKEN` (a
  service account of the `console` preset) and `CONSOLE_TEST_PLATFORM_ACCOUNT` (its account), and
  it reads `CONSOLE_TEST_PLATFORM_ADMIN_TOKEN` or `CONSOLE_TEST_PLATFORM_USER` (the person, by
  default the dev header `X-User: gonzalo`), `CONSOLE_TEST_PLATFORM_OTHER_TOKEN` and
  `CONSOLE_TEST_PLATFORM_OTHER_ACCOUNT` (a second console account, for the isolation tests),
  `CONSOLE_TEST_PLATFORM_INTERNAL_TOKEN` (the `console-internal` preset, which reads the feed),
  `CONSOLE_TEST_PLATFORM_ASSERTION_KEY` (the private EC JWK whose public half the platform trusts,
  with `..._ASSERTION_ISSUER`, `..._ASSERTION_AUDIENCE`, `..._DELEGATE` and `..._DELEGATE_GROUPS`)
  and `CONSOLE_TEST_PLATFORM_JOB_TIMEOUT_SECONDS`. Tests whose identities are missing skip.

The build uses two workers (`org.gradle.workers.max=2` in `gradle.properties`); it shares the
machine with the other console builds.

## The fake platform

### Run it

```sh
./gradlew :platform-fake-app:bootRun
# or
./gradlew :platform-fake-app:bootJar && java -jar platform-fake-app/build/libs/platform-fake-app-0.1.0-SNAPSHOT.jar
```

It listens on port 18090 (`server.port`), allows every `localhost` and `127.0.0.1` origin for
browser development, and seeds a demo world: the sample content (five resource types with the
hub's overlays, eleven workflows, the providers with their recorded samples, one region), two
demo console accounts, `123456789012` owned by `team-compute` and `210987654321` owned by
`team-data`, and a handful of images, VMs, databases, volumes and an environment built by running
the workflows, plus one job waiting for an approval and one needing attention.

```sh
curl -H 'X-User: gonzalo' localhost:18090/api/v1/regions
curl -H 'X-User: gonzalo' localhost:18090/api/v1/catalog
curl -H 'X-User: gonzalo' localhost:18090/api/v1/resources?type=vm
curl -H 'X-User: gonzalo' -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
     -d '{"name":"web-09","image":"img-1000","size":"small"}' localhost:18090/api/v1/vms
curl -N -H 'X-User: gonzalo' localhost:18090/api/v1/events/stream
```

Identity works as the target contract says. `X-User: <name>` is the dev header for a person
(`gonzalo` is an admin; `dana`, `raj` and `mia` are the other known people; without a header the
default user answers). Service accounts send `Authorization: Bearer iaf_...`; the demo accounts'
tokens are deterministic for a seed and are logged at boot:

```
demo service account sa-console-123456789012 for console account 123456789012 (team-compute): bearer iaf_...
demo service account sa-console-210987654321 for console account 210987654321 (team-data): bearer iaf_...
demo service account sa-console-internal (console-internal): bearer iaf_...
```

A console account acts for a person with `X-On-Behalf-Of`, an ES256 assertion the fake verifies
against the JWKS `console.platform-fake.jwks` names or against a key it generates at boot; the
P-02 rules apply (400 for a caller that may not delegate or an account that does not match, 401
for a bad, expired, replayed or untrusted assertion). `GET /api/v1/openapi.json` serves the hub's
target document, so the hub's contract check runs against the fake:

```sh
node scripts/platform-contract-check.mjs --from http://localhost:18090/api/v1/openapi.json --header 'X-User: gonzalo'
```

`POST /api/v1/demo/world:mutate` changes the recorded world for a demo: `set` fields on an object,
`delete` or `revive` it, or plan a failure of a provider action
(`{"provider":"vsphere","action":"vm.waitForPowerState","match":{"id":"vm-1006"},"fail":{"class":"timeout","retryable":true,"times":1}}`).

### Embed it

A service's demo profile embeds the fake instead of pointing at a platform:

```kotlin
dependencies {
    runtimeOnly("com.iaf.console:platform-fake:0.1.0-SNAPSHOT")
}
```

```yaml
# application-demo.yaml
console:
  platform-fake:
    enabled: true          # the auto-configuration mounts the routes in this application
    default-user: gonzalo  # who answers when no identity is sent; empty for none
    step-delay: 250ms      # how long each workflow step takes
    issuer: console-bff    # the assertion issuer and audience the fake accepts
    audience: platform
    jwks: '{"keys":[...]}' # the public keys it trusts; a key is generated when empty
    generate-demo-key: true
    seed: 20261023         # the seed of the demo tokens and ids
    demo: true             # seed the demo world
```

In a test, `PlatformFake.builder().stepDelay(Duration.ofMillis(20)).build()` makes one; `start(0)`
serves it on a free port and `baseUrl()` is what a client needs. Its accessors (`registry()`,
`jobs()`, `events()`, `world()`, `verifier()`, `demo()`) let a test look inside or plan failures.

## The client

```java
PlatformClient platform = PlatformClient.builder()
        .baseUrl("http://localhost:18090")
        .bearerToken(token)                      // or .devUser("gonzalo")
        .build();

Accepted<JobAccepted> accepted = platform.start(PlatformOperation.CREATE_VM, Map.of(),
        Map.of("name", "web-09", "image", imageId, "size", "small"),
        RequestOptions.console(assertion, requestId, idempotencyKey).withLabel("tab", "t1")).block();
JobDetail job = platform.getJob(accepted.body().jobId()).block();
Flux<FeedEvent> feed = platform.followEvents(lastSeq, List.of("job.", "resource."));
```

`PlatformPort` is the interface, one method per operation of the target contract, with
`RequestOptions` for the headers that change a request's meaning (`X-On-Behalf-Of`,
`X-Resource-Wfrn`, `X-Console-Request-Id`, `X-Label-*`, `Idempotency-Key`, `traceparent`,
`X-Workflow-Version`). Reads that page answer `PagedList` with the `Link` cursor, conditional reads
answer `Conditional` with the ETag, commands answer `Accepted` with the `Idempotent-Replay` flag,
and errors are `PlatformException` with the status, the error text, the problems and the
correlation id. `OnBehalfOfAssertions` signs and verifies the P-02 assertions.

## Consume from GitHub Packages

```kotlin
// settings.gradle.kts of a console service
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/gonzalomunoz1024/infrastructure-automation")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
            }
        }
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("console.spring-service") version "0.1.0-SNAPSHOT"
}
dependencies {
    implementation("com.iaf.console:platform-client:0.1.0-SNAPSHOT")
    testImplementation(testFixtures("com.iaf.console:platform-client:0.1.0-SNAPSHOT"))
}
```

GitHub Packages needs a token with `read:packages` even for reads. Every push to `console-libs`
republishes the snapshot; a release is a tagged commit with a fixed `version` in
`gradle.properties`.

## The contracts pin

`contracts.lock` at the root names the hub commit this repository builds against and the sha256
of every contract it copied from it: the target OpenAPI document (in the client's test resources
and the fake's resources, which serve it), the target AsyncAPI document (in the fake), the list
of used operations (in the client's tests) and the wfrn fixtures (in `wfrn`'s tests).
`ContractsLockTest` fails when a copy no longer matches its hash, and
`PlatformOperationContractTest` fails when the client calls a path and method the target document
does not have. Moving to a newer hub commit is one change: copy the files, update the lock and
`console.hub.commit` in `gradle.properties`, and let the tests say what the new contract asks for.

## CI

`.github/workflows/ci.yml` has three jobs. `build` runs `./gradlew build` on Temurin 21 with a
MongoDB service container and `CONSOLE_TEST_MONGO_URI` set. `secrets` runs gitleaks the way the
hub does: a pinned release over the checked-in files and over the new commits (the pull request's
or the push's range, else the commits since `origin/main`). `publish` runs on a push to
`console-libs` only and publishes every module to GitHub Packages with the workflow's
`GITHUB_TOKEN`. `.gitleaks.toml` extends the default rules; a reviewed false positive goes in
`.gitleaksignore` by fingerprint, never a silenced rule.

## Decisions

- **A hand-written, contract-tested client.** The target API has 89 operations with headers that
  carry meaning (`Idempotency-Key`, `Idempotent-Replay`, `ETag`, `Link`, `Last-Event-ID`,
  `X-On-Behalf-Of`), server-sent events and generated per-workflow routes. A generated client would
  bind the services to a generator's shapes and still need hand-written handling for all of that.
  The client is typed by hand against the hub's document, a test checks that every path and
  method it calls exists in that document, and the contract test runs it against the fake in CI
  and against a real platform when one is reachable. The contract test is a test fixture of the
  client so that the fake, a real platform and any future adapter run the same suite.
- **The fake is a library and an application.** Services embed it in a demo profile (same JVM, no
  Docker, no network), console-web and the hub's contract check run the application. One
  implementation, `PlatformFake`, serves both; the auto-configuration mounts its routes when
  `console.platform-fake.enabled` is true.
- **Spring Boot 3.5.16** everywhere, the platform's version, so one BOM decides every Spring,
  Jackson and Reactor version here and in the services.
- **Content is the platform's.** The fake runs the sample content's workflow YAML with an
  interpreter (`use`, `set`, `switch`, provider, ansible, idp and terraform calls, `register`,
  `run`, `fork`, `for`, `notify`, `raise`, `script`, `try`, `approval`, retries, compensations,
  the workflow `catch` and `outputs`), answers provider calls from the providers' recorded samples
  and prices create effects from the price list. Script steps are not evaluated (their preview
  says so) and `fork` branches run one after another.
- **Deterministic seeds, time-based ids.** The demo service accounts' ids and tokens come from a
  fixed clock and the configured seed, so a developer's bearer token survives a restart; job,
  resource and event ids are ULIDs of the real clock, so streams and cursors order as they would
  on the platform.
- **Identity.** `X-User` names a person in development; without any identity the configured
  default user answers, which keeps `curl` and a browser simple. Service accounts carry scopes;
  the `console` preset acts for one account and never reads the feed, the `console-internal`
  preset reads the feed for every account. Delegations follow P-02: 400 when the caller may not
  delegate or the assertion names another account, 401 for a bad, expired, replayed (`jti`),
  premature or untrusted assertion, assertions live at most a minute. The demo key is generated
  at boot and the test key in the test; no key is ever committed.
- **Scoping (P-18).** A console account sees its account's resources and jobs and the public
  resources; what it cannot see answers 404, never 403. A person sees their own jobs, their teams'
  workflows' jobs and the jobs they may approve; details of a job that is not theirs are masked.
- **Jobs.** Every step takes `step-delay` (250 ms by default, zero for the demo seed, 20 ms in
  the contract test). A step failure outside a `try`, a `catch` or a compensation pauses the job
  for attention (retry, edit the input, skip, abort); `raise` fails the job at once. A paused job
  takes decisions before anything shows the pause: once an approval is listed, the status reads
  `waiting-approval` or `needs-attention`, or the event is out, a decision never meets
  `job.notPaused` (a second decision on the same pause still does). `abort` runs
  the compensations and the workflow `catch` and ends `rolled-back` or `failed`; `cancel` stops the
  running step, emits `job.cancelled` and runs nothing else. A rerun or a resume keeps the account,
  region and wfrn of the source job and takes the new request's console request id and labels.
  Batches run their rows in the rollout's waves with its parallelism and stop at its failure
  percentage; `for` iterations get step records suffixed `[i]`.
- **Idempotency (P-21).** `Idempotency-Key` on every command, fingerprinted over the method, path,
  body and the context headers, scoped per caller, kept 24 hours; a replay answers the first
  response with `Idempotent-Replay: true`, another body under the same key answers 422
  `idempotency.mismatch`.
- **The feed (P-01).** A ring of 50,000 events, `id:` is the sequence, `Last-Event-ID` and
  `after` resume, keys are deterministic per the event key rules, and a console account's events
  carry its context. Job streams carry the stream type (`job`, `step`, `approval`, `attention`,
  `batch`) with the domain event's name in `data.event`, as the contract says.
- **Routes.** Path variables of generated routes stop at a colon so `/vms/{vmId}:restart:preview`
  never falls into `/vms/{vmId}:preview`; the audit record route does the same for `:verify`.
  Jobs list with a `Link` cursor only in cursor mode (`updatedAfter` or `after`); a plain list is
  newest first and `limit` truncates it.
- **Observation (P-09).** `observe` renders the provider's recorded sample through the type's
  `observe.map` and skips fields the provider did not answer; a type without a `list` block falls
  back to its `discover` action and answers the managed items; metrics answer 404
  `observe.notSupported` when the type has none.
- **Re-home (P-23).** `POST /api/v1/resources/{id}:rehome` takes `{ wfrn, accountId }` from admins
  or a service account holding `resources:rehome` (the `console` preset). The record keeps its id,
  region and lineage, takes the new wfrn and account, and lists the old wfrn in `previousWfrns`;
  `by-wfrn` still answers the old name with `Content-Location` naming the current one, and the move
  emits `resource.registered` with `data.previousWfrn`. The same body again is a 200 that changes
  nothing (the caller that moved it still finds it). A record that already moved answers 409
  `resource.rehomed`; one an unfinished job holds (its context names the record's wfrn or its input
  names the record) answers 409 `resource.busy`. The fake keeps no audit chain for a move, since its
  chains belong to jobs.
- **Evidence (P-12).** Every job opens a hash chain (`requested`, `policy`, `approval`,
  `decision`, `step`, `changed`, `closed`), sealed when the job ends, verified by `:verify`,
  exported as JSON or a small hand-written PDF; the evidence pack is a zip and, as the contract
  says, for admins only.
- **The demo mutate endpoint** is open to any authenticated caller: the fake is a demo tool, and
  a UI that plans a provider failure to show attention handling is the point.
- **The application excludes Spring Data Mongo's auto-configuration.** The service kit carries the
  reactive MongoDB starter for the services; the fake keeps nothing in a database.
- **The contracts pin is checked, not fetched.** The build never reaches the network for a
  contract; the copies live in the repository and a test compares their hashes with the lock, so
  a stale copy fails the build and a bump is a reviewed change.
