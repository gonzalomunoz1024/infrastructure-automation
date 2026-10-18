from __future__ import annotations

import asyncio
from datetime import timedelta
from typing import Any

import pytest
from temporalio.common import RawValue
from temporalio.converter import DataConverter
from temporalio.exceptions import ApplicationError
from temporalio.testing import ActivityEnvironment

from iaf_provider import (
    Call,
    Effect,
    Job,
    Provider,
    WorkerOptions,
    activities,
    activity_for,
    envelope,
    new_worker,
    retryable,
)

from inventory import Inventory


def probe(handler, **action: Any) -> Provider:
    p = Provider("probe", owner="team-platform", version="1.0.0")
    p.action("probe.run", effect=Effect.READ, **action)(handler)
    return p


def run(provider: Provider, action: str, payload: Any, env: ActivityEnvironment | None = None) -> Any:
    return (env or ActivityEnvironment()).run(activity_for(provider, action), payload)


def failure(provider: Provider, action: str, payload: Any) -> ApplicationError:
    with pytest.raises(ApplicationError) as e:
        run(provider, action, payload)
    return e.value


def test_a_call_runs_the_action_and_answers_its_output():
    out = run(Inventory().provider(), "item.get", envelope({"id": "it-1"}))

    assert out == {"id": "it-1", "name": "first", "state": "active"}


async def test_an_async_handler_runs_the_same_way():
    async def get(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        await asyncio.sleep(0)
        return {"echo": value}

    out = await run(probe(get), "probe.run", envelope({"a": 1}))

    assert out == {"echo": {"a": 1}}


def test_the_handler_sees_the_facts_of_the_envelope():
    seen: list[Call] = []

    def handler(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        seen.append(call)
        return value

    payload = {
        "input": {"a": "b"},
        "idempotencyKey": "job-7:create:3",
        "job": {"id": "job-7", "workflow": "add-item", "version": "1.0.0", "traceId": None},
        "step": "create",
        "environment": "dev",
        "attempt": 2,
    }

    assert run(probe(handler), "probe.run", payload) == {"a": "b"}
    call = seen[0]
    assert call.action == "probe.run"
    assert call.idempotency_key == "job-7:create:3"
    assert call.job == Job("job-7", "add-item", "1.0.0", None)
    assert (call.step, call.environment, call.attempt) == ("create", "dev", 2)


def test_a_repeated_call_with_the_same_key_answers_the_first_result():
    inv = Inventory()
    p = inv.provider()

    first = run(p, "item.create", envelope({"name": "second"}))
    again = run(p, "item.create", envelope({"name": "second"}, attempt=2))

    assert again == first
    assert len(inv.items) == 2


def test_a_fatal_failure_reaches_the_platform_as_a_non_retryable_error_of_its_class():
    e = failure(Inventory().provider(), "item.get", envelope({"id": "it-404"}))

    assert (e.type, e.non_retryable, e.message) == ("not_found", True, "No item it-404")


def test_a_retryable_failure_reaches_the_platform_as_a_retryable_error_of_its_class():
    def busy(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        raise retryable("busy", "The inventory is busy")

    e = failure(probe(busy), "probe.run", envelope())

    assert (e.type, e.non_retryable, e.message) == ("busy", False, "The inventory is busy")


def test_an_unclassified_exception_is_the_retryable_class_error():
    def broken(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        raise ConnectionError("connection reset")

    e = failure(probe(broken), "probe.run", envelope())

    assert (e.type, e.non_retryable, e.message) == ("error", False, "connection reset")


@pytest.mark.parametrize(
    "payload, error_class",
    [
        (envelope({"name": "no id"}), "invalid_input"),
        (envelope({"id": 42}), "invalid_input"),
        ("it-1", "invalid_request"),
        ({"input": "it-1"}, "invalid_request"),
    ],
    ids=["input that breaks the schema", "input of the wrong type", "not the envelope", "input not an object"],
)
def test_a_broken_contract_is_fatal(payload, error_class):
    e = failure(Inventory().provider(), "item.get", payload)

    assert (e.type, e.non_retryable) == (error_class, True)


async def test_an_action_the_provider_lacks_is_unknown():
    unknown = activities(Inventory().provider())[-1]
    payload = DataConverter.default.payload_converter.to_payloads([envelope()])[0]

    with pytest.raises(ApplicationError) as e:
        await ActivityEnvironment().run(unknown, [RawValue(payload)])

    assert (e.value.type, e.value.non_retryable) == ("unknown_action", True)


def test_output_that_breaks_the_schema_is_fatal():
    def half(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        return {"id": "it-9"}

    e = failure(probe(half, output={"type": "object", "required": ["id", "name"]}), "probe.run", envelope())

    assert (e.type, e.non_retryable) == ("invalid_output", True)
    assert "name" in e.message


def test_a_handler_that_returns_nothing_answers_an_empty_object():
    def nothing(call: Call, value: dict[str, Any]) -> None:
        return None

    assert run(probe(nothing), "probe.run", envelope({"a": 1})) == {}


async def test_heartbeats_reach_temporal():
    def scan(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        call.heartbeat("scanned 10 of 20")
        return value

    async def scan_async(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        call.heartbeat("scanned 20 of 20")
        return value

    beats: list[Any] = []
    env = ActivityEnvironment()
    env.on_heartbeat = lambda *details: beats.append(details)

    run(probe(scan), "probe.run", envelope(), env)
    await run(probe(scan_async), "probe.run", envelope(), env)

    assert beats == [("scanned 10 of 20",), ("scanned 20 of 20",)]


async def test_cancelling_the_step_cancels_an_async_handler():
    started = asyncio.Event()
    cancelled = asyncio.Event()

    async def long(call: Call, value: dict[str, Any]) -> dict[str, Any]:
        started.set()
        try:
            for i in range(200):
                call.heartbeat(i)
                await asyncio.sleep(0.02)
        except asyncio.CancelledError:
            cancelled.set()
            raise
        return value

    env = ActivityEnvironment()
    task = asyncio.ensure_future(run(probe(long), "probe.run", envelope(), env))
    await started.wait()

    env.cancel()

    with pytest.raises(asyncio.CancelledError):
        await task
    assert cancelled.is_set()


def test_options_come_from_the_environment():
    assert WorkerOptions.from_env({}) == WorkerOptions()
    assert WorkerOptions.from_env(
        {"TEMPORAL_ADDRESS": "temporal-frontend.temporal:7233", "TEMPORAL_NAMESPACE": "platform", "WORKER_DRAIN_SECONDS": "90"}
    ) == WorkerOptions(address="temporal-frontend.temporal:7233", namespace="platform", drain_timeout=timedelta(seconds=90))


def test_sync_handlers_need_a_thread_pool_to_run_on():
    with pytest.raises(ValueError, match="activity_executor"):
        new_worker(None, Inventory().provider())  # type: ignore[arg-type]


def test_an_invalid_provider_serves_nothing():
    with pytest.raises(ValueError, match="owner is the team"):
        activities(Provider("orphan", owner="", version="1.0.0"))
