"""The provider model: actions, error classes and the provider file."""

from __future__ import annotations

import copy
import enum
import inspect
import re
import typing
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Callable

import yaml
from pydantic import TypeAdapter

from ._schema import compile_schema, schema_of

API_VERSION = "iaf.platform/v1"
KIND = "Provider"
TYPE = "sdk"

_NAME = re.compile(r"^[a-z][a-z0-9-]*$")
_ACTION_ID = re.compile(r"^[a-z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*$")
_SEMVER = re.compile(r"^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$")
_TASK_QUEUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$")
_ERROR_CLASS = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]*$")

_OPEN_OBJECT: dict[str, Any] = {"type": "object"}


class Effect(str, enum.Enum):
    """What an action does to the world; the platform derives previews, approvals and retry safety from it.

    ``update``, the spelling of earlier versions of the SDKs, still reads as ``CHANGE`` (and
    ``Effect.UPDATE`` is ``Effect.CHANGE``) in this version."""

    READ = "read"
    CREATE = "create"
    CHANGE = "change"
    DELETE = "delete"
    #: Deprecated: the old name of CHANGE; it writes ``change`` and goes away in the next version.
    UPDATE = "change"

    @classmethod
    def _missing_(cls, value: object) -> Effect | None:
        if isinstance(value, str) and value.strip().lower() == "update":
            return cls.CHANGE
        return None


@dataclass(frozen=True)
class ErrorClass:
    """An error class the provider's actions fail with; workflows match on the name."""

    name: str
    retryable: bool
    description: str | None = None


class Action:
    """One operation a provider offers: its id (the activity type the platform schedules), what it
    does, the JSON Schemas of its input and output, an optional plan action that previews it, the
    answer the platform's demo mode gives for it, and the handler."""

    def __init__(
        self,
        id: str,
        handler: Callable[..., Any],
        *,
        title: str | None = None,
        effect: Effect | str | None = None,
        description: str | None = None,
        plan: str | None = None,
        input: dict[str, Any] | None = None,
        output: dict[str, Any] | None = None,
        sample: Any = None,
    ) -> None:
        self.id = id
        self.handler = handler
        self.title = title or id
        self.effect = Effect(effect) if effect is not None else None
        self.description = description
        self.plan = plan
        self.sample = sample
        self.is_async = inspect.iscoroutinefunction(handler)
        hints = _hints(handler)
        params = list(inspect.signature(handler).parameters.values())
        self.input_type: Any = hints.get(params[1].name, dict[str, Any]) if len(params) >= 2 else dict[str, Any]
        self.output_type: Any = hints.get("return", dict[str, Any])
        self.input_schema = copy.deepcopy(input) if input is not None else _derived(self.input_type, "validation")
        self.output_schema = copy.deepcopy(output) if output is not None else _derived(self.output_type, "serialization")
        self._input_adapter = TypeAdapter(self.input_type)
        self._output_adapter = TypeAdapter(self.output_type)
        self.input_check = compile_schema(self.input_schema)
        self.output_check = compile_schema(self.output_schema)

    def read_input(self, value: dict[str, Any]) -> Any:
        return self._input_adapter.validate_python(value)

    def write_output(self, value: Any) -> Any:
        if value is None:
            return {}
        return self._output_adapter.dump_python(value, mode="json")


def _hints(fn: Callable[..., Any]) -> dict[str, Any]:
    try:
        return typing.get_type_hints(fn)
    except Exception:
        return {}


def _derived(tp: Any, mode: str) -> dict[str, Any]:
    if tp in (dict, Any) or tp == dict[str, Any] or tp is type(None):
        return dict(_OPEN_OBJECT)
    return schema_of(tp, mode)


class Provider:
    """A custom-code provider: who owns it, the worker that serves it and its actions. It is both
    the code and the source of the provider file, so the two cannot drift.

    ::

        dns = Provider("dns-records", title="DNS records", owner="team-platform", version="1.0.0")

        @dns.action("record.get", title="Get a record", effect=Effect.READ)
        def get_record(call: Call, ref: RecordRef) -> Record:
            ...
    """

    def __init__(
        self,
        name: str,
        *,
        owner: str,
        version: str,
        title: str | None = None,
        description: str | None = None,
        task_queue: str | None = None,
        heartbeat_timeout: timedelta | None = None,
        alerts: str | None = None,
    ) -> None:
        self.name = name
        self.owner = owner
        self.version = version
        self.title = title or name
        self.description = description
        self.task_queue = task_queue or f"provider-{name}"
        self.heartbeat_timeout = heartbeat_timeout
        self.alerts = alerts
        self._task_queue_given = task_queue
        self._actions: dict[str, Action] = {}
        self._problems: list[str] = []
        self.errors: list[ErrorClass] = []

    # ------------------------------------------------------------------ declaring

    def error(self, error_class: str, *, retryable: bool, description: str | None = None) -> Provider:
        """Declare an error class the actions fail with."""
        self.errors.append(ErrorClass(error_class, retryable, description))
        return self

    def action(
        self,
        id: str,
        *,
        title: str | None = None,
        effect: Effect | str,
        description: str | None = None,
        plan: str | None = None,
        input: dict[str, Any] | None = None,
        output: dict[str, Any] | None = None,
        sample: Any = None,
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        """Decorate a handler ``(call, input) -> output``, sync or async, as an action.

        The input and output schemas are derived from the handler's type hints (pydantic models,
        dataclasses, TypedDicts) unless given; ``sample`` is the answer the platform's demo mode
        gives (text in it may use expressions like ``{{ $input.name }}``).
        """

        def register(fn: Callable[..., Any]) -> Callable[..., Any]:
            try:
                a = Action(
                    id,
                    fn,
                    title=title,
                    effect=effect,
                    description=description,
                    plan=plan,
                    input=input,
                    output=output,
                    sample=sample,
                )
            except Exception as e:
                self._problems.append(f"Action {id}: {e}")
                return fn
            if id in self._actions:
                self._problems.append(f"Action id {id} is used twice")
            else:
                self._actions[id] = a
            return fn

        return register

    # ------------------------------------------------------------------ reading

    @property
    def actions(self) -> list[Action]:
        return list(self._actions.values())

    def get_action(self, id: str) -> Action | None:
        return self._actions.get(id)

    def check(self) -> list[str]:
        """Every problem the platform's provider checks would report; empty when the provider is valid."""
        problems = list(self._problems)
        if not _NAME.match(self.name or ""):
            problems.append(f"The name is lowercase letters, digits and dashes, like dns-records; was {self.name!r}")
        if not _NAME.match(self.owner or ""):
            problems.append(f"owner is the team that owns the provider, like team-platform; was {self.owner!r}")
        if not _SEMVER.match(self.version or ""):
            problems.append(f"version is semantic, like 1.0.0; was {self.version!r}")
        if self._task_queue_given is not None and not _TASK_QUEUE.match(self._task_queue_given):
            problems.append(f"The task queue is letters, digits, dots, dashes and underscores; was {self._task_queue_given!r}")
        if self.heartbeat_timeout is not None and self.heartbeat_timeout < timedelta(seconds=1):
            problems.append(f"heartbeat_timeout is at least one second; was {self.heartbeat_timeout}")
        if not self._actions and not self._problems:
            problems.append("A provider offers at least one action")
        for a in self._actions.values():
            if not _ACTION_ID.match(a.id):
                problems.append(f"Action ids read as <noun>.<verb>, like record.create; was {a.id!r}")
            for which, schema in (("input", a.input_schema), ("output", a.output_schema)):
                if "type" in schema and schema["type"] != "object":
                    problems.append(f"The {which} of {a.id} is a JSON object; use a model")
            if a.plan is not None and (a.plan == a.id or a.plan not in self._actions):
                problems.append(f"The plan of {a.id} names another action of the provider; was {a.plan!r}")
        seen: set[str] = set()
        for e in self.errors:
            if not _ERROR_CLASS.match(e.name or ""):
                problems.append(f"An error class is a word like record_conflict; was {e.name!r}")
            elif e.name in seen:
                problems.append(f"Error class {e.name} is declared twice")
            seen.add(e.name)
        return problems

    def validate(self) -> Provider:
        """The provider itself when it is valid; a ValueError listing every problem otherwise."""
        problems = self.check()
        if problems:
            raise ValueError(f"Provider {self.name} is not valid:\n  - " + "\n  - ".join(problems))
        return self

    # ------------------------------------------------------------------ the provider file

    def document(self) -> dict[str, Any]:
        """The provider file as a dict: kind Provider, type sdk, the worker and every action with its schemas."""
        metadata: dict[str, Any] = {"name": self.name, "title": self.title, "owner": self.owner, "version": self.version}
        if self.description:
            metadata["description"] = self.description
        worker: dict[str, Any] = {"taskQueue": self.task_queue}
        if self.heartbeat_timeout is not None:
            worker["heartbeatTimeout"] = format_duration(self.heartbeat_timeout)
        actions = []
        for a in self._actions.values():
            entry: dict[str, Any] = {"id": a.id, "title": a.title, "effect": a.effect.value if a.effect else None}
            if a.plan:
                entry["plan"] = a.plan
            if a.description:
                entry["description"] = a.description
            entry["input"] = copy.deepcopy(a.input_schema)
            entry["output"] = copy.deepcopy(a.output_schema)
            if a.sample is not None:
                entry["sample"] = _plain(a.sample)
            actions.append(entry)
        spec: dict[str, Any] = {"type": TYPE, "worker": worker, "actions": actions}
        if self.errors:
            spec["errors"] = [
                {"class": e.name, "retry": e.retryable, **({"description": e.description} if e.description else {})}
                for e in self.errors
            ]
        if self.alerts:
            spec["alerts"] = {"channel": self.alerts}
        return {"apiVersion": API_VERSION, "kind": KIND, "metadata": metadata, "spec": spec}

    def to_yaml(self) -> str:
        """The provider file the platform registers: ``providers/<owner>/<name>.yaml``."""
        return yaml.dump(self.document(), Dumper=_Dumper, sort_keys=False, allow_unicode=True, default_flow_style=False, width=1000)


def _plain(value: Any) -> Any:
    return TypeAdapter(Any).dump_python(value, mode="json")


def format_duration(d: timedelta) -> str:
    """A duration the way provider files spell it: 30s, 2m, 1h, 1500ms."""
    ms = int(d.total_seconds() * 1000)
    if ms % 3_600_000 == 0:
        return f"{ms // 3_600_000}h"
    if ms % 60_000 == 0:
        return f"{ms // 60_000}m"
    if ms % 1000 == 0:
        return f"{ms // 1000}s"
    return f"{ms}ms"


class _Dumper(yaml.SafeDumper):
    """Block style with list items indented under their key, like the platform's own files."""

    def increase_indent(self, flow: bool = False, indentless: bool = False) -> None:
        return super().increase_indent(flow, False)
