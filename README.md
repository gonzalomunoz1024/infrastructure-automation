# console-search-indexer

The customer console's search: it follows the console's own events on Kafka (`state.resources`,
`drift.findings`), keeps one OpenSearch document per resource, and answers the search, type-ahead
and index operations the BFF uses for the header's global search and the filter bar over large
lists.

This repository is one branch (`console-search-indexer`) of the console work until the services
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

The service, its slices, profiles, API and decisions are described on the phase 6 branch, which
lands here by pull request.
