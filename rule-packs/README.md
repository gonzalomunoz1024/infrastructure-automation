# Rule packs

Compliance rule packs (WP 9.6, hub decision 26). The drift service scores every managed resource
against every pack on each read of the resource and on each change the inventory brings, keeps one
evaluation per resource and rule, and serves the account's scorecard, the organization's heat map,
the failing resources of a rule and one-click fixes (`contracts/openapi/console-drift-service.yaml`).

| Pack | Rules |
| --- | --- |
| `required-tags.yaml` | `owner`, `cost-centre` (CC-nnnn), `data-class` (public, internal, confidential, restricted), `environment` (dev, test, staging, prod), on every type; each fixed by setting the tag |
| `compute-baseline.yaml` | Production VMs: `prod-min-cpu` (2 vCPUs, fixed by `update`), `prod-min-memory` (4 GiB, `update`), `prod-powered-on` (fixed by `restart`) |
| `data-baseline.yaml` | Databases: `supported-version` (7.0 or 8.0, fixed by `mongodb-upgrade`), `prod-tier` (not M10 in production), `secret-in-vault` |

## Writing a rule

A rule (`contracts/schemas/rule-pack.schema.json`) says the same thing twice:

- **Rego** for the firm's OPA engine: package `console.scorecards.<pack>.<rule>` (dashes become
  underscores), a boolean `applies` (default false) and a set `deny` of messages; an empty `deny`
  passes. Write negations against helper rules (`not valid`), never against a function call on
  a value that may be missing: in OPA `not f(input.x)` is false when `input.x` is undefined.
- **`types`, `when` and `check`**, declarative conditions the drift service's embedded engine
  runs until the firm's engine is wired in (as the platform's policy check is a mock today).
  `contracts/rule-packs/reference.mjs` is their reference: a value is read at a dotted path, and
  every test but `present: false` is false where the value is missing.

The input both see is `{ resource: { wfrn, accountId, region, type, service, name, state,
environment, production, attributes, observed, effective, tags } }`: `attributes` are the desired
state, `observed` what the provider last reported, `effective` the desired attributes overlaid
with the observed ones.

Every rule lists `examples` with the verdict expected (`pass`, `fail`, `not-applicable`);
`npm run check` runs each through the declarative reference and through the Rego with OPA
(`OPA_BIN`, or `opa` on the PATH; CI installs a pinned release) and fails when either disagrees.

A `remediation` is optional: `kind: workflow` names a catalog verb of the type (or a `workflow`
by name, when its noun differs) and its `input`; `kind: tags` the tags to `set`. Values may be
`{{ $input.<name> }}` (the person supplies them, see `inputs`) or `{{ $resource.<path> }}`; a value that
is exactly one template takes the type of what it names (`cpu: "{{ $input.cpu }}"` sends a number). Raise
`metadata.version` on every change: resources scored under an older version are scored again on
their next read.
