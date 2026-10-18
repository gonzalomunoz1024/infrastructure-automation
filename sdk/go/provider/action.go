package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

// Effect says what an action does to the world; the platform derives previews, approvals and
// retry safety from it.
type Effect string

const (
	Read   Effect = "read"
	Create Effect = "create"
	Update Effect = "update"
	Delete Effect = "delete"
)

// Job is the platform job a call belongs to.
type Job struct {
	ID       string `json:"id"`
	Workflow string `json:"workflow"`
	Version  string `json:"version"`
	TraceID  string `json:"traceId"`
}

// Call is what a handler knows about the call it serves.
type Call struct {
	// Action is the action being run (the activity type).
	Action string
	// IdempotencyKey is the same for every retry of the step (and for a worker that picks the
	// call up again after a crash), new for a new run of it. Pass it to the system you call as
	// its idempotency or request id, or keep it with what you create.
	IdempotencyKey string
	// Job is the job the call belongs to.
	Job Job
	// Step is the id of the workflow step that made the call.
	Step string
	// Environment is the environment the job runs against (dev, prod); may be empty.
	Environment string
	// Attempt is the step's attempt, from 1; a retry by the step's retry policy counts up.
	Attempt int

	heartbeat func(details ...any)
}

// Heartbeat tells the platform the call is alive, with optional progress details. Call it more
// often than the provider's heartbeat timeout during long work, and watch ctx.Done(): a
// cancelled step cancels the context at the next heartbeat.
func (c *Call) Heartbeat(details ...any) {
	if c.heartbeat != nil {
		c.heartbeat(details...)
	}
}

// Handler is the code behind an action: it gets the validated input and returns the output.
// Return Retryable or Fatal for a classified failure; any other error is the retryable class
// "error".
type Handler[I, O any] func(ctx context.Context, call *Call, in I) (O, error)

// Action is one operation a provider offers: its id (the activity type the platform schedules),
// what it does, the JSON Schemas of its input and output, an optional plan action that previews
// it, the answer the platform's demo mode gives for it, and the handler.
type Action struct {
	id          string
	title       string
	effect      Effect
	description string
	plan        string
	input       json.RawMessage
	output      json.RawMessage
	sample      any
	run         func(ctx context.Context, call *Call, in json.RawMessage) (any, error)
	inputCheck  *jsonschema.Schema
	outputCheck *jsonschema.Schema
	err         error
}

// NewAction declares an action whose input and output schemas are derived from I and O (see
// SchemaOf). For plain JSON use map[string]any with explicit Input and Output schemas.
func NewAction[I, O any](id string, handler Handler[I, O]) *Action {
	a := &Action{id: id}
	var err error
	if a.input, err = SchemaOf(reflect.TypeFor[I]()); err != nil {
		a.err = fmt.Errorf("the input of %s: %w", id, err)
	}
	if a.output, err = SchemaOf(reflect.TypeFor[O]()); err != nil && a.err == nil {
		a.err = fmt.Errorf("the output of %s: %w", id, err)
	}
	a.run = func(ctx context.Context, call *Call, raw json.RawMessage) (any, error) {
		var v I
		if err := json.Unmarshal(raw, &v); err != nil {
			return nil, Fatal(InvalidInput, fmt.Sprintf("The input of %s cannot be read: %v", id, err))
		}
		return handler(ctx, call, v)
	}
	return a
}

// ID is the action id.
func (a *Action) ID() string { return a.id }

// PlanID is the id of the action that previews this one, or empty.
func (a *Action) PlanID() string { return a.plan }

// EffectOf is what the action does.
func (a *Action) EffectOf() Effect { return a.effect }

// InputSchema is the action's input schema as JSON.
func (a *Action) InputSchema() json.RawMessage { return append(json.RawMessage(nil), a.input...) }

// OutputSchema is the action's output schema as JSON.
func (a *Action) OutputSchema() json.RawMessage { return append(json.RawMessage(nil), a.output...) }

// Title is the action's display name; the id when left out.
func (a *Action) Title(t string) *Action { a.title = t; return a }

// Effect says what the action does: Read, Create, Update or Delete.
func (a *Action) Effect(e Effect) *Action { a.effect = e; return a }

// Description says more about the action.
func (a *Action) Description(d string) *Action { a.description = d; return a }

// Plan names another action of the provider that previews this one (what it would do, without
// doing it).
func (a *Action) Plan(actionID string) *Action { a.plan = actionID; return a }

// Input replaces the derived input schema with an explicit one: a JSON object as a string,
// []byte, json.RawMessage, map or struct.
func (a *Action) Input(schema any) *Action { a.input = a.explicit(schema, "input"); return a }

// Output replaces the derived output schema with an explicit one.
func (a *Action) Output(schema any) *Action { a.output = a.explicit(schema, "output"); return a }

// Sample is the answer the platform's demo mode gives for the action; text in it may use
// expressions like {{ $input.name }} or {{ $job.seq }}.
func (a *Action) Sample(output any) *Action { a.sample = output; return a }

func (a *Action) explicit(schema any, which string) json.RawMessage {
	var raw json.RawMessage
	var err error
	switch s := schema.(type) {
	case string:
		raw, err = objectJSON(json.RawMessage(s))
	case []byte:
		raw, err = objectJSON(json.RawMessage(s))
	default:
		raw, err = objectJSON(s)
	}
	if err != nil {
		if a.err == nil {
			a.err = fmt.Errorf("the %s schema of %s is a JSON object", which, a.id)
		}
		return json.RawMessage(`{"type":"object"}`)
	}
	return raw
}

func (a *Action) schemaType(schema json.RawMessage) string {
	var s struct {
		Type any `json:"type"`
	}
	if json.Unmarshal(schema, &s) != nil || s.Type == nil {
		return ""
	}
	if t, ok := s.Type.(string); ok {
		return t
	}
	return fmt.Sprint(s.Type)
}

func (a *Action) compile() error {
	var err error
	if a.inputCheck, err = compile(a.input); err != nil {
		return fmt.Errorf("the input schema of %s does not compile: %w", a.id, err)
	}
	if a.outputCheck, err = compile(a.output); err != nil {
		return fmt.Errorf("the output schema of %s does not compile: %w", a.id, err)
	}
	return nil
}
