package provider

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/worker"
)

// Registry is where the actions register: a worker.Worker, or one of Temporal's test
// environments (testsuite.TestActivityEnvironment, testsuite.TestWorkflowEnvironment).
type Registry interface {
	RegisterActivityWithOptions(a interface{}, options activity.RegisterOptions)
}

type dynamicRegistry interface {
	RegisterDynamicActivity(a interface{}, options activity.DynamicRegisterOptions)
}

// Register registers every action of the provider as an activity named by its id, and, where the
// registry takes one, a dynamic activity that answers any other name with unknown_action.
func Register(r Registry, p *Provider) {
	for _, a := range p.actions {
		r.RegisterActivityWithOptions(p.activity(a), activity.RegisterOptions{Name: a.id})
	}
	if d, ok := r.(dynamicRegistry); ok {
		d.RegisterDynamicActivity(p.unknown, activity.DynamicRegisterOptions{})
	}
}

func (p *Provider) activity(a *Action) func(context.Context, json.RawMessage) (json.RawMessage, error) {
	return func(ctx context.Context, payload json.RawMessage) (json.RawMessage, error) {
		out, err := p.dispatch(ctx, a, payload, func(details ...any) { activity.RecordHeartbeat(ctx, details...) })
		return out, toTemporal(err)
	}
}

func (p *Provider) unknown(ctx context.Context, _ converter.EncodedValues) (any, error) {
	name := activity.GetInfo(ctx).ActivityType.Name
	return nil, toTemporal(Fatal(UnknownAction, fmt.Sprintf("Provider %s has no action '%s'", p.name, name)))
}

// Options says where the worker finds Temporal and how long a stopping worker lets the calls in
// flight finish.
type Options struct {
	Address      string
	Namespace    string
	DrainTimeout time.Duration
}

// OptionsFromEnv reads TEMPORAL_ADDRESS (default 127.0.0.1:7233), TEMPORAL_NAMESPACE (default
// "default") and WORKER_DRAIN_SECONDS (default 30).
func OptionsFromEnv() Options {
	return optionsFrom(os.Getenv)
}

func optionsFrom(env func(string) string) Options {
	o := Options{Address: "127.0.0.1:7233", Namespace: "default", DrainTimeout: 30 * time.Second}
	if v := strings.TrimSpace(env("TEMPORAL_ADDRESS")); v != "" {
		o.Address = v
	}
	if v := strings.TrimSpace(env("TEMPORAL_NAMESPACE")); v != "" {
		o.Namespace = v
	}
	if v := strings.TrimSpace(env("WORKER_DRAIN_SECONDS")); v != "" {
		if s, err := strconv.Atoi(v); err == nil && s >= 0 {
			o.DrainTimeout = time.Duration(s) * time.Second
		}
	}
	return o
}

// NewWorker is a worker on the provider's task queue with its actions registered, on a client
// you built (TLS, API keys, interceptors). Stopping it lets the calls in flight finish for
// opts.WorkerStopTimeout (30s when zero).
func NewWorker(c client.Client, p *Provider, opts worker.Options) worker.Worker {
	if opts.WorkerStopTimeout == 0 {
		opts.WorkerStopTimeout = 30 * time.Second
	}
	w := worker.New(c, p.taskQueue, opts)
	Register(w, p)
	return w
}

// runOptions is the worker Run starts: activities only (it registers no workflow, so it does not
// poll the queue for workflow tasks) and the drain timeout as its stop timeout.
func runOptions(opts Options) worker.Options {
	return worker.Options{WorkerStopTimeout: opts.DrainTimeout, DisableWorkflowWorker: true}
}

// Run connects to Temporal, serves the provider until ctx is done or the process gets SIGINT or
// SIGTERM, then stops polling and lets the calls in flight finish for the drain timeout.
func Run(ctx context.Context, p *Provider, opts Options) error {
	c, err := client.Dial(client.Options{HostPort: opts.Address, Namespace: opts.Namespace})
	if err != nil {
		return fmt.Errorf("connect to Temporal at %s: %w", opts.Address, err)
	}
	defer c.Close()
	w := NewWorker(c, p, runOptions(opts))
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := w.Start(); err != nil {
		return fmt.Errorf("start the worker on %s: %w", p.taskQueue, err)
	}
	slog.Info("provider serves its actions", "provider", p.name, "version", p.version, "actions", len(p.actions),
		"taskQueue", p.taskQueue, "address", opts.Address, "namespace", opts.Namespace)
	<-ctx.Done()
	slog.Info("provider stops polling and drains", "provider", p.name, "drainTimeout", opts.DrainTimeout)
	w.Stop()
	return nil
}
