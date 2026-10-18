package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"
)

// Envelope is the one JSON payload the platform sends with every call, whatever the action:
//
//	{ "input": {...}, "idempotencyKey": "job-7:reserve:3",
//	  "job": { "id": "job-7", "workflow": "reserve-ip-address", "version": "1.0.0", "traceId": null },
//	  "step": "reserve", "environment": "dev", "attempt": 1 }
type Envelope struct {
	Input          any    `json:"input"`
	IdempotencyKey string `json:"idempotencyKey"`
	Job            Job    `json:"job"`
	Step           string `json:"step"`
	Environment    string `json:"environment"`
	Attempt        int    `json:"attempt"`
}

// TestEnvelope is a call with this input as a test makes it: job test-job, step "step",
// environment dev, attempt 1.
func TestEnvelope(input any) Envelope {
	return Envelope{
		Input:          input,
		IdempotencyKey: "test-job:step:1",
		Job:            Job{ID: "test-job", Workflow: "test", Version: "1.0.0"},
		Step:           "step",
		Environment:    "dev",
		Attempt:        1,
	}
}

type wireEnvelope struct {
	Input          json.RawMessage `json:"input"`
	IdempotencyKey string          `json:"idempotencyKey"`
	Job            *struct {
		ID       string  `json:"id"`
		Workflow string  `json:"workflow"`
		Version  string  `json:"version"`
		TraceID  *string `json:"traceId"`
	} `json:"job"`
	Step        string `json:"step"`
	Environment string `json:"environment"`
	Attempt     int    `json:"attempt"`
}

// dispatch runs one call the way the contract says: read the envelope, check the input against
// the action's schema, run the handler, check the output.
func (p *Provider) dispatch(ctx context.Context, a *Action, payload json.RawMessage, heartbeat func(...any)) (json.RawMessage, error) {
	call, input, err := read(a.id, payload)
	if err != nil {
		return nil, err
	}
	call.heartbeat = heartbeat
	if ps := problems(a.inputCheck, input); len(ps) > 0 {
		return nil, Fatal(InvalidInput, fmt.Sprintf("The input of %s does not match its schema: %s", a.id, strings.Join(ps, "; ")))
	}
	out, err := a.run(ctx, call, input)
	if err != nil {
		return nil, classify(ctx, a.id, err)
	}
	b, err := json.Marshal(out)
	if err != nil {
		return nil, Fatal(InvalidOutput, fmt.Sprintf("The output of %s cannot be written as JSON: %v", a.id, err))
	}
	if bytes.Equal(b, []byte("null")) {
		b = []byte("{}")
	}
	if ps := problems(a.outputCheck, b); len(ps) > 0 {
		return nil, Fatal(InvalidOutput, fmt.Sprintf("The output of %s does not match its schema: %s", a.id, strings.Join(ps, "; ")))
	}
	return b, nil
}

func read(action string, payload json.RawMessage) (*Call, json.RawMessage, error) {
	trimmed := bytes.TrimSpace(payload)
	var env wireEnvelope
	if len(trimmed) == 0 || trimmed[0] != '{' || json.Unmarshal(trimmed, &env) != nil {
		return nil, nil, Fatal(InvalidRequest, fmt.Sprintf("The call to %s is not the platform's envelope (a JSON object with input)", action))
	}
	input := bytes.TrimSpace(env.Input)
	switch {
	case len(input) == 0 || bytes.Equal(input, []byte("null")):
		input = []byte("{}")
	case input[0] != '{':
		return nil, nil, Fatal(InvalidRequest, fmt.Sprintf("The input of %s is a JSON object", action))
	}
	call := &Call{
		Action:         action,
		IdempotencyKey: env.IdempotencyKey,
		Step:           env.Step,
		Environment:    env.Environment,
		Attempt:        max(1, env.Attempt),
	}
	if env.Job != nil {
		call.Job = Job{ID: env.Job.ID, Workflow: env.Job.Workflow, Version: env.Job.Version}
		if env.Job.TraceID != nil {
			call.Job.TraceID = *env.Job.TraceID
		}
	}
	return call, input, nil
}

// classify keeps a classified failure, lets a cancel or a timeout reach Temporal as it is, and
// makes anything else the retryable class Unclassified.
func classify(ctx context.Context, action string, err error) error {
	var pe *Error
	if errors.As(err, &pe) {
		return pe
	}
	var ae *temporal.ApplicationError
	if errors.As(err, &ae) {
		return err
	}
	if ctx.Err() != nil && (errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded)) {
		return err
	}
	if activity.IsActivity(ctx) {
		activity.GetLogger(ctx).Warn("action failed with an unclassified error (reported as the retryable class error)", "action", action, "error", err)
	}
	return &Error{Class: Unclassified, Message: err.Error(), Retryable: true}
}
