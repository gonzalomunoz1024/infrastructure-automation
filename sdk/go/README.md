# Provider SDK for Go

Write a custom-code provider in Go: declare its actions with their input and output types, run
the worker that serves them on the provider's Temporal task queue, and publish the provider file
the platform reads. The SDK and the platform share one contract (the payload, the error classes,
the heartbeat); see "Custom-code providers" in [docs/architecture.md](../../docs/architecture.md).
The Java and Python SDKs ([../java](../java), [../python](../python)) follow the same model.

```
sdk/go/
  provider/            the library
  examples/ip-pool/    an example provider: IP address pools kept in memory
```

## Quick start

```bash
go get github.com/gonzalomunoz1024/infrastructure-automation/sdk/go/provider
```

Describe the provider. Input and output types are structs; their JSON Schemas are derived from
them the way `encoding/json` reads them (a field is required unless it is a pointer or tagged
`omitempty`; `description:"..."` and `enum:"a,b"` tags describe it):

```go
type Reservation struct {
	Pool     string `json:"pool" description:"The pool to take the address from"`
	Hostname string `json:"hostname"`
}

type Address struct {
	Address string `json:"address"`
	State   string `json:"state" enum:"reserved,released"`
}

func reserve(ctx context.Context, call *provider.Call, in Reservation) (Address, error) {
	// call.IdempotencyKey, call.Job, call.Step, call.Environment, call.Attempt
	if full {
		return Address{}, provider.Fatal("pool_exhausted", in.Pool+" has no free address")
	}
	...
}

p := provider.New("ip-pool").                      // providers/<owner>/ip-pool.yaml
	Title("IP address pools").
	Owner("team-platform").                        // the team that may edit and publish it
	Version("1.0.0").
	HeartbeatTimeout(30 * time.Second).            // optional: lost after 30s without a heartbeat
	Error("pool_exhausted", false, "The pool has no free address").
	Action(provider.NewAction("address.reserve", reserve).
		Title("Reserve an address").
		Effect(provider.Create)).                  // Read, Create, Change or Delete
	MustBuild()                                    // checks it like the platform's provider checks
```

An action that changes something in place says `provider.Change`, written `change` in the
provider file as the platform spells it; `update`, the spelling of earlier SDK versions, still
reads as `change` (`Effect("update")`, the deprecated `provider.Update`) until the next version.

Run its worker. It listens on the provider's task queue (`provider-<name>` unless you name one
with `TaskQueue(...)`) and stops gracefully on SIGINT or SIGTERM, letting calls in flight finish:

```go
provider.Run(ctx, p, provider.OptionsFromEnv())    // TEMPORAL_ADDRESS, TEMPORAL_NAMESPACE
```

Publish the provider file, then review it like any provider change, or submit it to the platform,
which runs its provider checks and opens the change request:

```go
provider.WriteFile(p, "providers/team-platform/ip-pool.yaml")
provider.Submit(ctx, p)   // IAF_PLATFORM_URL and a service-account token in IAF_PLATFORM_TOKEN
```

## Handlers

A handler is `func(ctx context.Context, call *provider.Call, in I) (O, error)`:

| | |
|---|---|
| `call.IdempotencyKey` | the same for every retry of the step and for a worker that picks the call up again; new for a new run of it. Pass it to the system you call, or keep it with what you create. |
| `call.Job`, `call.Step`, `call.Environment`, `call.Attempt` | the job (id, workflow, version, trace id), the step id, the environment and the step's attempt |
| `call.Heartbeat(details...)` | tell the platform the call is alive; call it more often than the heartbeat timeout during long work, and watch `ctx.Done()`: a cancelled step cancels the context |
| `provider.Retryable("pool_busy", "...")` | a failure the step's retry policy may retry |
| `provider.Fatal("pool_exhausted", "...")` | a failure no retry fixes: the step fails at once |

Any other error is reported as the retryable class `error`. The worker checks the input against
the action's schema before the handler runs and the output after it; a call that breaks the
contract fails for good with `invalid_input`, `invalid_output`, `invalid_request` or
`unknown_action`.

An action can name another one as its preview with `Plan("address.preview")`, and give the answer
the platform's demo mode returns for it with `Sample(...)`. `Input(schema)` and `Output(schema)`
replace a derived schema.

## Configuration

| Variable | Default | |
|---|---|---|
| `TEMPORAL_ADDRESS` | `127.0.0.1:7233` | the Temporal frontend (on Temporal Cloud, the namespace's gRPC endpoint) |
| `TEMPORAL_NAMESPACE` | `default` | the namespace the platform runs in (on Temporal Cloud, `<namespace>.<account>`) |
| `WORKER_DRAIN_SECONDS` | `30` | how long a stopping worker lets calls in flight finish; a value that is not a whole number of seconds is logged and falls back to 30 |
| `IAF_TEMPORAL_TLS_CA` | | a PEM file of the CA that signed the frontend's certificate; without it TLS trusts the system's CAs |
| `IAF_TEMPORAL_TLS_CERT`, `IAF_TEMPORAL_TLS_KEY` | | PEM files of the worker's client certificate and its key, for mutual TLS |
| `IAF_TEMPORAL_API_KEY` | | a Temporal Cloud API key, sent as a bearer token over TLS; never logged |
| `IAF_PLATFORM_URL` | `http://localhost:8080` | the platform, for `Submit` |
| `IAF_PLATFORM_TOKEN` | | a service-account token (`iaf_...`), for `Submit`; sent, never printed |

Any `IAF_TEMPORAL_*` variable turns TLS on (`Options.TLSCA`, `TLSCert`, `TLSKey` and `APIKey` in code); a
file that cannot be read stops `Run`, naming its variable. For anything else (interceptors, a data
converter), dial your own `client.Client` and use `provider.NewWorker(c, p, worker.Options{})`.

## Testing

`provider.Register` puts the actions on any of Temporal's test environments; call them the way
the platform does, with an envelope as the argument:

```go
var suite testsuite.WorkflowTestSuite
env := suite.NewTestActivityEnvironment()
provider.Register(env, p)

v, err := env.ExecuteActivity("address.reserve", provider.TestEnvelope(Reservation{Pool: "lab", Hostname: "web-01"}))
```

A classified failure arrives as a `*temporal.ApplicationError` whose `Type()` is the error class
and whose `NonRetryable()` is set for a fatal one. See
[examples/ip-pool/pools_test.go](examples/ip-pool/pools_test.go).

```bash
go test ./...
```

## The example

`examples/ip-pool` reserves addresses from named pools: `pool.get`, `address.preview`,
`address.reserve` (a create whose preview is `address.preview`, idempotent by the call's key) and
`address.release`.

```bash
go run ./examples/ip-pool publish ../../providers/team-platform/ip-pool.yaml   # write the provider file
go run ./examples/ip-pool run                                                  # serve provider-ip-pool

docker build -f examples/ip-pool/Dockerfile -t ip-pool-provider .              # from sdk/go
```
