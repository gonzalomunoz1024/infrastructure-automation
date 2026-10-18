# Provider SDK for Python

Write a custom-code provider in Python: declare its actions with their input and output types,
run the worker that serves them on the provider's Temporal task queue, and publish the provider
file the platform reads. The SDK and the platform share one contract (the payload, the error
classes, the heartbeat); see "Custom-code providers" in
[docs/architecture.md](../../docs/architecture.md). The Java and Go SDKs ([../java](../java),
[../go](../go)) follow the same model.

```
sdk/python/
  src/iaf_provider/        the library
  tests/                   its tests
  examples/dns_records/    an example provider: DNS records kept in memory
```

## Quick start

```bash
pip install ./sdk/python          # Python 3.10 or later
```

Describe the provider. Input and output types are pydantic models (or dataclasses, TypedDicts);
their JSON Schemas are derived from the handler's type hints, the way pydantic reads the input and
writes the output. A field with a default is optional, and `Field(description=...)` describes one:

```python
from datetime import timedelta
from pydantic import BaseModel, Field
from iaf_provider import Call, Effect, Provider, fatal, retryable

class NewRecord(BaseModel):
    zone: str = Field(description="The zone, like lab.example.internal")
    name: str
    value: str
    ttl: int = 300

class Record(BaseModel):
    fqdn: str
    value: str

dns = Provider(
    "dns-records",                          # providers/<owner>/dns-records.yaml
    title="DNS records",
    owner="team-platform",                  # the team that may edit and publish it
    version="1.0.0",
    heartbeat_timeout=timedelta(seconds=30),  # optional: lost after 30s without a heartbeat
)
dns.error("record_conflict", retryable=False, description="The name already has a different record")
dns.error("dns_busy", retryable=True, description="The zone is taking another change")

@dns.action("record.create", title="Create a record", effect=Effect.CREATE)  # READ, CREATE, CHANGE or DELETE
async def create(call: Call, new: NewRecord) -> Record:
    # call.idempotency_key, call.job, call.step, call.environment, call.attempt
    if taken:
        raise fatal("record_conflict", f"{new.name} already points elsewhere")
    ...
```

An action that changes something in place says `Effect.CHANGE`, written `change` in the provider
file as the platform spells it; `update`, the spelling of earlier SDK versions, still reads as
`change` (`effect="update"`, the deprecated `Effect.UPDATE`) until the next version.

Run its worker. It listens on the provider's task queue (`provider-<name>` unless you pass
`task_queue=`) and stops gracefully on SIGINT or SIGTERM, letting calls in flight finish:

```python
import asyncio
from iaf_provider import run_worker

asyncio.run(run_worker(dns))      # TEMPORAL_ADDRESS, TEMPORAL_NAMESPACE
```

Publish the provider file, then review it like any provider change, or submit it to the platform,
which runs its provider checks and opens the change request:

```python
from iaf_provider import submit, write_file

write_file(dns, "providers/team-platform/dns-records.yaml")
submit(dns)    # IAF_PLATFORM_URL and a service-account token in IAF_PLATFORM_TOKEN
```

## Handlers

A handler is `(call: Call, input) -> output`, a plain function or a coroutine. Plain functions run
on a thread pool (`run_worker` makes one; give `new_worker` an `activity_executor`), coroutines on
the worker's event loop.

| | |
|---|---|
| `call.idempotency_key` | the same for every retry of the step and for a worker that picks the call up again; new for a new run of it. Pass it to the system you call, or keep it with what you create. |
| `call.job`, `call.step`, `call.environment`, `call.attempt` | the job (id, workflow, version, trace id), the step id, the environment and the step's attempt |
| `call.heartbeat(*details)` | tell the platform the call is alive; call it more often than the heartbeat timeout during long work. The worker learns through heartbeats that the step was cancelled; it then cancels a coroutine (`asyncio.CancelledError`) or raises `temporalio.exceptions.CancelledError` in a thread: let both propagate. |
| `raise retryable("dns_busy", "...")` | a failure the step's retry policy may retry |
| `raise fatal("record_conflict", "...")` | a failure no retry fixes: the step fails at once |

Any other exception is reported as the retryable class `error`. The worker checks the input
against the action's schema (and reads it into the model) before the handler runs and checks the
output after it; a call that breaks the contract fails for good with `invalid_input`,
`invalid_output`, `invalid_request` or `unknown_action`.

An action can name another one as its preview with `plan="record.preview"`, and give the answer
the platform's demo mode returns for it with `sample=` (text in it may use expressions like
`{{ $input.name }}`). `input=` and `output=` replace a derived schema.

## Configuration

| Variable | Default | |
|---|---|---|
| `TEMPORAL_ADDRESS` | `127.0.0.1:7233` | the Temporal frontend |
| `TEMPORAL_NAMESPACE` | `default` | the namespace the platform runs in |
| `WORKER_DRAIN_SECONDS` | `30` | how long a stopping worker lets calls in flight finish; a value that is not a whole number of seconds is logged and falls back to 30 |
| `IAF_PLATFORM_URL` | `http://localhost:8080` | the platform, for `submit` |
| `IAF_PLATFORM_TOKEN` | | a service-account token (`iaf_...`), for `submit`; sent, never printed |

To connect with TLS or an API key, connect your own `temporalio.client.Client` and use
`new_worker(client, provider, activity_executor=...)`.

## Testing

`activity_for(provider, action_id)` gives one action's activity; run it with Temporal's
`ActivityEnvironment` and an envelope, the payload the platform sends:

```python
from temporalio.testing import ActivityEnvironment
from iaf_provider import activity_for, envelope

record = await ActivityEnvironment().run(activity_for(dns, "record.create"),
                                         envelope({"zone": "lab.example.internal", "name": "web-01", "value": "10.30.0.2"}))
```

A classified failure arrives as a `temporalio.exceptions.ApplicationError` whose `type` is the
error class and whose `non_retryable` is set for a fatal one. `envelope(input, attempt=2)`
overrides a field of the payload. See
[examples/dns_records/test_zones.py](examples/dns_records/test_zones.py).

```bash
pip install -e './sdk/python[test]'
cd sdk/python && pytest
TEMPORAL_CLI=$(which temporal) pytest tests/test_dev_server.py   # also against a Temporal dev server
```

## The example

`examples/dns_records` keeps records in three zones: `record.get`, `record.preview`,
`record.create` (async, a create whose preview is `record.preview`, idempotent by the call's key,
heartbeating while the zone's secondaries catch up) and `record.delete`. The sample workflow
`reserve-ip-address` reserves an address with the Go SDK's example and publishes its record with
this one.

```bash
cd sdk/python
python examples/dns_records/main.py publish ../../providers/team-platform/dns-records.yaml  # write the provider file
python examples/dns_records/main.py run                                                     # serve provider-dns-records

docker build -f examples/dns_records/Dockerfile -t dns-records-provider .                   # from sdk/python
```
