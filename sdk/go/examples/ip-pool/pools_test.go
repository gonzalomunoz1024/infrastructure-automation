package main

import (
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/gonzalomunoz1024/infrastructure-automation/sdk/go/provider"
)

// The provider's actions, called through Temporal's activity test environment the way the
// platform calls them: the activity type is the action id, the argument the platform's envelope.

func pools(t *testing.T) (*Pools, *testsuite.TestActivityEnvironment) {
	t.Helper()
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	ps := NewPools(map[string]string{"lab": "10.30.0.0/29", "tiny": "10.40.0.0/30"})
	provider.Register(env, ps.Provider())
	return ps, env
}

func call[T any](t *testing.T, env *testsuite.TestActivityEnvironment, action string, envelope provider.Envelope) T {
	t.Helper()
	v, err := env.ExecuteActivity(action, envelope)
	require.NoError(t, err)
	var out T
	require.NoError(t, v.Get(&out))
	return out
}

func failure(t *testing.T, env *testsuite.TestActivityEnvironment, action string, envelope provider.Envelope) *temporal.ApplicationError {
	t.Helper()
	_, err := env.ExecuteActivity(action, envelope)
	var app *temporal.ApplicationError
	require.True(t, errors.As(err, &app), "want an ApplicationError, got %v", err)
	return app
}

func TestReservingTakesTheNextFreeAddressAndThePoolCountsIt(t *testing.T) {
	_, env := pools(t)

	a := call[Address](t, env, "address.reserve", provider.TestEnvelope(Reservation{Pool: "lab", Hostname: "web-01"}))
	pool := call[Pool](t, env, "pool.get", provider.TestEnvelope(PoolRef{Pool: "lab"}))

	assert.Equal(t, Address{Address: "10.30.0.1", Pool: "lab", Hostname: "web-01", State: "reserved"}, a)
	assert.Equal(t, Pool{Name: "lab", CIDR: "10.30.0.0/29", Free: 5, Reserved: 1}, pool)
}

func TestARetriedReservationAnswersTheAddressItAlreadyReserved(t *testing.T) {
	_, env := pools(t)
	first := provider.TestEnvelope(Reservation{Pool: "lab", Hostname: "web-02"})
	first.IdempotencyKey = "job-3:reserve:2"
	retry := first
	retry.Attempt = 2

	a := call[Address](t, env, "address.reserve", first)
	b := call[Address](t, env, "address.reserve", retry)

	assert.Equal(t, a, b)
	assert.Equal(t, 1, call[Pool](t, env, "pool.get", provider.TestEnvelope(PoolRef{Pool: "lab"})).Reserved)
}

func TestThePreviewSaysWhatAReservationWouldDo(t *testing.T) {
	_, env := pools(t)

	plan := call[Plan](t, env, "address.preview", provider.TestEnvelope(Reservation{Pool: "lab", Hostname: "web-03"}))

	assert.Equal(t, Plan{Action: "reserve", Address: "10.30.0.1", Summary: "Reserves 10.30.0.1 in lab for web-03"}, plan)
	assert.Equal(t, 0, call[Pool](t, env, "pool.get", provider.TestEnvelope(PoolRef{Pool: "lab"})).Reserved, "a preview reserves nothing")
}

func TestAFullPoolIsExhaustedForGood(t *testing.T) {
	_, env := pools(t)
	for i, host := range []string{"a", "b"} {
		e := provider.TestEnvelope(Reservation{Pool: "tiny", Hostname: host})
		e.IdempotencyKey = []string{"k1", "k2"}[i]
		call[Address](t, env, "address.reserve", e)
	}
	e := provider.TestEnvelope(Reservation{Pool: "tiny", Hostname: "c"})
	e.IdempotencyKey = "k3"

	f := failure(t, env, "address.reserve", e)

	assert.Equal(t, "pool_exhausted", f.Type())
	assert.True(t, f.NonRetryable())
}

func TestReleasingGivesTheAddressBack(t *testing.T) {
	_, env := pools(t)
	a := call[Address](t, env, "address.reserve", provider.TestEnvelope(Reservation{Pool: "lab", Hostname: "web-04"}))

	released := call[Address](t, env, "address.release", provider.TestEnvelope(Release{Address: a.Address}))
	again := failure(t, env, "address.release", provider.TestEnvelope(Release{Address: a.Address}))

	assert.Equal(t, "released", released.State)
	assert.Equal(t, "address_not_found", again.Type())
	assert.Equal(t, 0, call[Pool](t, env, "pool.get", provider.TestEnvelope(PoolRef{Pool: "lab"})).Reserved)
}

func TestAnUnknownPoolIsNotFound(t *testing.T) {
	_, env := pools(t)

	f := failure(t, env, "pool.get", provider.TestEnvelope(PoolRef{Pool: "nowhere"}))

	assert.Equal(t, "pool_not_found", f.Type())
	assert.True(t, f.NonRetryable())
}

func TestAnInputWithoutAHostnameIsRefusedBeforeTheHandlerRuns(t *testing.T) {
	_, env := pools(t)

	f := failure(t, env, "address.reserve", provider.TestEnvelope(map[string]any{"pool": "lab"}))

	assert.Equal(t, provider.InvalidInput, f.Type())
	assert.True(t, f.NonRetryable())
}

func TestTheProviderFileNamesTheWorkerAndEveryAction(t *testing.T) {
	p := NewPools(DefaultPools()).Provider()

	assert.Equal(t, "provider-ip-pool", p.TaskQueue())
	var ids []string
	for _, a := range p.Actions() {
		ids = append(ids, a.ID())
	}
	assert.Equal(t, []string{"pool.get", "address.preview", "address.reserve", "address.release"}, ids)
	assert.Equal(t, "address.preview", p.Action("address.reserve").PlanID())
}
