from __future__ import annotations

import enum
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Optional

import pytest
from pydantic import BaseModel, Field

from iaf_provider import schema_of
from iaf_provider._schema import compile_schema, problems


class Size(str, enum.Enum):
    SMALL = "small"
    LARGE = "large"


class Disk(BaseModel):
    size_gb: int
    ssd: bool


class Request(BaseModel):
    site: str = Field(description="Where it goes")
    note: Optional[str] = None
    count: int = 1
    size: Size
    disks: list[Disk]
    labels: dict[str, str]
    at: datetime
    ratio: float
    extra: Any
    title: str


class Loop(BaseModel):
    name: str
    next: Optional["Loop"] = None


@dataclass
class Point:
    x: int
    y: int = 0


def test_a_model_is_an_object_whose_fields_are_required_unless_they_have_a_default():
    s = schema_of(Request)

    assert s["type"] == "object"
    assert s["required"] == ["site", "size", "disks", "labels", "at", "ratio", "extra", "title"]
    assert "title" not in s, "the title pydantic adds is left out"


def test_fields_map_to_their_json_types_written_in_place():
    p = schema_of(Request)["properties"]

    assert p["site"] == {"type": "string", "description": "Where it goes"}
    assert p["note"] == {"anyOf": [{"type": "string"}, {"type": "null"}], "default": None}
    assert p["count"] == {"type": "integer", "default": 1}
    assert p["size"] == {"type": "string", "enum": ["small", "large"]}
    assert p["disks"] == {
        "type": "array",
        "items": {
            "type": "object",
            "properties": {"size_gb": {"type": "integer"}, "ssd": {"type": "boolean"}},
            "required": ["size_gb", "ssd"],
        },
    }
    assert p["labels"] == {"type": "object", "additionalProperties": {"type": "string"}}
    assert p["at"] == {"type": "string", "format": "date-time"}
    assert p["ratio"] == {"type": "number"}
    assert p["extra"] == {}
    assert p["title"] == {"type": "string"}, "a field named title is a field like any other"


def test_dataclasses_describe_themselves_too():
    assert schema_of(Point) == {
        "type": "object",
        "properties": {"x": {"type": "integer"}, "y": {"type": "integer", "default": 0}},
        "required": ["x"],
    }


def test_a_type_that_refers_to_itself_needs_an_explicit_schema():
    with pytest.raises(ValueError, match="refers to itself"):
        schema_of(Loop)


def test_problems_say_what_does_not_conform():
    check = compile_schema(schema_of(Disk))

    assert problems(check, {"size_gb": 10, "ssd": True}) == []
    found = problems(check, {"size_gb": "ten"})
    assert len(found) == 2
    assert any("ssd" in f for f in found)
    assert any(f.startswith("/size_gb:") for f in found)
