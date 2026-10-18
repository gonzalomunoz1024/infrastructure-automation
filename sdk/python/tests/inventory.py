"""A small provider the tests serve: items in a dict."""

from __future__ import annotations

import enum
import threading
from datetime import timedelta
from typing import Optional

from pydantic import BaseModel, Field

from iaf_provider import Call, Effect, Provider, fatal


class State(str, enum.Enum):
    ACTIVE = "active"
    RETIRED = "retired"


class Lookup(BaseModel):
    id: str = Field(description="The item id")


class Create(BaseModel):
    name: str
    owner: Optional[str] = None


class Item(BaseModel):
    id: str
    name: str
    state: State


class Inventory:
    def __init__(self) -> None:
        self.items: dict[str, Item] = {"it-1": Item(id="it-1", name="first", state=State.ACTIVE)}
        self.created: dict[str, str] = {}
        self.lock = threading.Lock()

    def get(self, call: Call, ref: Lookup) -> Item:
        item = self.items.get(ref.id)
        if item is None:
            raise fatal("not_found", f"No item {ref.id}")
        return item

    def create(self, call: Call, new: Create) -> Item:
        with self.lock:
            if call.idempotency_key in self.created:
                return self.items[self.created[call.idempotency_key]]
            item = Item(id=f"it-{len(self.items) + 1}", name=new.name, state=State.ACTIVE)
            self.items[item.id] = item
            self.created[call.idempotency_key or ""] = item.id
            return item

    def provider(self, **overrides) -> Provider:
        options = dict(
            title="Inventory",
            owner="team-platform",
            version="1.2.0",
            description="Items the tests keep in memory",
            heartbeat_timeout=timedelta(seconds=30),
            alerts="#platform-alerts",
        )
        options.update(overrides)
        p = Provider("inventory", **options)
        p.error("not_found", retryable=False, description="No item has that id")
        p.error("busy", retryable=True, description="The inventory is busy; try again")
        p.action("item.get", title="Get an item", effect=Effect.READ, sample={"id": "{{ $input.id }}", "name": "sample", "state": "active"})(self.get)
        p.action("item.preview", title="Preview an item", effect=Effect.READ)(preview)
        p.action("item.create", title="Create an item", effect=Effect.CREATE, plan="item.preview")(self.create)
        return p


def preview(call: Call, new: Create) -> Item:
    return Item(id="(new)", name=new.name, state=State.ACTIVE)
