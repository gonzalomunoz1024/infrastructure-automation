# Blueprints

A blueprint is a stack the console launches and tears down as one request: the parameters a person
fills in, the resources it creates and the outputs its page shows (hub decision 33, WP 9.7). It is
content, like the type overlays: one `kind: Blueprint` file per blueprint in this folder, named
after `metadata.name`, checked by `npm run check` and pinned by the state service, which runs it.
The schema is [`contracts/schemas/blueprint.schema.json`](../contracts/schemas/blueprint.schema.json).

| File | What it launches |
| --- | --- |
| [`web-app.yaml`](web-app.yaml) | A hardened image, a web server built from it, its database and a data volume on the server |
| [`dev-sandbox.yaml`](dev-sandbox.yaml) | A development VM from an image the person picks, with a scratch volume |

## The shape

```yaml
apiVersion: iaf.console/v1
kind: Blueprint
metadata: { name, version, title, description, owner, tags }
spec:
  parameters:            # what the launch form asks, by name
    appName: { type: string, title: Application name, pattern: '^[a-z][a-z0-9-]{2,20}$' }
  resources:             # what the stack creates, by an id local to the blueprint; each is one step
    web:
      type: vm           # a resource type; the step runs the type's create workflow (producedBy)
      input: { name: '${params.appName}-web', image: '${resources.image.registryId}' }
      dependsOn: [database]
      deletionPolicy: delete          # or retain
      deleteInput: { reason: 'Stack ${stack.name} torn down: ${teardown.reason}' }
  outputs:               # what the stack's page shows once it is active
    server: { title: Web server, value: '${resources.web.wfrn}' }
```

A parameter is a `string`, an `integer` or a `boolean`, with an optional `enum`, `pattern`,
`minimum`, `maximum` and `default`; without a default it is required. `x-resource: { type }` makes
the launch form offer the registered resources of that type, and the value is the registry id.

## References

A string may hold references, resolved by the state service when the step starts:

| Reference | Value |
| --- | --- |
| `${params.<name>}` | The parameter's value, its default when the person gave none |
| `${resources.<id>.wfrn}`, `.registryId`, `.name` | The other resource's wfrn, platform registry id or name |
| `${resources.<id>.attributes.<field>}` | A field of the other resource as the platform registered it; never a sensitive field |
| `${stack.name}`, `.wfrn`, `.accountId`, `.region` | The stack's own |
| `${teardown.reason}` | The reason the person gave for the teardown, in `deleteInput` only |

A value that is one reference keeps the referenced value's type (`sizeGb: '${params.dataGb}'` is an
integer); any other text is a string. Naming another resource makes the step wait for it: a step
starts when every resource it references or lists in `dependsOn` is active, and the resources form
no cycle. Outputs resolve when the stack is active.

## What `npm run check` holds a blueprint to

- The schema, and `metadata.name` equal to the file's name.
- Every resource's type is a pinned type whose `producedBy` is a pinned workflow making that type.
- The input is that workflow's: no unknown input, every required input given or defaulted, every
  literal one the input accepts (enum, pattern, range). A whole-value reference must fit: a
  parameter feeding an enum input has an enum within it, one feeding a ranged input a range within
  it, one feeding a patterned input an enum or a pattern; an `x-resource` input takes the
  `registryId` of a resource of its type, or an `x-resource` parameter of that type.
  Text with references only goes where a string goes, never to an enum or `x-resource` input.
- References name declared parameters and resources (never the resource itself), the stack's four
  fields and, in `deleteInput` only, the teardown's reason; never a sensitive field.
- No cycle through references and `dependsOn`; the check prints the waves the stack starts in.
- A resource teardown deletes (`deletionPolicy: delete`, the default) has a pinned delete workflow
  (`verb: delete` for its type) with a subject input (P-08), and `deleteInput` gives every other
  required input; `retain` takes no `deleteInput`. In the pinned catalog only `vm` has one
  (`delete-vm`), so the samples retain their images, databases and volumes.
- Parameters: defaults and enum values fit their types, patterns compile, ranges are ordered, and
  an `x-resource` type is pinned. An unused parameter is a warning.

## How a stack runs

The state service owns the stack (`wfrn:<p>:blueprints:<region>:<account>:stack/stack-<ulid>`) and
decides; the BFF executes, as it does any create or delete, for the person who launched it.

1. `POST /v1/stacks` checks the parameters, records the stack (`pending`) and announces the first
   wave on `state.stacks`: each ready step with its resolved input and an idempotency key.
2. The BFF mints the child with `POST /v1/resources:mint` naming `stack: { wfrn, step, attempt }`
   and starts its create job. The mint is where a step takes its child: only a ready step does,
   once per attempt, so a teardown that came first refuses it.
3. As children become active the stack announces the steps that were waiting on them, their
   references now resolved. When every step is done the stack is `active` and its outputs resolve.
4. A child that fails stops the launch: the stack is `failed`, nothing new starts, nothing is
   rolled back. Retrying the child (decision 24) resumes the stack when it succeeds;
   `:resume` re-announces steps the BFF could not start (`steps/{step}:report-failure`).
5. `:teardown` deletes in reverse dependency order, each child once nothing in the stack that
   depends on it is left, through the type's delete workflow with `deleteInput`; retained
   resources stay in the account. A failed delete stops the teardown; `:resume` tries it again,
   and a teardown naming `retain: [step]` leaves that resource in place.

The contracts are the state service's `/v1/blueprints` and `/v1/stacks` routes
(`contracts/openapi/console-state-service.yaml` 1.2.0) and the `state.stacks` channel
(`contracts/asyncapi/console-events.yaml` 1.1.0).
