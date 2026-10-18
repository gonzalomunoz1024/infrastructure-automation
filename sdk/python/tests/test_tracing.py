"""A call continues the platform's trace: the traceparent reaches the handler, and with the
OpenTelemetry API installed the handler runs in a span that continues the platform's step."""

from __future__ import annotations

import contextlib
from typing import Any

import pytest
from opentelemetry import trace
from temporalio.exceptions import ApplicationError
from temporalio.testing import ActivityEnvironment

from iaf_provider import Call, Effect, Provider, activity_for, envelope, fatal
from iaf_provider import _worker

TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
STEP_SPAN = "b7ad6b7169203331"
TRACEPARENT = f"00-{TRACE_ID}-{STEP_SPAN}-01"


class RecordedSpan(trace.Span):
    """A span that keeps what the worker told it."""

    def __init__(self, name: str, kind: trace.SpanKind, attributes: dict[str, Any], parent: trace.SpanContext) -> None:
        self.name, self.kind, self.attributes, self.parent = name, kind, attributes, parent
        self.status: trace.Status | None = None
        self.exceptions: list[BaseException] = []
        self.ended = False
        self._context = trace.SpanContext(parent.trace_id, 0x00F067AA0BA902B7, is_remote=False, trace_flags=parent.trace_flags)

    def get_span_context(self) -> trace.SpanContext:
        return self._context

    def set_attributes(self, attributes: Any) -> None:
        self.attributes.update(attributes)

    def set_attribute(self, key: str, value: Any) -> None:
        self.attributes[key] = value

    def add_event(self, name: str, attributes: Any = None, timestamp: Any = None) -> None:
        pass

    def update_name(self, name: str) -> None:
        self.name = name

    def is_recording(self) -> bool:
        return not self.ended

    def set_status(self, status: Any, description: str | None = None) -> None:
        self.status = status

    def record_exception(self, exception: BaseException, attributes: Any = None, timestamp: Any = None, escaped: bool = False) -> None:
        self.exceptions.append(exception)

    def end(self, end_time: Any = None) -> None:
        self.ended = True


class Recorder(trace.Tracer):
    """A tracer that records the spans it starts, as an OpenTelemetry SDK would export them."""

    def __init__(self) -> None:
        self.spans: list[RecordedSpan] = []

    def start_span(self, name: str, context: Any = None, kind: trace.SpanKind = trace.SpanKind.INTERNAL, attributes: Any = None, *args: Any, **kwargs: Any) -> RecordedSpan:
        span = RecordedSpan(name, kind, dict(attributes or {}), trace.get_current_span(context).get_span_context())
        self.spans.append(span)
        return span

    @contextlib.contextmanager
    def start_as_current_span(self, name: str, context: Any = None, kind: trace.SpanKind = trace.SpanKind.INTERNAL, attributes: Any = None, *args: Any, **kwargs: Any) -> Any:
        span = self.start_span(name, context, kind, attributes)
        with trace.use_span(span, end_on_exit=True, record_exception=False, set_status_on_exception=False) as s:
            yield s


def probe(seen: list[Any], fail: bool = False) -> Provider:
    p = Provider("probe", owner="team-platform", version="1.4.0")
    p.error("quota_exceeded", retryable=False, description="No room left")

    @p.action("probe.run", effect=Effect.READ)
    def run(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        current = trace.get_current_span().get_span_context()
        seen.append((call.traceparent, format(current.trace_id, "032x"), format(current.span_id, "016x")))
        if fail:
            raise fatal("quota_exceeded", "No room left for team-x")
        return value

    return p


def call_with(traceparent: str | None, where: str = "job") -> dict[str, Any]:
    payload = envelope({"a": "b"}, idempotencyKey="job-7:run:1", step="run", attempt=2)
    payload["job"]["id"] = "job-7"
    if traceparent is not None and where == "job":
        payload["job"]["traceparent"] = traceparent
    elif traceparent is not None:
        payload["traceparent"] = traceparent
    return payload


@pytest.fixture
def recorder(monkeypatch: pytest.MonkeyPatch) -> Recorder:
    r = Recorder()
    monkeypatch.setattr(_worker, "_tracer", lambda: r)
    return r


def test_the_traceparent_reaches_the_handler_from_the_job_or_the_top_of_the_payload():
    seen: list[Any] = []
    p = probe(seen)

    for payload in (call_with(TRACEPARENT), call_with(TRACEPARENT, where="top"), call_with(None)):
        ActivityEnvironment().run(activity_for(p, "probe.run"), payload)

    assert [s[0] for s in seen] == [TRACEPARENT, TRACEPARENT, None]


def test_with_the_opentelemetry_api_alone_the_handler_runs_inside_the_platforms_step():
    # No SDK configured: the API's no-op tracer makes the platform's step span the current one.
    seen: list[Any] = []

    ActivityEnvironment().run(activity_for(probe(seen), "probe.run"), call_with(TRACEPARENT))

    assert seen == [(TRACEPARENT, TRACE_ID, STEP_SPAN)]


def test_the_call_runs_in_a_span_of_its_own_child_of_the_step(recorder: Recorder):
    seen: list[Any] = []

    out = ActivityEnvironment().run(activity_for(probe(seen), "probe.run"), call_with(TRACEPARENT))

    assert out == {"a": "b"}
    span = recorder.spans[0]
    assert len(recorder.spans) == 1
    assert span.name == "run probe.probe.run"
    assert span.kind == trace.SpanKind.SERVER
    assert (format(span.parent.trace_id, "032x"), format(span.parent.span_id, "016x"), span.parent.is_remote) == (TRACE_ID, STEP_SPAN, True)
    assert span.attributes == {
        "provider": "probe",
        "provider.version": "1.4.0",
        "action": "probe.run",
        "job.id": "job-7",
        "workflow": "test",
        "workflow.version": "1.0.0",
        "step": "run",
        "environment": "dev",
        "attempt": 2,
    }
    assert span.ended and span.status is None
    assert seen == [(TRACEPARENT, TRACE_ID, "00f067aa0ba902b7")], "the handler's current span is the call's"


def test_a_failed_call_marks_its_span_with_the_error_class(recorder: Recorder):
    with pytest.raises(ApplicationError):
        ActivityEnvironment().run(activity_for(probe([], fail=True), "probe.run"), call_with(TRACEPARENT))

    span = recorder.spans[0]
    assert span.ended
    assert span.status is not None and span.status.status_code == trace.StatusCode.ERROR
    assert span.status.description == "quota_exceeded"
    assert span.attributes["error.class"] == "quota_exceeded"
    assert len(span.exceptions) == 1


def test_without_a_valid_traceparent_no_span_starts(recorder: Recorder):
    seen: list[Any] = []
    p = probe(seen)
    broken = ["not-a-traceparent", f"00-{'0' * 32}-{STEP_SPAN}-01", f"ff-{TRACE_ID}-{STEP_SPAN}-01", TRACEPARENT.upper()]

    for traceparent in [None, *broken]:
        ActivityEnvironment().run(activity_for(p, "probe.run"), call_with(traceparent))

    assert recorder.spans == []
    assert [s[0] for s in seen] == [None, *broken], "what the platform sent reaches the handler"
    assert not any(_worker.valid_traceparent(t) for t in broken)


def test_without_the_opentelemetry_api_the_call_runs_and_logs_the_traceparent(monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture):
    monkeypatch.setattr(_worker, "_otel", None)
    seen: list[Any] = []

    with caplog.at_level("DEBUG", logger="iaf_provider"):
        out = ActivityEnvironment().run(activity_for(probe(seen), "probe.run"), call_with(TRACEPARENT))

    assert out == {"a": "b"}
    assert seen[0][0] == TRACEPARENT
    assert f"call probe.run of job job-7 carries the platform's trace {TRACEPARENT}" in caplog.text
