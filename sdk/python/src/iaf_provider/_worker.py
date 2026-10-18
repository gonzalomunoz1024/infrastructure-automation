"""The worker: serves a provider's actions on its Temporal task queue."""

from __future__ import annotations

import asyncio
import concurrent.futures
import contextlib
import logging
import os
import re
import signal
from dataclasses import dataclass, field
from datetime import timedelta
from collections.abc import Callable, Mapping, Sequence
from typing import Any

from pydantic import ValidationError
from temporalio import activity
from temporalio.client import Client
from temporalio.common import RawValue
from temporalio.exceptions import ApplicationError, CancelledError
from temporalio.service import TLSConfig
from temporalio.worker import Worker

from ._errors import (
    INVALID_INPUT,
    INVALID_OUTPUT,
    INVALID_REQUEST,
    UNCLASSIFIED,
    UNKNOWN_ACTION,
    ProviderError,
    fatal,
)
from ._provider import Action, Provider
from ._schema import problems

try:  # optional: pip install 'iaf-provider-sdk[otel]'
    from opentelemetry import trace as _otel
    from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator as _TraceContext
except ImportError:  # pragma: no cover - the worker runs without it
    _otel = None  # type: ignore[assignment]

log = logging.getLogger("iaf_provider")

#: version-traceid-spanid-flags in lowercase hex
_TRACEPARENT = re.compile(r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$")


@dataclass(frozen=True)
class Job:
    """The platform job a call belongs to."""

    id: str | None = None
    workflow: str | None = None
    version: str | None = None
    trace_id: str | None = None


@dataclass
class Call:
    """What a handler knows about the call it serves."""

    #: The action being run (the activity type).
    action: str
    #: The same for every retry of the step (and for a worker that picks the call up again after a
    #: crash), new for a new run of it. Pass it to the system you call as its idempotency or
    #: request id, or keep it with what you create.
    idempotency_key: str | None
    #: The job the call belongs to.
    job: Job
    #: The id of the workflow step that made the call.
    step: str | None
    #: The environment the job runs against (dev, prod); may be None.
    environment: str | None
    #: The step's attempt, from 1; a retry by the step's retry policy counts up.
    attempt: int
    #: The W3C trace context of the platform's step span (``00-<trace id>-<span id>-<flags>``), None
    #: when the platform sent none. With the OpenTelemetry API installed the handler runs in a span
    #: that continues it.
    traceparent: str | None = None
    _heartbeat: Callable[..., None] = field(default=lambda *details: None, repr=False)

    def heartbeat(self, *details: Any) -> None:
        """Tell the platform the call is alive, with optional progress details.

        Call it more often than the provider's heartbeat timeout during long work. When the step
        is cancelled, an async handler is cancelled (``asyncio.CancelledError``) and a sync one
        gets a ``temporalio.exceptions.CancelledError`` raised in its thread: let both propagate.
        """
        self._heartbeat(*details)


def envelope(input: Any = None, **overrides: Any) -> dict[str, Any]:
    """The payload the platform sends, as a test makes it: job ``test-job``, step ``step``,
    environment ``dev``, attempt 1. ``overrides`` replace top-level fields (``idempotencyKey``,
    ``attempt``..., and ``traceparent``, which the worker reads there as well as in the job)."""
    from pydantic import TypeAdapter

    payload = {
        "input": {} if input is None else TypeAdapter(Any).dump_python(input, mode="json"),
        "idempotencyKey": "test-job:step:1",
        "job": {"id": "test-job", "workflow": "test", "version": "1.0.0", "traceId": None},
        "step": "step",
        "environment": "dev",
        "attempt": 1,
    }
    payload.update(overrides)
    return payload


def _read(action: str, payload: Any) -> tuple[Call, dict[str, Any]]:
    if not isinstance(payload, Mapping):
        raise fatal(INVALID_REQUEST, f"The call to {action} is not the platform's envelope (a JSON object with input)")
    raw_input = payload.get("input")
    if raw_input is None:
        raw_input = {}
    if not isinstance(raw_input, Mapping):
        raise fatal(INVALID_REQUEST, f"The input of {action} is a JSON object")
    job = payload.get("job") if isinstance(payload.get("job"), Mapping) else {}
    attempt = payload.get("attempt")
    traceparent = job.get("traceparent") or payload.get("traceparent")
    call = Call(
        action=action,
        idempotency_key=payload.get("idempotencyKey"),
        job=Job(job.get("id"), job.get("workflow"), job.get("version"), job.get("traceId")),
        step=payload.get("step"),
        environment=payload.get("environment"),
        attempt=max(1, attempt) if isinstance(attempt, int) else 1,
        traceparent=traceparent if isinstance(traceparent, str) else None,
    )
    return call, dict(raw_input)


def valid_traceparent(value: str | None) -> bool:
    """Whether the value is W3C trace context a span can continue: the right shape, a version other
    than ff, and trace and span ids that are not all zeros."""
    m = _TRACEPARENT.match(value or "")
    return bool(m) and m.group(1) != "ff" and m.group(2).strip("0") != "" and m.group(3).strip("0") != ""


def _tracer() -> Any:
    return _otel.get_tracer("iaf_provider")


def _error_class(e: BaseException) -> str:
    if isinstance(e, ProviderError):
        return e.error_class
    if isinstance(e, ApplicationError):
        return e.type or UNCLASSIFIED
    if isinstance(e, (CancelledError, asyncio.CancelledError)):
        return "cancelled"
    return UNCLASSIFIED


@contextlib.contextmanager
def _traced(provider: Provider, a: Action, call: Call) -> Any:
    """Continues the platform's trace around one call: a span ``run <provider>.<action>``, child of
    the step span the traceparent names, current while the handler runs, when the OpenTelemetry API
    is installed (a no-op span until the program configures the OpenTelemetry SDK); the traceparent
    in the debug log otherwise. Without a valid traceparent the call runs as it is."""
    if not valid_traceparent(call.traceparent):
        yield
        return
    if _otel is None:
        log.debug("call %s of job %s carries the platform's trace %s", a.id, call.job.id, call.traceparent)
        yield
        return
    parent = _TraceContext().extract({"traceparent": call.traceparent})
    attributes = {
        "provider": provider.name,
        "provider.version": provider.version,
        "action": a.id,
        "job.id": call.job.id,
        "workflow": call.job.workflow,
        "workflow.version": call.job.version,
        "step": call.step,
        "environment": call.environment,
        "attempt": call.attempt,
    }
    with _tracer().start_as_current_span(
        f"run {provider.name}.{a.id}",
        context=parent,
        kind=_otel.SpanKind.SERVER,
        attributes={k: v for k, v in attributes.items() if v is not None},
        record_exception=False,
        set_status_on_exception=False,
    ) as span:
        try:
            yield
        except BaseException as e:
            error_class = _error_class(e)
            span.set_attribute("error.class", error_class)
            span.record_exception(e)
            span.set_status(_otel.Status(_otel.StatusCode.ERROR, error_class))
            raise


def _check_input(a: Action, value: dict[str, Any]) -> Any:
    found = problems(a.input_check, value)
    if found:
        raise fatal(INVALID_INPUT, f"The input of {a.id} does not match its schema: " + "; ".join(found))
    try:
        return a.read_input(value)
    except ValidationError as e:
        raise fatal(INVALID_INPUT, f"The input of {a.id} cannot be read: {e}") from e


def _check_output(a: Action, value: Any) -> dict[str, Any]:
    try:
        out = a.write_output(value)
    except Exception as e:
        raise fatal(INVALID_OUTPUT, f"The output of {a.id} cannot be written as JSON: {e}") from e
    found = problems(a.output_check, out)
    if found:
        raise fatal(INVALID_OUTPUT, f"The output of {a.id} does not match its schema: " + "; ".join(found))
    return out


def _to_temporal(e: ProviderError) -> ApplicationError:
    return ApplicationError(e.message, type=e.error_class, non_retryable=not e.retryable)


def _unclassified(a: Action, e: Exception) -> ApplicationError:
    log.warning("action %s failed with an unclassified error (reported as the retryable class error)", a.id, exc_info=e)
    return ApplicationError(str(e) or type(e).__name__, type=UNCLASSIFIED, non_retryable=False)


def _sync_activity(provider: Provider, a: Action) -> Callable[[Any], Any]:
    def run(payload: Any) -> Any:
        try:
            call, raw = _read(a.id, payload)
            call._heartbeat = activity.heartbeat
            with _traced(provider, a, call):
                value = _check_input(a, raw)
                try:
                    out = a.handler(call, value)
                except (ProviderError, ApplicationError, CancelledError):
                    raise
                except Exception as e:
                    raise _unclassified(a, e) from e
                return _check_output(a, out)
        except ProviderError as e:
            raise _to_temporal(e) from e

    run.__name__ = run.__qualname__ = a.id.replace(".", "_")
    return activity.defn(name=a.id)(run)


def _async_activity(provider: Provider, a: Action) -> Callable[[Any], Any]:
    async def run(payload: Any) -> Any:
        try:
            call, raw = _read(a.id, payload)
            call._heartbeat = activity.heartbeat
            with _traced(provider, a, call):
                value = _check_input(a, raw)
                try:
                    out = await a.handler(call, value)
                except (ProviderError, ApplicationError, CancelledError, asyncio.CancelledError):
                    raise
                except Exception as e:
                    raise _unclassified(a, e) from e
                return _check_output(a, out)
        except ProviderError as e:
            raise _to_temporal(e) from e

    run.__name__ = run.__qualname__ = a.id.replace(".", "_")
    return activity.defn(name=a.id)(run)


def activities(provider: Provider) -> list[Callable[..., Any]]:
    """The activities that serve the provider: one per action, named by its id, plus a dynamic
    activity that answers any other name with ``unknown_action``. Register them on your own
    ``Worker`` (with an ``activity_executor`` when a handler is sync) or call one with
    ``temporalio.testing.ActivityEnvironment``."""
    provider.validate()
    out: list[Callable[..., Any]] = [_async_activity(provider, a) if a.is_async else _sync_activity(provider, a) for a in provider.actions]

    @activity.defn(dynamic=True)
    async def unknown(args: Sequence[RawValue]) -> Any:
        name = activity.info().activity_type
        raise ApplicationError(f"Provider {provider.name} has no action '{name}'", type=UNKNOWN_ACTION, non_retryable=True)

    out.append(unknown)
    return out


def activity_for(provider: Provider, action_id: str) -> Callable[..., Any]:
    """The activity of one action, to call with ``ActivityEnvironment().run(fn, envelope(...))``."""
    a = provider.get_action(action_id)
    if a is None:
        raise KeyError(f"Provider {provider.name} has no action '{action_id}'")
    return _async_activity(provider, a) if a.is_async else _sync_activity(provider, a)


@dataclass(frozen=True)
class WorkerOptions:
    """Where the worker finds Temporal, how it proves who it is there, and how long a stopping worker
    lets the calls in flight finish. A CA, a client certificate or an API key makes the connection
    TLS: the CA verifies the frontend (the system's CAs without one), the certificate and its key are
    the worker's identity for mutual TLS, the API key (Temporal Cloud) goes with every request as a
    bearer token."""

    address: str = "127.0.0.1:7233"
    namespace: str = "default"
    drain_timeout: timedelta = timedelta(seconds=30)
    #: A PEM file of the CA that signed the frontend's certificate.
    tls_ca: str | None = None
    #: PEM files of the worker's client certificate and its key, for mutual TLS; both or neither.
    tls_cert: str | None = None
    tls_key: str | None = None
    #: A Temporal Cloud API key; never in the repr.
    api_key: str | None = field(default=None, repr=False)

    @staticmethod
    def from_env(env: Mapping[str, str] | None = None) -> WorkerOptions:
        """``TEMPORAL_ADDRESS``, ``TEMPORAL_NAMESPACE`` and ``WORKER_DRAIN_SECONDS``, each falling back to
        the default (a drain time that is not a whole number of seconds is logged and falls back too, as
        in the other SDKs), and ``IAF_TEMPORAL_TLS_CA``, ``IAF_TEMPORAL_TLS_CERT``, ``IAF_TEMPORAL_TLS_KEY``
        and ``IAF_TEMPORAL_API_KEY`` for TLS and Temporal Cloud."""
        env = os.environ if env is None else env
        d = WorkerOptions()
        drain = (env.get("WORKER_DRAIN_SECONDS") or "").strip()
        drain_timeout = d.drain_timeout
        if drain.isdigit():
            drain_timeout = timedelta(seconds=int(drain))
        elif drain:
            log.warning(
                "WORKER_DRAIN_SECONDS is a whole number of seconds, not %r; the worker drains for %ds",
                drain,
                int(d.drain_timeout.total_seconds()),
            )
        return WorkerOptions(
            address=(env.get("TEMPORAL_ADDRESS") or "").strip() or d.address,
            namespace=(env.get("TEMPORAL_NAMESPACE") or "").strip() or d.namespace,
            drain_timeout=drain_timeout,
            tls_ca=(env.get("IAF_TEMPORAL_TLS_CA") or "").strip() or None,
            tls_cert=(env.get("IAF_TEMPORAL_TLS_CERT") or "").strip() or None,
            tls_key=(env.get("IAF_TEMPORAL_TLS_KEY") or "").strip() or None,
            api_key=(env.get("IAF_TEMPORAL_API_KEY") or "").strip() or None,
        )


def _read_file(path: str, variable: str) -> bytes:
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError as e:
        raise ValueError(f"Cannot read {path} ({variable}): {e}") from e


def _connect_options(options: WorkerOptions) -> dict[str, Any]:
    """The keyword arguments of ``Client.connect`` for these options: TLS when a CA, a client
    certificate or an API key asks for it, and the API key. A file that cannot be read fails here,
    naming the variable it comes from."""
    if bool(options.tls_cert) != bool(options.tls_key):
        raise ValueError(
            "IAF_TEMPORAL_TLS_CERT and IAF_TEMPORAL_TLS_KEY go together: the worker's client certificate and its key, for mutual TLS"
        )
    tls: bool | TLSConfig = False
    if options.tls_ca or options.tls_cert:
        tls = TLSConfig(
            server_root_ca_cert=_read_file(options.tls_ca, "IAF_TEMPORAL_TLS_CA") if options.tls_ca else None,
            client_cert=_read_file(options.tls_cert, "IAF_TEMPORAL_TLS_CERT") if options.tls_cert else None,
            client_private_key=_read_file(options.tls_key, "IAF_TEMPORAL_TLS_KEY") if options.tls_key else None,
        )
    elif options.api_key:
        tls = True
    return {"namespace": options.namespace, "tls": tls, "api_key": options.api_key}


def _connection(options: WorkerOptions) -> str:
    """How the worker connects, for its start-up line: never the key itself."""
    transport = "mutual TLS" if options.tls_cert else "TLS" if options.tls_ca or options.api_key else "plaintext"
    return f"{transport} with an API key" if options.api_key else transport


def new_worker(
    client: Client,
    provider: Provider,
    *,
    activity_executor: concurrent.futures.Executor | None = None,
    drain_timeout: timedelta = timedelta(seconds=30),
) -> Worker:
    """A worker on the provider's task queue with its actions registered, on a client you built
    (interceptors, a data converter). Sync handlers run on ``activity_executor``."""
    acts = activities(provider)
    needs_executor = any(not a.is_async for a in provider.actions)
    if needs_executor and activity_executor is None:
        raise ValueError("Sync handlers run on threads: pass activity_executor=concurrent.futures.ThreadPoolExecutor(...)")
    return Worker(
        client,
        task_queue=provider.task_queue,
        activities=acts,
        activity_executor=activity_executor,
        graceful_shutdown_timeout=drain_timeout,
    )


async def run_worker(provider: Provider, options: WorkerOptions | None = None, *, stop: asyncio.Event | None = None) -> None:
    """Connect to Temporal (TLS and an API key when the options say so) and serve the provider until
    SIGINT or SIGTERM (or ``stop`` is set), then stop polling and let the calls in flight finish for
    the drain timeout."""
    options = options or WorkerOptions.from_env()
    provider.validate()
    client = await Client.connect(options.address, **_connect_options(options))
    stop = stop or asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except (NotImplementedError, RuntimeError):  # not on the main thread, or not on this platform
            pass
    with concurrent.futures.ThreadPoolExecutor(max_workers=32, thread_name_prefix=f"provider-{provider.name}") as executor:
        worker = new_worker(client, provider, activity_executor=executor, drain_timeout=options.drain_timeout)
        async with worker:
            log.info(
                "provider %s %s serves %d action(s) on task queue %s at %s (namespace %s, %s)",
                provider.name,
                provider.version,
                len(provider.actions),
                provider.task_queue,
                options.address,
                options.namespace,
                _connection(options),
            )
            await stop.wait()
            log.info("provider %s stops polling and drains for up to %s", provider.name, options.drain_timeout)
