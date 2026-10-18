// Package provider writes a custom-code provider for the platform in Go: declare its actions with
// their input and output types, serve them on the provider's Temporal task queue, and publish the
// provider file the platform reads.
//
//	p := provider.New("ip-pool").
//		Title("IP address pools").
//		Owner("team-platform").
//		Version("1.0.0").
//		HeartbeatTimeout(30 * time.Second).
//		Error("pool_exhausted", false, "The pool has no free address").
//		Action(provider.NewAction("address.reserve", pools.Reserve).
//			Title("Reserve an address").
//			Effect(provider.Create)).
//		MustBuild()
//
//	provider.Run(ctx, p, provider.OptionsFromEnv())
//
// The platform and the SDKs share one contract (the payload, the error classes, the heartbeat);
// see "Custom-code providers" in docs/architecture.md.
package provider

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

const (
	// APIVersion and Kind of the provider file.
	APIVersion = "iaf.platform/v1"
	Kind       = "Provider"
	// Type of a custom-code provider.
	Type = "sdk"
)

var (
	namePattern       = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	actionIDPattern   = regexp.MustCompile(`^[a-z][A-Za-z0-9]*\.[a-z][A-Za-z0-9]*$`)
	semverPattern     = regexp.MustCompile(`^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$`)
	taskQueuePattern  = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$`)
	errorClassPattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_.-]*$`)
)

// ErrorClass is an error class the provider's actions fail with, as the provider file publishes
// it. Workflows match on the name; a retryable class may be retried by the step's retry policy.
type ErrorClass struct {
	Name        string
	Retryable   bool
	Description string
}

// Provider is a custom-code provider: who owns it, the worker that serves it and its actions. It
// is both the code and the source of the provider file, so the two cannot drift.
type Provider struct {
	name             string
	title            string
	owner            string
	version          string
	description      string
	taskQueue        string
	heartbeatTimeout time.Duration
	alerts           string
	actions          []*Action
	byID             map[string]*Action
	errors           []ErrorClass
}

// Name is the provider's name, the id workflows call it by.
func (p *Provider) Name() string { return p.name }

// Title is the provider's display name.
func (p *Provider) Title() string { return p.title }

// Owner is the team that may edit and publish the provider.
func (p *Provider) Owner() string { return p.owner }

// Version is the provider's semantic version.
func (p *Provider) Version() string { return p.version }

// TaskQueue is where the worker listens and the platform schedules the actions: provider-<name>
// unless named.
func (p *Provider) TaskQueue() string { return p.taskQueue }

// HeartbeatTimeout is how long a call may go without a heartbeat before the platform counts the
// worker lost; zero means no limit.
func (p *Provider) HeartbeatTimeout() time.Duration { return p.heartbeatTimeout }

// Actions are the provider's actions in the order they were declared.
func (p *Provider) Actions() []*Action { return append([]*Action(nil), p.actions...) }

// Action is the action with that id, or nil.
func (p *Provider) Action(id string) *Action { return p.byID[id] }

// Errors are the provider's error classes.
func (p *Provider) Errors() []ErrorClass { return append([]ErrorClass(nil), p.errors...) }

// Builder describes a provider; Build checks it the way the platform's provider checks would.
type Builder struct {
	p       Provider
	actions []*Action
}

// New starts a provider with its name: lowercase letters, digits and dashes, like ip-pool.
func New(name string) *Builder {
	return &Builder{p: Provider{name: name}}
}

// Title is the provider's display name; the name when left out.
func (b *Builder) Title(title string) *Builder { b.p.title = title; return b }

// Owner is the team that owns the provider, like team-platform.
func (b *Builder) Owner(team string) *Builder { b.p.owner = team; return b }

// Version is a semantic version, like 1.0.0; publishing opens a change request for it.
func (b *Builder) Version(v string) *Builder { b.p.version = v; return b }

// Description says what the provider is for.
func (b *Builder) Description(d string) *Builder { b.p.description = d; return b }

// TaskQueue names the queue to listen on; the default is provider-<name>.
func (b *Builder) TaskQueue(q string) *Builder { b.p.taskQueue = q; return b }

// HeartbeatTimeout is how long a call may go without a heartbeat before the platform fails it
// as worker-lost; at least a second.
func (b *Builder) HeartbeatTimeout(d time.Duration) *Builder { b.p.heartbeatTimeout = d; return b }

// Alerts is the channel health alerts about the provider go to, like #platform-alerts.
func (b *Builder) Alerts(channel string) *Builder { b.p.alerts = channel; return b }

// Error declares an error class the actions fail with.
func (b *Builder) Error(class string, retryable bool, description string) *Builder {
	b.p.errors = append(b.p.errors, ErrorClass{Name: class, Retryable: retryable, Description: description})
	return b
}

// Action adds an action.
func (b *Builder) Action(a *Action) *Builder {
	b.actions = append(b.actions, a)
	return b
}

// Build checks the provider and returns it; the error lists every problem at once.
func (b *Builder) Build() (*Provider, error) {
	p := b.p
	var problems []string
	if !namePattern.MatchString(p.name) {
		problems = append(problems, fmt.Sprintf("The name is lowercase letters, digits and dashes, like ip-pool; was %q", p.name))
	}
	if !namePattern.MatchString(p.owner) {
		problems = append(problems, fmt.Sprintf("Owner is the team that owns the provider, like team-platform; was %q", p.owner))
	}
	if !semverPattern.MatchString(p.version) {
		problems = append(problems, fmt.Sprintf("Version is semantic, like 1.0.0; was %q", p.version))
	}
	if p.taskQueue != "" && !taskQueuePattern.MatchString(p.taskQueue) {
		problems = append(problems, fmt.Sprintf("The task queue is letters, digits, dots, dashes and underscores; was %q", p.taskQueue))
	}
	if p.heartbeatTimeout != 0 && p.heartbeatTimeout < time.Second {
		problems = append(problems, fmt.Sprintf("HeartbeatTimeout is at least one second; was %s", p.heartbeatTimeout))
	}
	if p.title == "" {
		p.title = p.name
	}
	if p.taskQueue == "" {
		p.taskQueue = "provider-" + p.name
	}
	p.byID = map[string]*Action{}
	for _, a := range b.actions {
		if a == nil {
			continue
		}
		if a.err != nil {
			problems = append(problems, a.err.Error())
			continue
		}
		if !actionIDPattern.MatchString(a.id) {
			problems = append(problems, fmt.Sprintf("Action ids read as <noun>.<verb>, like address.reserve; was %q", a.id))
		}
		if a.effect == "" {
			problems = append(problems, fmt.Sprintf("Action %s says what it does: Effect(Read), Create, Change or Delete", a.id))
		} else if !validEffect(a.effect) {
			problems = append(problems, fmt.Sprintf("Action %s has effect %q; one of read, create, change, delete", a.id, a.effect))
		}
		for _, s := range []struct {
			which  string
			schema json.RawMessage
		}{{"input", a.input}, {"output", a.output}} {
			if t := a.schemaType(s.schema); t != "" && t != "object" {
				problems = append(problems, fmt.Sprintf("The %s of %s is a JSON object; use a struct", s.which, a.id))
			}
		}
		if _, dup := p.byID[a.id]; dup {
			problems = append(problems, fmt.Sprintf("Action id %s is used twice", a.id))
			continue
		}
		if err := a.compile(); err != nil {
			problems = append(problems, err.Error())
		}
		p.byID[a.id] = a
		p.actions = append(p.actions, a)
	}
	if len(b.actions) == 0 {
		problems = append(problems, "A provider offers at least one action")
	}
	for _, a := range p.actions {
		if a.plan != "" && (a.plan == a.id || p.byID[a.plan] == nil) {
			problems = append(problems, fmt.Sprintf("The plan of %s names another action of the provider; was %q", a.id, a.plan))
		}
	}
	seen := map[string]bool{}
	for _, e := range p.errors {
		switch {
		case !errorClassPattern.MatchString(e.Name):
			problems = append(problems, fmt.Sprintf("An error class is a word like pool_exhausted; was %q", e.Name))
		case seen[e.Name]:
			problems = append(problems, fmt.Sprintf("Error class %s is declared twice", e.Name))
		}
		seen[e.Name] = true
	}
	if len(problems) > 0 {
		return nil, errors.New("provider " + p.name + " is not valid:\n  - " + strings.Join(problems, "\n  - "))
	}
	return &p, nil
}

// MustBuild is Build for a provider declared in code: it panics on a problem.
func (b *Builder) MustBuild() *Provider {
	p, err := b.Build()
	if err != nil {
		panic(err)
	}
	return p
}

func validEffect(e Effect) bool {
	switch e {
	case Read, Create, Change, Delete:
		return true
	}
	return false
}
