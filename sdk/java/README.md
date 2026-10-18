# Provider SDK for Java

Write a custom-code provider in Java: declare its actions with their input and output types, run
the worker that serves them on the provider's Temporal task queue, and publish the provider file
the platform reads. The SDK and the platform share one contract (the payload, the error classes,
the heartbeat); see "Custom-code providers" in [docs/architecture.md](../../docs/architecture.md).
The Go and Python SDKs ([../go](../go), [../python](../python)) follow the same model.

This is a Maven project of its own (Java 21), not part of the backend build:

```
sdk/java/
  provider-sdk/      the library: com.iaf.provider.sdk
  examples/cmdb/     an example provider: configuration items kept in memory
```

## Quick start

Build and install the library, then depend on it:

```bash
cd sdk/java && mvn -B install
```

```xml
<dependency>
  <groupId>com.iaf</groupId>
  <artifactId>provider-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Describe the provider. Input and output types are records; their JSON Schemas are derived from
them (every component is required unless it is an `Optional` or marked `@Field(optional = true)`):

```java
record Lookup(@Field(description = "The item id") String id) {}
record Item(String id, String name, String state) {}

Provider provider = Provider.named("cmdb")                 // the provider's name: providers/<owner>/cmdb.yaml
        .title("Configuration items")
        .owner("team-platform")                            // the team that may edit and publish it
        .version("1.0.0")
        .heartbeatTimeout(Duration.ofSeconds(30))          // optional: lost after 30s without a heartbeat
        .error("not_found", false, "No item has that id")  // error classes, retryable or not
        .action(Action.of("item.get", Lookup.class, Item.class, (ctx, in) -> store.get(ctx, in))
                .title("Get an item")
                .effect(Effect.READ))                      // READ, CREATE, CHANGE or DELETE
        .build();                                          // checks it like the platform's provider checks
```

An action that changes something in place says `Effect.CHANGE`, written `change` in the provider
file as the platform spells it; `update`, the spelling of earlier SDK versions, still reads as
`change` (`Effect.of("update")`, the deprecated `Effect.UPDATE`) until the next version.

Run its worker. It listens on the provider's task queue (`provider-<name>` unless you name one
with `.taskQueue(...)`) and stops gracefully on SIGTERM, letting calls in flight finish:

```java
try (ProviderWorker worker = ProviderWorker.start(provider)) {   // TEMPORAL_ADDRESS, TEMPORAL_NAMESPACE
    worker.awaitShutdown();
}
```

Publish the provider file, then review it like any provider change, or submit it to the platform,
which runs its provider checks and opens the change request:

```java
Publisher.write(provider, Path.of("providers/team-platform/cmdb.yaml"));
Publisher.submit(provider);   // IAF_PLATFORM_URL and a service-account token in IAF_PLATFORM_TOKEN
```

## Handlers

A handler gets the validated input and an `ActionContext`:

| | |
|---|---|
| `ctx.idempotencyKey()` | the same for every retry of the step and for a worker that picks the call up again; new for a new run of it. Pass it to the system you call, or keep it with what you create. |
| `ctx.job()`, `ctx.step()`, `ctx.environment()`, `ctx.attempt()` | the job (id, workflow, version, trace id, `traceparent`), the step id, the environment and the step's attempt |
| `ctx.heartbeat(details)` | tell the platform the call is alive; call it more often than the heartbeat timeout during long work. It throws when the step was cancelled: let that propagate. |
| `throw ctx.retryable("store_busy", "...")` | a failure the step's retry policy may retry |
| `throw ctx.fatal("not_found", "...")` | a failure no retry fixes: the step fails at once |

Any other exception is reported as the retryable class `error`. The worker checks the input
against the action's schema before the handler runs and the output after it; a call that breaks
the contract fails for good with `invalid_input`, `invalid_output`, `invalid_request` or
`unknown_action`.

An action can name another one as its preview with `.plan("item.preview")`, and give the answer
the platform's demo mode returns for it with `.sample(...)`. `Action.json(id, handler)` works on
plain `JsonNode` with schemas you give with `.input(...)` and `.output(...)`.

## Tracing

Each call carries the platform's step span as a W3C `traceparent` (`ctx.job().traceparent()`).
While the handler runs, the traceparent and the trace id are in the logging MDC (`%X{traceparent}`,
`%X{traceId}` in a logback pattern). With the OpenTelemetry API on the worker's classpath
(`io.opentelemetry:opentelemetry-api`, an optional dependency of the SDK) the handler also runs in a
span `run <provider>.<action>`, child of the platform's step span and current, so the clients it
calls carry the trace on; the OpenTelemetry Java agent or an SDK you register exports it.

## Configuration

| Variable | Default | |
|---|---|---|
| `TEMPORAL_ADDRESS` | `127.0.0.1:7233` | the Temporal frontend (on Temporal Cloud, the namespace's gRPC endpoint) |
| `TEMPORAL_NAMESPACE` | `default` | the namespace the platform runs in (on Temporal Cloud, `<namespace>.<account>`) |
| `WORKER_DRAIN_SECONDS` | `30` | how long a stopping worker lets calls in flight finish; a value that is not a whole number of seconds is logged and falls back to 30 |
| `IAF_TEMPORAL_TLS_CA` | | a PEM file of the CA that signed the frontend's certificate; without it TLS trusts the system's CAs |
| `IAF_TEMPORAL_TLS_CERT`, `IAF_TEMPORAL_TLS_KEY` | | PEM files of the worker's client certificate and its key, for mutual TLS (the key in PKCS #8, `BEGIN PRIVATE KEY`; `openssl pkcs8 -topk8 -nocrypt` converts another) |
| `IAF_TEMPORAL_API_KEY` | | a Temporal Cloud API key, sent as a bearer token over TLS; never logged |
| `IAF_PLATFORM_URL` | `http://localhost:8080` | the platform, for `Publisher.submit` |
| `IAF_PLATFORM_TOKEN` | | a service-account token (`iaf_...`), for `Publisher.submit`; sent, never printed |

Any `IAF_TEMPORAL_*` variable turns TLS on (`WorkerSettings.withTls(...)` and `.withApiKey(...)` in code); a
file that cannot be read stops the worker at start, naming its variable. For anything else
(interceptors, a data converter), build your own `WorkflowClient` and `WorkerFactory` and serve the
provider with `ProviderWorker.attach(factory.newWorker(provider.taskQueue()), provider)`.

## Testing

`ProviderWorker.activities(provider)` is the activity implementation the worker registers; put it
on Temporal's `TestActivityEnvironment` and call the actions the way the platform does, with an
`Envelope` as the payload:

```java
@ActivityInterface
public interface Calls {
    @ActivityMethod(name = "item.get") JsonNode get(JsonNode envelope);
}

TestActivityEnvironment env = TestActivityEnvironment.newInstance();
env.registerActivitiesImplementations(ProviderWorker.activities(provider));
Calls calls = env.newActivityStub(Calls.class, ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
        .build());
JsonNode item = calls.get(Envelope.of(Map.of("id", "ci-1001")).toJson());
```

A classified failure arrives as an `ActivityFailure` whose cause is an `ApplicationFailure` with
the error class as its type and `isNonRetryable()` set for a fatal one. See
[examples/cmdb](examples/cmdb/src/test/java/com/example/cmdb/CmdbTest.java).

```bash
mvn -B test          # the library's and the example's tests
```

## The example

`examples/cmdb` registers configuration items: `item.get`, `item.preview` and `item.register`
(a create whose preview is `item.preview`, idempotent by the call's key).

```bash
mvn -B package -Dmaven.test.skip=true
java -jar examples/cmdb/target/cmdb-provider.jar publish /tmp/cmdb.yaml   # write the provider file
java -jar examples/cmdb/target/cmdb-provider.jar run                      # serve provider-cmdb

docker build -f examples/cmdb/Dockerfile -t cmdb-provider .               # from sdk/java
```
