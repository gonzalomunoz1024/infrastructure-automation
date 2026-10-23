# console-state-service

The customer console's record of every resource, named by wfrn, from the first request to the
tombstone: an event-sourced inventory that holds the desired state a customer asked for and the
observed state the platform and the providers report, and serves the console's lists, details
and histories.

This repository is one branch (`console-state-service`) of the console work until the services
move to repositories of their own. It is shaped like that future repository: the Gradle root is
the branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock`, and the shared code comes from `console-libs` as
`com.iaf.console:*`.

## Build

Java 21 and nothing else: the Gradle wrapper fetches Gradle.

```sh
./gradlew build
```

The service, its slices, profiles, API and decisions are described on the phase 2 branch, which
lands here by pull request.
