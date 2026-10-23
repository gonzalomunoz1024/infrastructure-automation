# console-cost-service

The customer console's cost service (hub decision 25): what every console resource costs, priced
from the platform's price lists; the Cost Explorer's queries by account, service, type, region,
tag and cost centre; monthly budgets with forecasts, alerts and an optional block on creates; and
the monthly chargeback that goes to finance. Work packages 7.3, 9.3 and 9.9 of the
[customer console plan](https://claude.ai/artifact/Sp3X9oC33s3XjN86vkYsXC).

This branch is the root of the future `console-cost-service` repository. The contracts it serves
and reads live on the hub branch, `console-root` (`contracts/openapi/console-cost-service.yaml`);
the shared code comes from `console-libs` as `com.iaf.console:*`.

Build with Java 21: `./gradlew build`. The Gradle build, the code and the CI arrive with the
phase 7 branch, `console-cost-service--phase-7`.
