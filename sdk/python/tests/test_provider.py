from __future__ import annotations

import json
from datetime import timedelta
from typing import Any

import pytest
import yaml

from iaf_provider import Call, Effect, Provider, format_duration

from inventory import Inventory


def echo(call: Call, value: dict[str, Any]) -> dict[str, Any]:
    return value


def to_text(call: Call, value: str) -> str:
    return value


def test_a_provider_describes_itself_as_the_provider_file_the_platform_reads():
    doc = Inventory().provider().document()

    assert doc["apiVersion"] == "iaf.platform/v1"
    assert doc["kind"] == "Provider"
    assert doc["metadata"] == {
        "name": "inventory",
        "title": "Inventory",
        "owner": "team-platform",
        "version": "1.2.0",
        "description": "Items the tests keep in memory",
    }
    spec = doc["spec"]
    assert spec["type"] == "sdk"
    assert spec["worker"] == {"taskQueue": "provider-inventory", "heartbeatTimeout": "30s"}
    assert [a["id"] for a in spec["actions"]] == ["item.get", "item.preview", "item.create"]
    create = spec["actions"][2]
    assert create["effect"] == "create"
    assert create["plan"] == "item.preview"
    assert create["input"]["required"] == ["name"]
    assert create["output"]["properties"]["state"] == {"type": "string", "enum": ["active", "retired"]}
    get = spec["actions"][0]
    assert get["input"] == {
        "type": "object",
        "properties": {"id": {"type": "string", "description": "The item id"}},
        "required": ["id"],
    }
    assert get["sample"]["id"] == "{{ $input.id }}"
    assert spec["errors"][0] == {"class": "not_found", "retry": False, "description": "No item has that id"}
    assert spec["errors"][1]["retry"] is True
    assert spec["alerts"] == {"channel": "#platform-alerts"}


def test_the_yaml_reads_back_as_the_same_document_in_the_same_order():
    p = Inventory().provider()

    text = p.to_yaml()

    assert text.startswith("apiVersion: iaf.platform/v1\nkind: Provider\nmetadata:\n  name: inventory\n")
    assert text.index("type: object") < text.index("properties:")
    assert "  actions:\n    - id: item.get\n" in text
    assert yaml.safe_load(text) == json.loads(json.dumps(p.document()))


def test_the_task_queue_defaults_to_provider_dash_name_and_can_be_named():
    assert Inventory().provider().task_queue == "provider-inventory"
    assert Inventory().provider(task_queue="inventory-eu").task_queue == "inventory-eu"


def test_without_a_heartbeat_timeout_the_worker_block_only_names_the_queue():
    doc = Inventory().provider(heartbeat_timeout=None).document()

    assert doc["spec"]["worker"] == {"taskQueue": "provider-inventory"}


def test_durations_are_written_the_way_provider_files_spell_them():
    assert format_duration(timedelta(seconds=30)) == "30s"
    assert format_duration(timedelta(minutes=2)) == "2m"
    assert format_duration(timedelta(hours=1)) == "1h"
    assert format_duration(timedelta(milliseconds=1500)) == "1500ms"


def test_every_problem_is_listed_at_once():
    p = Provider("Bad Name", owner="", version="1.0", task_queue="no spaces allowed", heartbeat_timeout=timedelta(milliseconds=200))
    p.error("not a class", retryable=False)
    p.action("item.get", title="Get", effect="melt")(echo)
    p.action("item.list", effect=Effect.READ)(echo)
    p.action("item.list", effect=Effect.READ)(echo)
    p.action("itemcreate", effect=Effect.CREATE, plan="item.preview")(echo)

    with pytest.raises(ValueError) as e:
        p.validate()

    for want in [
        "The name is lowercase letters",
        "owner is the team",
        "version is semantic",
        "The task queue is letters",
        "heartbeat_timeout is at least one second",
        "Action item.get: 'melt' is not a valid Effect",
        "Action id item.list is used twice",
        "Action ids read as <noun>.<verb>",
        "The plan of itemcreate names another action",
        "An error class is a word",
    ]:
        assert want in str(e.value)


def test_an_action_that_changes_something_writes_change():
    # CHANGE, and the old spelling update (the deprecated alias or the string) all write change.
    for effect in (Effect.CHANGE, Effect.UPDATE, "update", "change"):
        p = Provider("probe", owner="team-platform", version="1.0.0")
        p.action("item.rename", effect=effect)(echo)

        assert p.validate().document()["spec"]["actions"][0]["effect"] == "change"
    assert Effect("update") is Effect.CHANGE
    assert [e.value for e in Effect] == ["read", "create", "change", "delete"]


def test_a_provider_offers_at_least_one_action():
    with pytest.raises(ValueError, match="A provider offers at least one action"):
        Provider("empty", owner="team-platform", version="1.0.0").validate()


def test_inputs_and_outputs_are_json_objects():
    p = Provider("strings", owner="team-platform", version="1.0.0")
    p.action("text.echo", effect=Effect.READ)(to_text)

    problems = p.check()

    assert "The input of text.echo is a JSON object; use a model" in problems
    assert "The output of text.echo is a JSON object; use a model" in problems


def test_an_explicit_schema_replaces_the_derived_one():
    schema = {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}
    p = Provider("search", owner="team-platform", version="1.0.0")
    p.action("item.search", effect=Effect.READ, input=schema)(echo)

    assert p.get_action("item.search").input_schema == schema
    assert p.get_action("item.search").output_schema == {"type": "object"}
    assert p.get_action("item.nope") is None
