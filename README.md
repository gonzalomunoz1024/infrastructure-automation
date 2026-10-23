# console-drift-service

The customer console's drift detection: it compares what a customer asked for (the desired state
the state service holds for every resource) with what the provider actually has, records each
difference as a finding, and offers the three decisions a person takes on it: revert in one click,
accept the provider's values, or ignore the fields. Nothing is ever put back on its own.

This repository is one branch (`console-drift-service`) of the console work until the services
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

The service, its loops, profiles, API and decisions are described on the phase 5 branch, which
lands here by pull request.
