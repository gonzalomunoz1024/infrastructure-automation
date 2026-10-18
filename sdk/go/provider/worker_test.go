package provider

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"os"
	"path/filepath"
	"strings"
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

func TestADrainTimeThatIsNotAWholeNumberOfSecondsFallsBackToThirtySeconds(t *testing.T) {
	var logged bytes.Buffer
	defer slog.SetDefault(slog.Default())
	slog.SetDefault(slog.New(slog.NewTextHandler(&logged, nil)))

	for _, drain := range []string{"30s", "1.5", "-5", "soon"} {
		assert.Equal(t, 30*time.Second, optionsFrom(func(k string) string {
			if k == "WORKER_DRAIN_SECONDS" {
				return drain
			}
			return ""
		}).DrainTimeout, drain)
	}

	assert.Equal(t, 4, strings.Count(logged.String(), "level=WARN"))
	assert.Contains(t, logged.String(), "WORKER_DRAIN_SECONDS is a whole number of seconds, not 'soon'; the worker drains for 30s")
}

func TestOptionsComeFromTheEnvironment(t *testing.T) {
	assert.Equal(t, Options{Address: "127.0.0.1:7233", Namespace: "default", DrainTimeout: 30 * time.Second},
		optionsFrom(func(string) string { return "" }))
	env := map[string]string{"TEMPORAL_ADDRESS": "temporal-frontend.temporal:7233", "TEMPORAL_NAMESPACE": "platform", "WORKER_DRAIN_SECONDS": "90"}
	assert.Equal(t, Options{Address: "temporal-frontend.temporal:7233", Namespace: "platform", DrainTimeout: 90 * time.Second},
		optionsFrom(func(k string) string { return env[k] }))
}

// writeCerts writes a test CA, a client certificate it signed and the client's key, as PEM files.
func writeCerts(t *testing.T) (ca, cert, key string) {
	t.Helper()
	dir := t.TempDir()
	write := func(name, typ string, der []byte) string {
		p := filepath.Join(dir, name)
		require.NoError(t, os.WriteFile(p, pem.EncodeToMemory(&pem.Block{Type: typ, Bytes: der}), 0o600))
		return p
	}
	now := time.Now()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	caTemplate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test CA"},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	require.NoError(t, err)
	caCert, err := x509.ParseCertificate(caDER)
	require.NoError(t, err)
	clientKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	clientTemplate := &x509.Certificate{SerialNumber: big.NewInt(2), Subject: pkix.Name{CommonName: "provider-worker"},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}
	clientDER, err := x509.CreateCertificate(rand.Reader, clientTemplate, caCert, &clientKey.PublicKey, caKey)
	require.NoError(t, err)
	keyDER, err := x509.MarshalPKCS8PrivateKey(clientKey)
	require.NoError(t, err)
	return write("ca.crt", "CERTIFICATE", caDER), write("tls.crt", "CERTIFICATE", clientDER), write("tls.key", "PRIVATE KEY", keyDER)
}

func TestAPlainConnectionHasNoTLSAndNoCredentials(t *testing.T) {
	co, err := clientOptions(optionsFrom(func(string) string { return "" }))

	require.NoError(t, err)
	assert.Equal(t, "127.0.0.1:7233", co.HostPort)
	assert.Equal(t, "default", co.Namespace)
	assert.Nil(t, co.ConnectionOptions.TLS)
	assert.Nil(t, co.Credentials)
	assert.Equal(t, "plaintext", connection(Options{}))
}

func TestTheEnvironmentNamesTheTLSFilesAndTheAPIKey(t *testing.T) {
	ca, cert, key := writeCerts(t)
	env := map[string]string{"TEMPORAL_ADDRESS": "platform.a1b2c.tmprl.cloud:7233", "TEMPORAL_NAMESPACE": "platform.a1b2c",
		"IAF_TEMPORAL_TLS_CA": ca, "IAF_TEMPORAL_TLS_CERT": cert, "IAF_TEMPORAL_TLS_KEY": " " + key + " ", "IAF_TEMPORAL_API_KEY": " test-api-key "}

	o := optionsFrom(func(k string) string { return env[k] })
	co, err := clientOptions(o)

	assert.Equal(t, Options{Address: "platform.a1b2c.tmprl.cloud:7233", Namespace: "platform.a1b2c", DrainTimeout: 30 * time.Second,
		TLSCA: ca, TLSCert: cert, TLSKey: key, APIKey: "test-api-key"}, o)
	require.NoError(t, err)
	require.NotNil(t, co.ConnectionOptions.TLS)
	caPEM, err := os.ReadFile(ca)
	require.NoError(t, err)
	want := x509.NewCertPool()
	require.True(t, want.AppendCertsFromPEM(caPEM))
	assert.True(t, want.Equal(co.ConnectionOptions.TLS.RootCAs), "the CA verifies the frontend")
	require.Len(t, co.ConnectionOptions.TLS.Certificates, 1)
	assert.Equal(t, "provider-worker", co.ConnectionOptions.TLS.Certificates[0].Leaf.Subject.CommonName)
	assert.NotNil(t, co.Credentials, "the API key")
	assert.Equal(t, "mutual TLS with an API key", connection(o))
	assert.NotContains(t, fmt.Sprint(o), "test-api-key")
	assert.Contains(t, fmt.Sprint(o), "APIKey:(set)")
}

func TestAnAPIKeyAloneTalksTLSWithTheSystemsCAs(t *testing.T) {
	o := Options{Address: "platform.a1b2c.tmprl.cloud:7233", Namespace: "platform.a1b2c", APIKey: "test-api-key"}

	co, err := clientOptions(o)

	require.NoError(t, err)
	require.NotNil(t, co.ConnectionOptions.TLS)
	assert.Nil(t, co.ConnectionOptions.TLS.RootCAs)
	assert.Empty(t, co.ConnectionOptions.TLS.Certificates)
	assert.NotNil(t, co.Credentials)
	assert.Equal(t, "TLS with an API key", connection(o))
}

func TestTLSSettingsThatCannotWorkNameTheirVariable(t *testing.T) {
	ca, cert, _ := writeCerts(t)
	notPEM := filepath.Join(t.TempDir(), "ca.crt")
	require.NoError(t, os.WriteFile(notPEM, []byte("not a certificate\n"), 0o600))

	for _, c := range []struct {
		o    Options
		want string
	}{
		{Options{TLSCert: cert}, "IAF_TEMPORAL_TLS_CERT and IAF_TEMPORAL_TLS_KEY go together"},
		{Options{TLSCA: filepath.Join(t.TempDir(), "missing.crt")}, "(IAF_TEMPORAL_TLS_CA)"},
		{Options{TLSCA: notPEM}, notPEM + " (IAF_TEMPORAL_TLS_CA) holds no PEM certificate"},
		{Options{TLSCert: cert, TLSKey: ca}, "(IAF_TEMPORAL_TLS_CERT, IAF_TEMPORAL_TLS_KEY)"},
	} {
		_, err := clientOptions(c.o)

		require.Error(t, err, c.want)
		assert.Contains(t, err.Error(), c.want)
	}
}

const (
	testTraceID     = "0af7651916cd43dd8448eb211c80319c"
	testStepSpan    = "b7ad6b7169203331"
	testTraceParent = "00-" + testTraceID + "-" + testStepSpan + "-01"
)

type spanKey struct{}

// spans is a SpanStarter that records what it was asked to start and how each span ended.
type spans struct {
	started []string
	calls   []*Call
	ended   []error
}

func (s *spans) start(ctx context.Context, name string, call *Call) (context.Context, func(error)) {
	s.started = append(s.started, name)
	s.calls = append(s.calls, call)
	return context.WithValue(ctx, spanKey{}, name), func(err error) { s.ended = append(s.ended, err) }
}

func tracedProbe(t *testing.T, fail bool, seen *[]any) *Provider {
	t.Helper()
	return New("probe").Owner("team-platform").Version("1.4.0").
		Error("quota_exceeded", false, "No room left").
		Action(NewAction("probe.run", func(ctx context.Context, call *Call, in map[string]any) (map[string]any, error) {
			*seen = append(*seen, call.TraceParent, ctx.Value(spanKey{}))
			if fail {
				return nil, Fatal("quota_exceeded", "No room left for team-x")
			}
			return in, nil
		}).Effect(Read)).
		MustBuild()
}

func TestACallWithATraceparentRunsInTheSpanTheStarterStarts(t *testing.T) {
	var seen []any
	rec := &spans{}
	p := tracedProbe(t, false, &seen).WithTracing(rec.start)
	payload := `{"input":{"a":"b"},"idempotencyKey":"job-7:run:1","job":{"id":"job-7","workflow":"w","version":"1.0.0","traceId":"` +
		testTraceID + `","traceparent":"` + testTraceParent + `"},"step":"run","environment":"dev","attempt":2}`

	out, err := p.dispatch(context.Background(), p.byID["probe.run"], json.RawMessage(payload), nil)

	require.NoError(t, err)
	assert.JSONEq(t, `{"a":"b"}`, string(out))
	assert.Equal(t, []string{"run probe.probe.run"}, rec.started)
	assert.Equal(t, testTraceParent, rec.calls[0].TraceParent)
	assert.Equal(t, "job-7", rec.calls[0].Job.ID)
	assert.Equal(t, 2, rec.calls[0].Attempt)
	assert.Equal(t, []any{testTraceParent, "run probe.probe.run"}, seen, "the handler sees the traceparent and runs with the span's context")
	assert.Equal(t, []error{nil}, rec.ended)
}

func TestAFailedCallEndsItsSpanWithTheError(t *testing.T) {
	var seen []any
	rec := &spans{}
	p := tracedProbe(t, true, &seen).WithTracing(rec.start)
	env := TestEnvelope(map[string]any{"a": "b"})
	env.TraceParent = testTraceParent
	payload, err := json.Marshal(env)
	require.NoError(t, err)

	_, err = p.dispatch(context.Background(), p.byID["probe.run"], payload, nil)

	require.Error(t, err)
	require.Len(t, rec.ended, 1)
	var pe *Error
	require.ErrorAs(t, rec.ended[0], &pe)
	assert.Equal(t, "quota_exceeded", pe.Class)
}

func TestWithoutAValidTraceparentNoSpanStarts(t *testing.T) {
	var seen []any
	rec := &spans{}
	p := tracedProbe(t, false, &seen).WithTracing(rec.start)

	for _, tp := range []string{"", "not-a-traceparent", "00-" + strings.Repeat("0", 32) + "-" + testStepSpan + "-01",
		"ff-" + testTraceID + "-" + testStepSpan + "-01", "00-" + strings.ToUpper(testTraceID) + "-" + testStepSpan + "-01"} {
		env := TestEnvelope(map[string]any{})
		env.TraceParent = tp
		payload, err := json.Marshal(env)
		require.NoError(t, err)

		_, err = p.dispatch(context.Background(), p.byID["probe.run"], payload, nil)

		require.NoError(t, err, tp)
	}
	assert.Empty(t, rec.started)
	assert.Equal(t, "", seen[0], "no traceparent")
	assert.Equal(t, "not-a-traceparent", seen[2], "what the platform sent reaches the handler")
	assert.Nil(t, seen[3], "no span context")
}

func TestWithoutAStarterTheCallRunsAsBefore(t *testing.T) {
	var seen []any
	p := tracedProbe(t, false, &seen)
	env := TestEnvelope(map[string]any{"a": "b"})
	env.TraceParent = testTraceParent
	payload, err := json.Marshal(env)
	require.NoError(t, err)

	out, err := p.dispatch(context.Background(), p.byID["probe.run"], payload, nil)

	require.NoError(t, err)
	assert.JSONEq(t, `{"a":"b"}`, string(out))
	assert.Equal(t, []any{testTraceParent, nil}, seen)
	assert.Nil(t, p.tracing, "WithTracing returns a copy")
}
