"""JSON Schemas of actions: derived from Python types, checked against the values on the wire."""

from __future__ import annotations

import copy
from typing import Any

import jsonschema
from pydantic import TypeAdapter

_SCHEMA_MAPS = ("properties", "patternProperties", "$defs", "definitions", "dependentSchemas")
_SCHEMA_VALUES = (
    "items",
    "additionalProperties",
    "not",
    "contains",
    "if",
    "then",
    "else",
    "propertyNames",
    "unevaluatedItems",
    "unevaluatedProperties",
)
_SCHEMA_LISTS = ("anyOf", "oneOf", "allOf", "prefixItems")
_DEFS = "#/$defs/"


def schema_of(tp: Any, mode: str = "validation") -> dict[str, Any]:
    """The JSON Schema (draft 2020-12) of a type, the way pydantic reads (``validation``, for an
    input) or writes (``serialization``, for an output) it.

    A pydantic model, a dataclass or a TypedDict is an object whose fields are its properties;
    a field with a default is optional; ``Field(description=...)`` describes one. Nested models
    and enums are written in place, and the titles pydantic adds on its own are left out, so the
    schema reads like a hand-written one.
    """
    try:
        schema = TypeAdapter(tp).json_schema(mode=mode)  # type: ignore[arg-type]
    except Exception as e:  # pydantic raises several kinds for types it cannot describe
        raise ValueError(f"Cannot derive a JSON Schema from {tp!r}: {e}; give the schema explicitly") from e
    return _tidy(_inline(schema))


def _inline(schema: dict[str, Any]) -> dict[str, Any]:
    defs = schema.pop("$defs", None)
    if not defs:
        return schema

    def resolve(node: Any, stack: frozenset[str]) -> Any:
        if isinstance(node, dict):
            ref = node.get("$ref")
            if isinstance(ref, str) and ref.startswith(_DEFS):
                name = ref[len(_DEFS):]
                if name in stack:
                    raise ValueError(f"{name} refers to itself; give its schema explicitly")
                target = resolve(copy.deepcopy(defs[name]), stack | {name})
                return {**target, **{k: resolve(v, stack) for k, v in node.items() if k != "$ref"}}
            return {k: resolve(v, stack) for k, v in node.items()}
        if isinstance(node, list):
            return [resolve(v, stack) for v in node]
        return node

    return resolve(schema, frozenset())


def _tidy(node: Any) -> Any:
    """``type`` first and no generated ``title``, in the schema and every schema inside it."""
    if not isinstance(node, dict):
        return node
    out: dict[str, Any] = {}
    if "type" in node:
        out["type"] = node["type"]
    for key, value in node.items():
        if key == "type" or (key == "title" and isinstance(value, str)):
            continue
        if key in _SCHEMA_MAPS and isinstance(value, dict):
            out[key] = {name: _tidy(sub) for name, sub in value.items()}
        elif key in _SCHEMA_VALUES:
            out[key] = _tidy(value)
        elif key in _SCHEMA_LISTS and isinstance(value, list):
            out[key] = [_tidy(sub) for sub in value]
        else:
            out[key] = value
    return out


def compile_schema(schema: dict[str, Any]) -> jsonschema.Draft202012Validator:
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema)


def problems(validator: jsonschema.Draft202012Validator, value: Any) -> list[str]:
    """What is wrong with ``value``, one line per problem, sorted; empty when it conforms."""
    out = []
    for error in validator.iter_errors(value):
        where = "/" + "/".join(str(p) for p in error.absolute_path)
        out.append(f"{where}: {error.message}")
    return sorted(out)
