package main

import (
	"context"
	"fmt"
	"net/netip"
	"sort"
	"sync"
	"time"

	"github.com/gonzalomunoz1024/infrastructure-automation/sdk/go/provider"
)

// An example provider: IP address pools kept in memory. Four actions show the model: a read, the
// preview of a reservation, the reservation itself (idempotent with the platform's idempotency
// key) and its release.

type PoolRef struct {
	Pool string `json:"pool" description:"The pool, like office"`
}

type Pool struct {
	Name     string `json:"name"`
	CIDR     string `json:"cidr"`
	Free     int    `json:"free"`
	Reserved int    `json:"reserved"`
}

type Reservation struct {
	Pool     string `json:"pool" description:"The pool to take the address from"`
	Hostname string `json:"hostname" description:"The host the address is for, like web-01"`
}

type Address struct {
	Address  string `json:"address"`
	Pool     string `json:"pool"`
	Hostname string `json:"hostname"`
	State    string `json:"state" enum:"reserved,released"`
}

type Plan struct {
	Action  string `json:"action" enum:"reserve,none"`
	Address string `json:"address,omitempty"`
	Summary string `json:"summary"`
}

type Release struct {
	Address string `json:"address" description:"The address to give back"`
}

type pool struct {
	mu     sync.Mutex
	name   string
	prefix netip.Prefix
	leases map[netip.Addr]string // address -> hostname
	byKey  map[string]netip.Addr // idempotency key -> address it reserved
}

// Pools is the in-memory pool store.
type Pools struct {
	pools map[string]*pool
	// hold keeps a reservation's lock a little while, as a real address manager's write would.
	hold time.Duration
}

func NewPools(cidrs map[string]string) *Pools {
	ps := &Pools{pools: map[string]*pool{}}
	for name, cidr := range cidrs {
		ps.pools[name] = &pool{name: name, prefix: netip.MustParsePrefix(cidr).Masked(), leases: map[netip.Addr]string{}, byKey: map[string]netip.Addr{}}
	}
	return ps
}

// DefaultPools are the pools the example serves: documentation and private ranges.
func DefaultPools() map[string]string {
	return map[string]string{"office": "10.20.0.0/28", "lab": "10.30.0.0/29", "dmz": "192.0.2.0/28"}
}

// Provider is the provider: who owns it, its worker and its actions.
func (ps *Pools) Provider() *provider.Provider {
	return provider.New("ip-pool").
		Title("IP address pools").
		Owner("team-platform").
		Version("1.0.0").
		Description("Reserves and releases IP addresses from named pools. Example provider of the Go SDK.").
		HeartbeatTimeout(30*time.Second).
		Alerts("#platform-alerts").
		Error("pool_not_found", false, "No pool has that name").
		Error("pool_exhausted", false, "The pool has no free address").
		Error("hostname_taken", false, "The host already holds an address in the pool").
		Error("address_not_found", false, "The address is not reserved").
		Error("pool_busy", true, "Another reservation holds the pool; a retry succeeds").
		Action(provider.NewAction("pool.get", ps.Get).
			Title("Get a pool").
			Effect(provider.Read).
			Sample(map[string]any{"name": "{{ $input.pool }}", "cidr": "10.20.0.0/28", "free": 12, "reserved": 2})).
		Action(provider.NewAction("address.preview", ps.Preview).
			Title("Preview a reservation").
			Effect(provider.Read).
			Sample(map[string]any{"action": "reserve", "address": "10.20.0.3", "summary": "Reserves 10.20.0.3 in {{ $input.pool }} for {{ $input.hostname }}"})).
		Action(provider.NewAction("address.reserve", ps.Reserve).
			Title("Reserve an address").
			Effect(provider.Create).
			Plan("address.preview").
			Sample(map[string]any{"address": "10.20.0.{{ $job.seq }}", "pool": "{{ $input.pool }}", "hostname": "{{ $input.hostname }}", "state": "reserved"})).
		Action(provider.NewAction("address.release", ps.Release).
			Title("Release an address").
			Effect(provider.Delete).
			Sample(map[string]any{"address": "{{ $input.address }}", "pool": "office", "hostname": "web-01", "state": "released"})).
		MustBuild()
}

func (ps *Pools) find(name string) (*pool, error) {
	p, ok := ps.pools[name]
	if !ok {
		names := make([]string, 0, len(ps.pools))
		for n := range ps.pools {
			names = append(names, n)
		}
		sort.Strings(names)
		return nil, provider.Fatal("pool_not_found", fmt.Sprintf("No pool %q; the pools are %v", name, names))
	}
	return p, nil
}

func (p *pool) usable() []netip.Addr {
	var out []netip.Addr
	first := p.prefix.Addr().Next() // the network address is not handed out
	for a := first; p.prefix.Contains(a); a = a.Next() {
		if !p.prefix.Contains(a.Next()) {
			break // nor is the broadcast address
		}
		out = append(out, a)
	}
	return out
}

func (p *pool) next() (netip.Addr, bool) {
	for _, a := range p.usable() {
		if _, taken := p.leases[a]; !taken {
			return a, true
		}
	}
	return netip.Addr{}, false
}

func (ps *Pools) Get(_ context.Context, _ *provider.Call, in PoolRef) (Pool, error) {
	p, err := ps.find(in.Pool)
	if err != nil {
		return Pool{}, err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return Pool{Name: p.name, CIDR: p.prefix.String(), Free: len(p.usable()) - len(p.leases), Reserved: len(p.leases)}, nil
}

func (ps *Pools) Preview(_ context.Context, _ *provider.Call, in Reservation) (Plan, error) {
	p, err := ps.find(in.Pool)
	if err != nil {
		return Plan{}, err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for a, host := range p.leases {
		if host == in.Hostname {
			return Plan{Action: "none", Address: a.String(), Summary: fmt.Sprintf("%s already holds %s in %s", in.Hostname, a, p.name)}, nil
		}
	}
	a, ok := p.next()
	if !ok {
		return Plan{Action: "none", Summary: p.name + " has no free address"}, nil
	}
	return Plan{Action: "reserve", Address: a.String(), Summary: fmt.Sprintf("Reserves %s in %s for %s", a, p.name, in.Hostname)}, nil
}

func (ps *Pools) Reserve(_ context.Context, call *provider.Call, in Reservation) (Address, error) {
	p, err := ps.find(in.Pool)
	if err != nil {
		return Address{}, err
	}
	if !p.mu.TryLock() {
		return Address{}, provider.Retryable("pool_busy", "Another reservation holds "+p.name)
	}
	defer p.mu.Unlock()
	if a, ok := p.byKey[call.IdempotencyKey]; ok {
		return Address{Address: a.String(), Pool: p.name, Hostname: p.leases[a], State: "reserved"}, nil // a retry of a call that already reserved it
	}
	for a, host := range p.leases {
		if host == in.Hostname {
			return Address{}, provider.Fatal("hostname_taken", fmt.Sprintf("%s already holds %s in %s", in.Hostname, a, p.name))
		}
	}
	a, ok := p.next()
	if !ok {
		return Address{}, provider.Fatal("pool_exhausted", p.name+" has no free address")
	}
	call.Heartbeat("reserving " + a.String())
	time.Sleep(ps.hold)
	p.leases[a] = in.Hostname
	p.byKey[call.IdempotencyKey] = a
	return Address{Address: a.String(), Pool: p.name, Hostname: in.Hostname, State: "reserved"}, nil
}

func (ps *Pools) Release(_ context.Context, _ *provider.Call, in Release) (Address, error) {
	a, err := netip.ParseAddr(in.Address)
	if err != nil {
		return Address{}, provider.Fatal("address_not_found", fmt.Sprintf("%q is not an IP address", in.Address))
	}
	for _, p := range ps.pools {
		if !p.prefix.Contains(a) {
			continue
		}
		p.mu.Lock()
		defer p.mu.Unlock()
		host, ok := p.leases[a]
		if !ok {
			return Address{}, provider.Fatal("address_not_found", fmt.Sprintf("%s is not reserved in %s", a, p.name))
		}
		delete(p.leases, a)
		for k, v := range p.byKey {
			if v == a {
				delete(p.byKey, k)
			}
		}
		return Address{Address: a.String(), Pool: p.name, Hostname: host, State: "released"}, nil
	}
	return Address{}, provider.Fatal("address_not_found", fmt.Sprintf("%s is in no pool", a))
}
