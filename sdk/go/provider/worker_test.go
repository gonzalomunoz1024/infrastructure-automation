package provider

import (
	"context"
	"encoding/json"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"
	"go.temporal.io/sdk/workflow"
)

// outcome is what came back to the platform from one call.
type outcome struct {
	Output       json.RawMessage `json:"output,omitempty"`
	Type         string          `json:"type,omitempty"`
	NonRetryable bool            `json:"nonRetryable,omitempty"`
	Message      string          `json:"message,omitempty"`
	Cancelled    bool            `json:"cancelled,omitempty"`
}

// platformCall calls an action the way the platform's interpreter does: an activity of the
// action's type, no Temporal-level retries, cancelled with the step (here after cancelAfter).
func platformCall(ctx workflow.Context, action string, payload any, heartbeat, cancelAfter time.Duration) (outcome, error) {
	ctx = workflow.WithActivityOptions(ctx, workflow.ActivityOptions{
		StartToCloseTimeout: 20 * time.Second,
		HeartbeatTimeout:    heartbeat,
		RetryPolicy:         &temporal.RetryPolicy{MaximumAttempts: 1},
		WaitForCancellation: true,
	})
	callCtx, cancel := workflow.WithCancel(ctx)
	f := workflow.ExecuteActivity(callCtx, action, payload)
	if cancelAfter > 0 {
		_ = workflow.Sleep(ctx, cancelAfter)
		cancel()
	}
	var out json.RawMessage
	err := f.Get(ctx, &out)
	var app *temporal.ApplicationError
	var canceled *temporal.CanceledError
	switch {
	case err == nil:
		return outcome{Output: out}, nil
	case errors.As(err, &app):
		return outcome{Type: app.Type(), NonRetryable: app.NonRetryable(), Message: app.Message()}, nil
	case errors.As(err, &canceled):
		return outcome{Cancelled: true}, nil
	}
	return outcome{}, err
}

type harness struct {
	testsuite.WorkflowTestSuite
	env *testsuite.TestWorkflowEnvironment
}

func serve(t *testing.T, p *Provider) *harness {
	h := &harness{}
	h.env = h.NewTestWorkflowEnvironment()
	h.env.RegisterWorkflow(platformCall)
	Register(h.env, p)
	t.Cleanup(func() { h.env.AssertExpectations(t) })
	return h
}

func (h *harness) call(t *testing.T, action string, payload any, heartbeat, cancelAfter time.Duration) outcome {
	t.Helper()
	h.env.ExecuteWorkflow(platformCall, action, payload, heartbeat, cancelAfter)
	require.True(t, h.env.IsWorkflowCompleted())
	require.NoError(t, h.env.GetWorkflowError())
	var o outcome
	require.NoError(t, h.env.GetWorkflowResult(&o))
	return o
}

func probe(t *testing.T, handler Handler[map[string]any, map[string]any]) *Provider {
	t.Helper()
	p, err := New("probe").Owner("team-platform").Version("1.0.0").
		Action(NewAction("probe.run", handler).Effect(Read)).
		Build()
	require.NoError(t, err)
	return p
}

func TestACallRunsTheActionAndAnswersItsOutput(t *testing.T) {
	h := serve(t, newInventory().builder().MustBuild())

	o := h.call(t, "item.get", TestEnvelope(map[string]any{"id": "it-1"}), 0, 0)

	assert.JSONEq(t, `{"id":"it-1","name":"first","state":"active"}`, string(o.Output))
}

func TestTheHandlerSeesTheFactsOfTheEnvelope(t *testing.T) {
	var seen *Call
	h := serve(t, probe(t, func(_ context.Context, call *Call, in map[string]any) (map[string]any, error) {
		seen = call
		return in, nil
	}))
	payload := map[string]any{
		"input":          map[string]any{"a": "b"},
		"idempotencyKey": "job-7:create:3",
		"job":            map[string]any{"id": "job-7", "workflow": "add-item", "version": "1.0.0", "traceId": nil},
		"step":           "create",
		"environment":    "dev",
		"attempt":        2,
	}

	o := h.call(t, "probe.run", payload, 0, 0)

	assert.JSONEq(t, `{"a":"b"}`, string(o.Output))
	require.NotNil(t, seen)
	assert.Equal(t, "probe.run", seen.Action)
	assert.Equal(t, "job-7:create:3", seen.IdempotencyKey)
	assert.Equal(t, Job{ID: "job-7", Workflow: "add-item", Version: "1.0.0"}, seen.Job)
	assert.Equal(t, "create", seen.Step)
	assert.Equal(t, "dev", seen.Environment)
	assert.Equal(t, 2, seen.Attempt)
}

func TestARepeatedCallWithTheSameKeyAnswersTheFirstResult(t *testing.T) {
	inv := newInventory()
	p := inv.builder().MustBuild()

	first := serve(t, p).call(t, "item.create", TestEnvelope(map[string]any{"name": "second"}), 0, 0)
	again := serve(t, p).call(t, "item.create", TestEnvelope(map[string]any{"name": "second"}), 0, 0)

	assert.JSONEq(t, string(first.Output), string(again.Output))
	assert.Len(t, inv.items, 2)
}

func TestAFatalFailureReachesThePlatformAsANonRetryableErrorOfItsClass(t *testing.T) {
	h := serve(t, newInventory().builder().MustBuild())

	o := h.call(t, "item.get", TestEnvelope(map[string]any{"id": "it-404"}), 0, 0)

	assert.Equal(t, outcome{Type: "not_found", NonRetryable: true, Message: "No item it-404"}, o)
}

func TestARetryableFailureReachesThePlatformAsARetryableErrorOfItsClass(t *testing.T) {
	h := serve(t, probe(t, func(context.Context, *Call, map[string]any) (map[string]any, error) {
		return nil, Retryable("busy", "The inventory is busy")
	}))

	o := h.call(t, "probe.run", TestEnvelope(nil), 0, 0)

	assert.Equal(t, outcome{Type: "busy", NonRetryable: false, Message: "The inventory is busy"}, o)
}

func TestAnUnclassifiedErrorIsTheRetryableClassError(t *testing.T) {
	h := serve(t, probe(t, func(context.Context, *Call, map[string]any) (map[string]any, error) {
		return nil, errors.New("connection reset")
	}))

	o := h.call(t, "probe.run", TestEnvelope(nil), 0, 0)

	assert.Equal(t, outcome{Type: "error", NonRetryable: false, Message: "connection reset"}, o)
}

func TestABrokenContractIsFatal(t *testing.T) {
	for _, c := range []struct {
		name    string
		action  string
		payload any
		class   string
	}{
		{"input that breaks the schema", "item.get", TestEnvelope(map[string]any{"name": "no id"}), InvalidInput},
		{"input of the wrong type", "item.get", TestEnvelope(map[string]any{"id": 42}), InvalidInput},
		{"an action the provider lacks", "item.melt", TestEnvelope(nil), UnknownAction},
		{"a payload that is not the envelope", "item.get", "it-1", InvalidRequest},
		{"an input that is not an object", "item.get", map[string]any{"input": "it-1"}, InvalidRequest},
	} {
		t.Run(c.name, func(t *testing.T) {
			h := serve(t, newInventory().builder().MustBuild())

			o := h.call(t, c.action, c.payload, 0, 0)

			assert.Equal(t, c.class, o.Type)
			assert.True(t, o.NonRetryable)
		})
	}
}

func TestOutputThatBreaksTheSchemaIsFatal(t *testing.T) {
	p := New("probe").Owner("team-platform").Version("1.0.0").
		Action(NewAction("item.get", func(_ context.Context, _ *Call, in lookup) (map[string]any, error) {
			return map[string]any{"id": in.ID}, nil
		}).Effect(Read).Output(`{"type":"object","required":["id","name"]}`)).
		MustBuild()
	h := serve(t, p)

	o := h.call(t, "item.get", TestEnvelope(map[string]any{"id": "it-9"}), 0, 0)

	assert.Equal(t, InvalidOutput, o.Type)
	assert.True(t, o.NonRetryable)
	assert.Contains(t, o.Message, "name")
}

func TestAHandlerThatReturnsNothingAnswersAnEmptyObject(t *testing.T) {
	h := serve(t, probe(t, func(context.Context, *Call, map[string]any) (map[string]any, error) { return nil, nil }))

	o := h.call(t, "probe.run", TestEnvelope(map[string]any{"a": 1}), 0, 0)

	assert.JSONEq(t, `{}`, string(o.Output))
}

func TestHeartbeatsReachTemporal(t *testing.T) {
	h := serve(t, probe(t, func(_ context.Context, call *Call, in map[string]any) (map[string]any, error) {
		call.Heartbeat("scanned 10 of 20")
		return in, nil
	}))
	var details []string
	h.env.SetOnActivityHeartbeatListener(func(_ *activity.Info, d converter.EncodedValues) {
		var s string
		if d.HasValues() && d.Get(&s) == nil {
			details = append(details, s)
		}
	})

	o := h.call(t, "probe.run", TestEnvelope(map[string]any{"a": 1}), 5*time.Second, 0)

	assert.JSONEq(t, `{"a":1}`, string(o.Output))
	assert.Equal(t, []string{"scanned 10 of 20"}, details)
}

func TestCancellingTheStepCancelsTheHandlersContext(t *testing.T) {
	var sawCancel atomic.Bool
	h := serve(t, probe(t, func(ctx context.Context, call *Call, in map[string]any) (map[string]any, error) {
		for i := 0; i < 200; i++ {
			call.Heartbeat(i)
			select {
			case <-ctx.Done():
				sawCancel.Store(true)
				return nil, ctx.Err()
			case <-time.After(20 * time.Millisecond):
			}
		}
		return in, nil
	}))

	o := h.call(t, "probe.run", TestEnvelope(nil), time.Second, 300*time.Millisecond)

	assert.True(t, o.Cancelled)
	// the next heartbeat tells the handler (heartbeats are throttled to 80% of the heartbeat timeout)
	assert.Eventually(t, sawCancel.Load, 5*time.Second, 10*time.Millisecond)
}

func TestRunServesActivitiesOnlyAndDrainsForTheDrainTimeout(t *testing.T) {
	o := runOptions(Options{Address: "127.0.0.1:7233", Namespace: "default", DrainTimeout: 45 * time.Second})
	assert.True(t, o.DisableWorkflowWorker, "a provider's worker never polls for workflow tasks")
	assert.Equal(t, 45*time.Second, o.WorkerStopTimeout)
}

func TestOptionsComeFromTheEnvironment(t *testing.T) {
	assert.Equal(t, Options{Address: "127.0.0.1:7233", Namespace: "default", DrainTimeout: 30 * time.Second},
		optionsFrom(func(string) string { return "" }))
	env := map[string]string{"TEMPORAL_ADDRESS": "temporal-frontend.temporal:7233", "TEMPORAL_NAMESPACE": "platform", "WORKER_DRAIN_SECONDS": "90"}
	assert.Equal(t, Options{Address: "temporal-frontend.temporal:7233", Namespace: "platform", DrainTimeout: 90 * time.Second},
		optionsFrom(func(k string) string { return env[k] }))
}
