package provider

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// A small provider the tests serve: items in a map.

type lookup struct {
	ID string `json:"id" description:"The item id"`
}

type create struct {
	Name  string  `json:"name"`
	Owner *string `json:"owner"`
}

type item struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	State string `json:"state" enum:"active,retired"`
}

type inventory struct {
	mu      sync.Mutex
	items   map[string]item
	created map[string]string // idempotency key -> item id
}

func newInventory() *inventory {
	return &inventory{items: map[string]item{"it-1": {ID: "it-1", Name: "first", State: "active"}}, created: map[string]string{}}
}

func (inv *inventory) get(_ context.Context, _ *Call, in lookup) (item, error) {
	inv.mu.Lock()
	defer inv.mu.Unlock()
	it, ok := inv.items[in.ID]
	if !ok {
		return item{}, Fatal("not_found", "No item "+in.ID)
	}
	return it, nil
}

func (inv *inventory) create(_ context.Context, call *Call, in create) (item, error) {
	inv.mu.Lock()
	defer inv.mu.Unlock()
	if id, ok := inv.created[call.IdempotencyKey]; ok {
		return inv.items[id], nil
	}
	it := item{ID: fmt.Sprintf("it-%d", len(inv.items)+1), Name: in.Name, State: "active"}
	inv.items[it.ID] = it
	inv.created[call.IdempotencyKey] = it.ID
	return it, nil
}

func (inv *inventory) builder() *Builder {
	return New("inventory").
		Title("Inventory").
		Owner("team-platform").
		Version("1.2.0").
		Description("Items the tests keep in memory").
		HeartbeatTimeout(30*time.Second).
		Alerts("#platform-alerts").
		Error("not_found", false, "No item has that id").
		Error("busy", true, "The inventory is busy; try again").
		Action(NewAction("item.get", inv.get).
			Title("Get an item").
			Effect(Read).
			Sample(map[string]any{"id": "{{ input.id }}", "name": "sample", "state": "active"})).
		Action(NewAction("item.preview", func(_ context.Context, _ *Call, in create) (item, error) {
			return item{ID: "(new)", Name: in.Name, State: "active"}, nil
		}).Title("Preview an item").Effect(Read)).
		Action(NewAction("item.create", inv.create).
			Title("Create an item").
			Effect(Create).
			Plan("item.preview"))
}
