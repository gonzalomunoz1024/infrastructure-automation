package provider

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
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

// Options says where the worker finds Temporal, how it proves who it is there, and how long a
// stopping worker lets the calls in flight finish. A CA, a client certificate or an API key makes
// the connection TLS: the CA verifies the frontend (the system's CAs without one), the certificate
// and its key are the worker's identity for mutual TLS, the API key (Temporal Cloud) goes with
// every request as a bearer token.
type Options struct {
	Address      string
	Namespace    string
	DrainTimeout time.Duration
	// TLSCA is a PEM file of the CA that signed the frontend's certificate.
	TLSCA string
	// TLSCert and TLSKey are PEM files of the worker's client certificate and its key, for mutual
	// TLS; both or neither.
	TLSCert, TLSKey string
	// APIKey is a Temporal Cloud API key. String never shows it.
	APIKey string
}

// String describes the options without the API key.
func (o Options) String() string {
	key := "none"
	if o.APIKey != "" {
		key = "(set)"
	}
	return fmt.Sprintf("{Address:%s Namespace:%s DrainTimeout:%s TLSCA:%s TLSCert:%s TLSKey:%s APIKey:%s}",
		o.Address, o.Namespace, o.DrainTimeout, o.TLSCA, o.TLSCert, o.TLSKey, key)
}

// OptionsFromEnv reads TEMPORAL_ADDRESS (default 127.0.0.1:7233), TEMPORAL_NAMESPACE (default
// "default"), WORKER_DRAIN_SECONDS (default 30; a value that is not a whole number of seconds is
// logged and falls back to it, as in the other SDKs), and IAF_TEMPORAL_TLS_CA,
// IAF_TEMPORAL_TLS_CERT, IAF_TEMPORAL_TLS_KEY and IAF_TEMPORAL_API_KEY for TLS and Temporal Cloud.
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
	o.TLSCA = strings.TrimSpace(env("IAF_TEMPORAL_TLS_CA"))
	o.TLSCert = strings.TrimSpace(env("IAF_TEMPORAL_TLS_CERT"))
	o.TLSKey = strings.TrimSpace(env("IAF_TEMPORAL_TLS_KEY"))
	o.APIKey = strings.TrimSpace(env("IAF_TEMPORAL_API_KEY"))
	if v := strings.TrimSpace(env("WORKER_DRAIN_SECONDS")); v != "" {
		if s, err := strconv.Atoi(v); err == nil && s >= 0 {
			o.DrainTimeout = time.Duration(s) * time.Second
		} else {
			slog.Warn(fmt.Sprintf("WORKER_DRAIN_SECONDS is a whole number of seconds, not '%s'; the worker drains for %ds",
				v, int(o.DrainTimeout.Seconds())))
		}
	}
	return o
}

// NewWorker is a worker on the provider's task queue with its actions registered, on a client
// you built (interceptors, a data converter). Stopping it lets the calls in flight finish for
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

// clientOptions is how the worker dials Temporal: TLS when a CA, a client certificate or an API
// key asks for it, and the API key as the credentials. A file that cannot be read fails here,
// naming the variable it comes from.
func clientOptions(o Options) (client.Options, error) {
	co := client.Options{HostPort: o.Address, Namespace: o.Namespace}
	if (o.TLSCert == "") != (o.TLSKey == "") {
		return co, errors.New("IAF_TEMPORAL_TLS_CERT and IAF_TEMPORAL_TLS_KEY go together: the worker's client certificate and its key, for mutual TLS")
	}
	if o.TLSCA != "" || o.TLSCert != "" || o.APIKey != "" {
		cfg := &tls.Config{MinVersion: tls.VersionTLS12}
		if o.TLSCA != "" {
			pem, err := os.ReadFile(o.TLSCA)
			if err != nil {
				return co, fmt.Errorf("read the CA of the Temporal frontend (IAF_TEMPORAL_TLS_CA): %w", err)
			}
			cfg.RootCAs = x509.NewCertPool()
			if !cfg.RootCAs.AppendCertsFromPEM(pem) {
				return co, fmt.Errorf("%s (IAF_TEMPORAL_TLS_CA) holds no PEM certificate", o.TLSCA)
			}
		}
		if o.TLSCert != "" {
			pair, err := tls.LoadX509KeyPair(o.TLSCert, o.TLSKey)
			if err != nil {
				return co, fmt.Errorf("load the client certificate and key (IAF_TEMPORAL_TLS_CERT, IAF_TEMPORAL_TLS_KEY): %w", err)
			}
			cfg.Certificates = []tls.Certificate{pair}
		}
		co.ConnectionOptions.TLS = cfg
	}
	if o.APIKey != "" {
		co.Credentials = client.NewAPIKeyStaticCredentials(o.APIKey)
	}
	return co, nil
}

// connection describes how the worker connects, for its start-up line: never the key itself.
func connection(o Options) string {
	transport := "plaintext"
	switch {
	case o.TLSCert != "":
		transport = "mutual TLS"
	case o.TLSCA != "" || o.APIKey != "":
		transport = "TLS"
	}
	if o.APIKey != "" {
		return transport + " with an API key"
	}
	return transport
}

// Run connects to Temporal (TLS and an API key when the options say so), serves the provider until
// ctx is done or the process gets SIGINT or SIGTERM, then stops polling and lets the calls in
// flight finish for the drain timeout.
func Run(ctx context.Context, p *Provider, opts Options) error {
	co, err := clientOptions(opts)
	if err != nil {
		return err
	}
	c, err := client.Dial(co)
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
		"taskQueue", p.taskQueue, "address", opts.Address, "namespace", opts.Namespace, "connection", connection(opts))
	<-ctx.Done()
	slog.Info("provider stops polling and drains", "provider", p.name, "drainTimeout", opts.DrainTimeout)
	w.Stop()
	return nil
}
