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
| `platform-client` | `com.iaf.console:platform-client` | A reactive client of the platform's target API, contract tested |
| `platform-fake` | `com.iaf.console:platform-fake` | The in-memory platform, a Spring WebFlux library a service's demo profile embeds |
| `platform-fake-app` | `com.iaf.console:platform-fake-app` | The fake as a runnable application, on port 18090 |

## Build

Java 21 and nothing else: the Gradle wrapper fetches Gradle.

```sh
./gradlew build
```
