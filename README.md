# console-identity-service

The identity service of the customer console: accounts, users and their memberships, groups,
roles, policies, access keys and quotas, and the authorization decisions every other console
service asks for (`POST /v1/authorize`), compiled into an OPA bundle.

This repository is one branch (`console-identity-service`) of the console work until the services
move to repositories of their own. It is shaped like that future repository: the Gradle root is
the branch root, it carries its own CI, and it holds no platform files. What every service shares
(decisions, contracts, the wfrn fixtures) lives on the hub branch, `console-root`, which this
repository pins by commit in `contracts.lock`; the shared Java code comes from `console-libs`
as `com.iaf.console:*`.
