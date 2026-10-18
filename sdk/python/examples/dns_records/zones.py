"""An example provider: DNS records in a few zones, kept in memory.

Four actions show the model: a read, the preview of a new record, its creation (async,
idempotent with the platform's idempotency key, heartbeating while the change reaches the
secondaries) and its deletion.
"""

from __future__ import annotations

import asyncio
import enum
import ipaddress
import re
import threading
from collections.abc import Iterable
from datetime import timedelta
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from iaf_provider import Call, Effect, Provider, fatal, retryable

_LABEL = r"[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
_HOST = re.compile(rf"^{_LABEL}(\.{_LABEL})*\.?$")


class RecordType(str, enum.Enum):
    A = "A"
    AAAA = "AAAA"
    CNAME = "CNAME"
    TXT = "TXT"


class RecordRef(BaseModel):
    zone: str = Field(description="The zone, like lab.example.internal")
    name: str = Field(description="The name in the zone, like web-01")


class NewRecord(BaseModel):
    zone: str = Field(description="The zone, like lab.example.internal")
    name: str = Field(description="The name in the zone, like web-01", pattern=rf"^{_LABEL}$")
    type: RecordType = Field(RecordType.A, description="The record type")
    value: str = Field(description="An IPv4 address for A, an IPv6 address for AAAA, a host name for CNAME, any text for TXT")
    ttl: int = Field(300, ge=30, le=86400, description="How long resolvers may cache it, in seconds")

    @model_validator(mode="after")
    def value_fits_the_type(self) -> NewRecord:
        try:
            if self.type is RecordType.A:
                ipaddress.IPv4Address(self.value)
            elif self.type is RecordType.AAAA:
                ipaddress.IPv6Address(self.value)
        except ValueError:
            raise ValueError(f"An {self.type.value} record points to an IP{'v4' if self.type is RecordType.A else 'v6'} address; was {self.value!r}") from None
        if self.type is RecordType.CNAME and not _HOST.match(self.value):
            raise ValueError(f"A CNAME record points to a host name; was {self.value!r}")
        return self


class Record(BaseModel):
    fqdn: str
    zone: str
    name: str
    type: RecordType
    value: str
    ttl: int


class Plan(BaseModel):
    action: Literal["create", "none"]
    fqdn: str
    summary: str


class Deleted(BaseModel):
    fqdn: str
    deleted: bool = Field(description="False when there was no record to delete")


def default_zones() -> list[str]:
    """The zones the example serves, one for each pool of the ip-pool example."""
    return ["office.example.internal", "lab.example.internal", "dmz.example.internal"]


class Zones:
    """The in-memory DNS server: records by zone, one lock per zone."""

    def __init__(self, zones: Iterable[str], *, propagation: timedelta = timedelta(seconds=1)) -> None:
        self.records: dict[str, dict[str, Record]] = {z: {} for z in zones}
        self.locks = {z: threading.Lock() for z in self.records}
        self.created: dict[str, Record] = {}  # idempotency key -> the record its call created
        # How long a new record takes to reach the secondaries, as a real server's NOTIFY round would.
        self.propagation = propagation

    def zone(self, name: str) -> dict[str, Record]:
        records = self.records.get(name)
        if records is None:
            raise fatal("zone_not_found", f"No zone {name}; the zones are {', '.join(self.records)}")
        return records

    def get(self, call: Call, ref: RecordRef) -> Record:
        record = self.zone(ref.zone).get(ref.name)
        if record is None:
            raise fatal("record_not_found", f"No record {ref.name}.{ref.zone}")
        return record

    def preview(self, call: Call, new: NewRecord) -> Plan:
        fqdn = f"{new.name}.{new.zone}"
        existing = self.zone(new.zone).get(new.name)
        if existing is None:
            return Plan(action="create", fqdn=fqdn, summary=f"Creates {new.type.value} {fqdn} -> {new.value} (ttl {new.ttl}s)")
        if _same(existing, new):
            return Plan(action="none", fqdn=fqdn, summary=f"{fqdn} already points to {new.value}")
        raise fatal("record_conflict", f"{fqdn} is already a {existing.type.value} record for {existing.value}")

    async def create(self, call: Call, new: NewRecord) -> Record:
        records = self.zone(new.zone)
        lock = self.locks[new.zone]
        if not lock.acquire(blocking=False):
            raise retryable("dns_busy", f"Zone {new.zone} is taking another change; try again")
        try:
            key = call.idempotency_key
            if key and key in self.created:  # a retry of the step, or a worker picking the call up again
                return self.created[key]
            fqdn = f"{new.name}.{new.zone}"
            existing = records.get(new.name)
            if existing is not None and not _same(existing, new):
                raise fatal("record_conflict", f"{fqdn} is already a {existing.type.value} record for {existing.value}")
            record = existing or Record(fqdn=fqdn, zone=new.zone, name=new.name, type=new.type, value=new.value, ttl=new.ttl)
            records[new.name] = record
            if key:
                self.created[key] = record
            secondaries = 3
            for done in range(1, secondaries + 1):
                await asyncio.sleep(self.propagation.total_seconds() / secondaries)
                call.heartbeat(f"{fqdn}: {done} of {secondaries} secondaries updated")
            return record
        finally:
            lock.release()

    def delete(self, call: Call, ref: RecordRef) -> Deleted:
        records = self.zone(ref.zone)
        with self.locks[ref.zone]:
            return Deleted(fqdn=f"{ref.name}.{ref.zone}", deleted=records.pop(ref.name, None) is not None)

    def provider(self) -> Provider:
        p = Provider(
            "dns-records",
            title="DNS records",
            owner="team-platform",
            version="1.0.0",
            description="Creates and deletes records in the internal DNS zones. Example provider of the Python SDK.",
            heartbeat_timeout=timedelta(seconds=30),
            alerts="#platform-alerts",
        )
        p.error("zone_not_found", retryable=False, description="No zone has that name")
        p.error("record_not_found", retryable=False, description="The zone has no record of that name")
        p.error("record_conflict", retryable=False, description="The name already has a different record")
        p.error("dns_busy", retryable=True, description="The zone is taking another change; a retry succeeds")
        p.action(
            "record.get",
            title="Get a record",
            effect=Effect.READ,
            sample={
                "fqdn": "{{ $input.name }}.{{ $input.zone }}",
                "zone": "{{ $input.zone }}",
                "name": "{{ $input.name }}",
                "type": "A",
                "value": "10.30.0.2",
                "ttl": 300,
            },
        )(self.get)
        p.action(
            "record.preview",
            title="Preview a record",
            effect=Effect.READ,
            sample={
                "action": "create",
                "fqdn": "{{ $input.name }}.{{ $input.zone }}",
                "summary": "Creates {{ $input.type ?? 'A' }} {{ $input.name }}.{{ $input.zone }} -> {{ $input.value }}",
            },
        )(self.preview)
        p.action(
            "record.create",
            title="Create a record",
            effect=Effect.CREATE,
            plan="record.preview",
            description="Waits until the zone's secondaries have the record, heartbeating meanwhile.",
            sample={
                "fqdn": "{{ $input.name }}.{{ $input.zone }}",
                "zone": "{{ $input.zone }}",
                "name": "{{ $input.name }}",
                "type": "{{ $input.type ?? 'A' }}",
                "value": "{{ $input.value }}",
                "ttl": "{{ $input.ttl ?? 300 }}",
            },
        )(self.create)
        p.action(
            "record.delete",
            title="Delete a record",
            effect=Effect.DELETE,
            sample={"fqdn": "{{ $input.name }}.{{ $input.zone }}", "deleted": True},
        )(self.delete)
        return p


def _same(record: Record, new: NewRecord) -> bool:
    return record.type == new.type and record.value == new.value
