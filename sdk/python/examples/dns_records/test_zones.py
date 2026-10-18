from __future__ import annotations

import asyncio
from datetime import timedelta
from typing import Any

import pytest
from temporalio.exceptions import ApplicationError
from temporalio.testing import ActivityEnvironment

from iaf_provider import activity_for, envelope

from zones import Zones, default_zones

LAB = "lab.example.internal"


@pytest.fixture
def zones() -> Zones:
    return Zones(default_zones(), propagation=timedelta(0))


def call(zones: Zones, action: str, input: dict[str, Any], env: ActivityEnvironment | None = None, **overrides: Any) -> Any:
    return (env or ActivityEnvironment()).run(activity_for(zones.provider(), action), envelope(input, **overrides))


async def failure(zones: Zones, action: str, input: dict[str, Any]) -> ApplicationError:
    with pytest.raises(ApplicationError) as e:
        result = call(zones, action, input)
        if asyncio.iscoroutine(result):
            await result
    return e.value


def test_the_provider_passes_its_own_checks(zones: Zones):
    p = zones.provider()

    assert p.check() == []
    assert [a.id for a in p.actions] == ["record.get", "record.preview", "record.create", "record.delete"]
    assert p.document()["spec"]["worker"] == {"taskQueue": "provider-dns-records", "heartbeatTimeout": "30s"}


async def test_creating_a_record_answers_it_and_get_finds_it(zones: Zones):
    created = await call(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"})

    assert created == {"fqdn": f"web-01.{LAB}", "zone": LAB, "name": "web-01", "type": "A", "value": "10.30.0.2", "ttl": 300}
    assert call(zones, "record.get", {"zone": LAB, "name": "web-01"}) == created


async def test_creating_heartbeats_while_the_secondaries_catch_up(zones: Zones):
    beats: list[Any] = []
    env = ActivityEnvironment()
    env.on_heartbeat = lambda *details: beats.append(details[0])

    await call(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"}, env)

    assert beats == [f"web-01.{LAB}: {n} of 3 secondaries updated" for n in (1, 2, 3)]


async def test_a_retried_create_with_the_same_key_answers_the_first_record(zones: Zones):
    first = await call(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"})
    zones.records[LAB].clear()  # even if the record was changed by hand since

    again = await call(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"}, attempt=2)

    assert again == first


async def test_a_name_with_a_different_record_is_a_conflict_no_retry_fixes(zones: Zones):
    await call(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"}, idempotencyKey="job-1:dns:1")

    e = await failure(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.3"})

    assert (e.type, e.non_retryable) == ("record_conflict", True)
    assert "10.30.0.2" in e.message


async def test_a_busy_zone_is_retryable(zones: Zones):
    zones.locks[LAB].acquire()
    try:
        e = await failure(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"})
    finally:
        zones.locks[LAB].release()

    assert (e.type, e.non_retryable) == ("dns_busy", False)


async def test_a_zone_the_server_lacks_is_fatal(zones: Zones):
    e = await failure(zones, "record.get", {"zone": "nowhere.example.internal", "name": "web-01"})

    assert (e.type, e.non_retryable) == ("zone_not_found", True)


@pytest.mark.parametrize(
    "record",
    [
        {"type": "A", "value": "web-02"},
        {"type": "AAAA", "value": "10.30.0.2"},
        {"type": "CNAME", "value": "not a host"},
    ],
    ids=["A without an IPv4 address", "AAAA without an IPv6 address", "CNAME without a host name"],
)
async def test_a_value_that_does_not_fit_the_type_is_invalid_input(zones: Zones, record: dict[str, str]):
    e = await failure(zones, "record.create", {"zone": LAB, "name": "web-01", **record})

    assert (e.type, e.non_retryable) == ("invalid_input", True)


async def test_preview_says_what_create_would_do(zones: Zones):
    new = {"zone": LAB, "name": "web-01", "value": "10.30.0.2"}

    before = call(zones, "record.preview", new)
    await call(zones, "record.create", new)
    after = call(zones, "record.preview", new)

    assert before == {"action": "create", "fqdn": f"web-01.{LAB}", "summary": f"Creates A web-01.{LAB} -> 10.30.0.2 (ttl 300s)"}
    assert after["action"] == "none"


async def test_delete_removes_the_record_and_is_safe_to_repeat(zones: Zones):
    await call(zones, "record.create", {"zone": LAB, "name": "web-01", "value": "10.30.0.2"})

    first = call(zones, "record.delete", {"zone": LAB, "name": "web-01"})
    again = call(zones, "record.delete", {"zone": LAB, "name": "web-01"})

    assert first == {"fqdn": f"web-01.{LAB}", "deleted": True}
    assert again == {"fqdn": f"web-01.{LAB}", "deleted": False}
    assert (await failure(zones, "record.get", {"zone": LAB, "name": "web-01"})).type == "record_not_found"
