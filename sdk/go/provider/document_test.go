package provider

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const token = "iaf_test-token"

// platform answers the provider API the way the platform does and records what it got.
type platform struct {
	*httptest.Server
	mu     sync.Mutex
	calls  []string
	bodies []map[string]string
}

func newPlatform(t *testing.T, answers map[string]struct {
	status int
	body   string
}) *platform {
	p := &platform{}
	p.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var body map[string]string
		_ = json.Unmarshal(b, &body)
		p.mu.Lock()
		p.calls = append(p.calls, r.Method+" "+r.URL.Path+" "+r.Header.Get("Authorization"))
		p.bodies = append(p.bodies, body)
		p.mu.Unlock()
		a, ok := answers[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(a.status)
		_, _ = w.Write([]byte(a.body))
	}))
	t.Cleanup(p.Close)
	return p
}

type answer = struct {
	status int
	body   string
}

type envMap map[string]string

func (m envMap) get(k string) string { return m[k] }

func TestWriteFileCreatesTheFoldersAndTheProviderFile(t *testing.T) {
	p := newInventory().builder().MustBuild()
	file := filepath.Join(t.TempDir(), "providers", "team-platform", "inventory.yaml")

	require.NoError(t, WriteFile(p, file))

	got, err := os.ReadFile(file)
	require.NoError(t, err)
	want, _ := p.YAML()
	assert.Equal(t, string(want), string(got))
}

func TestSubmitChecksTheFileThenPublishesItAsTheServiceAccount(t *testing.T) {
	p := newInventory().builder().MustBuild()
	srv := newPlatform(t, map[string]answer{
		"/api/v1/providers:validate": {200, `{"checks":[],"problems":[{"level":"warning","code":"alerts.channel","message":"Set alerts.channel"}]}`},
		"/api/v1/providers:publish":  {202, `{"id":"cr-42","status":"open","links":{}}`},
	})

	s, err := submitEnv(context.Background(), p, envMap{"IAF_PLATFORM_URL": srv.URL, "IAF_PLATFORM_TOKEN": token}.get)

	require.NoError(t, err)
	assert.Equal(t, &Submission{ChangeRequest: "cr-42", Status: "open"}, s)
	assert.Equal(t, []string{
		"POST /api/v1/providers:validate Bearer " + token,
		"POST /api/v1/providers:publish Bearer " + token,
	}, srv.calls)
	y, _ := p.YAML()
	for _, b := range srv.bodies {
		assert.Equal(t, string(y), b["yaml"])
	}
}

func TestACheckErrorStopsTheSubmitBeforeAnythingIsPublished(t *testing.T) {
	srv := newPlatform(t, map[string]answer{
		"/api/v1/providers:validate": {200, `{"checks":[],"problems":[{"level":"error","code":"metadata.owner","field":"metadata.owner","message":"owner is a team"}]}`},
		"/api/v1/providers:publish":  {202, `{"id":"cr-1"}`},
	})

	_, err := SubmitTo(context.Background(), newInventory().builder().MustBuild(), srv.URL, token)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "metadata.owner at metadata.owner: owner is a team")
	assert.Len(t, srv.calls, 1)
}

func TestARefusedPublishSaysWhyWithoutRepeatingTheToken(t *testing.T) {
	srv := newPlatform(t, map[string]answer{
		"/api/v1/providers:validate": {200, `{"checks":[],"problems":[]}`},
		"/api/v1/providers:publish":  {403, `{"error":"Only team-platform may publish provider inventory"}`},
	})

	_, err := SubmitTo(context.Background(), newInventory().builder().MustBuild(), srv.URL+"/", token)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "403")
	assert.Contains(t, err.Error(), "Only team-platform may publish provider inventory")
	assert.NotContains(t, err.Error(), token)
}

func TestSubmittingNeedsAServiceAccountTokenFromTheEnvironment(t *testing.T) {
	_, err := submitEnv(context.Background(), newInventory().builder().MustBuild(), envMap{"IAF_PLATFORM_URL": "http://127.0.0.1:1"}.get)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "IAF_PLATFORM_TOKEN")
}
