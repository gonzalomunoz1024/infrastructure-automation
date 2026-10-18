package provider

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// Document is the provider file as JSON: kind Provider, type sdk, the worker and every action
// with its schemas.
func (p *Provider) Document() json.RawMessage {
	md := newObject().set("name", p.name).set("title", p.title).set("owner", p.owner).set("version", p.version)
	if p.description != "" {
		md.set("description", p.description)
	}
	w := newObject().set("taskQueue", p.taskQueue)
	if p.heartbeatTimeout > 0 {
		w.set("heartbeatTimeout", formatDuration(p.heartbeatTimeout))
	}
	spec := newObject().set("type", Type).set("worker", w)
	actions := make([]any, 0, len(p.actions))
	for _, a := range p.actions {
		title := a.title
		if title == "" {
			title = a.id
		}
		o := newObject().set("id", a.id).set("title", title).set("effect", string(a.effect))
		if a.plan != "" {
			o.set("plan", a.plan)
		}
		if a.description != "" {
			o.set("description", a.description)
		}
		o.set("input", a.input).set("output", a.output)
		if a.sample != nil {
			o.set("sample", a.sample)
		}
		actions = append(actions, o)
	}
	spec.set("actions", actions)
	if len(p.errors) > 0 {
		errs := make([]any, 0, len(p.errors))
		for _, e := range p.errors {
			o := newObject().set("class", e.Name).set("retry", e.Retryable)
			if e.Description != "" {
				o.set("description", e.Description)
			}
			errs = append(errs, o)
		}
		spec.set("errors", errs)
	}
	if p.alerts != "" {
		spec.set("alerts", newObject().set("channel", p.alerts))
	}
	root := newObject().set("apiVersion", APIVersion).set("kind", Kind).set("metadata", md).set("spec", spec)
	b, err := json.Marshal(root)
	if err != nil {
		panic(fmt.Sprintf("the provider file of %s: %v", p.name, err)) // only a sample that cannot be JSON gets here
	}
	return b
}

// YAML is the provider file the platform registers: providers/<owner>/<name>.yaml.
func (p *Provider) YAML() ([]byte, error) {
	var n yaml.Node
	if err := yaml.Unmarshal(p.Document(), &n); err != nil {
		return nil, err
	}
	blockStyle(&n)
	var buf bytes.Buffer
	enc := yaml.NewEncoder(&buf)
	enc.SetIndent(2)
	if err := enc.Encode(&n); err != nil {
		return nil, err
	}
	if err := enc.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// blockStyle drops the JSON styling: mappings and lists in block style, strings quoted only
// where YAML needs it.
func blockStyle(n *yaml.Node) {
	n.Style = 0
	for _, c := range n.Content {
		blockStyle(c)
	}
}

// formatDuration spells a duration the way provider files do: 30s, 2m, 1h, 1500ms.
func formatDuration(d time.Duration) string {
	ms := d.Milliseconds()
	switch {
	case ms%3_600_000 == 0:
		return fmt.Sprintf("%dh", ms/3_600_000)
	case ms%60_000 == 0:
		return fmt.Sprintf("%dm", ms/60_000)
	case ms%1000 == 0:
		return fmt.Sprintf("%ds", ms/1000)
	}
	return fmt.Sprintf("%dms", ms)
}

// WriteFile writes the provider file, creating the folders.
func WriteFile(p *Provider, path string) error {
	y, err := p.YAML()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, y, 0o644)
}

// Submission is what the platform answered: the change request opened for the provider version.
type Submission struct {
	ChangeRequest string `json:"id"`
	Status        string `json:"status"`
}

// Submit sends the provider file to the platform at IAF_PLATFORM_URL (default
// http://localhost:8080) as the service account whose token is in IAF_PLATFORM_TOKEN.
func Submit(ctx context.Context, p *Provider) (*Submission, error) {
	return submitEnv(ctx, p, os.Getenv)
}

func submitEnv(ctx context.Context, p *Provider, env func(string) string) (*Submission, error) {
	token := strings.TrimSpace(env("IAF_PLATFORM_TOKEN"))
	if token == "" {
		return nil, fmt.Errorf("set IAF_PLATFORM_TOKEN to a service-account token of the platform to submit the provider")
	}
	url := strings.TrimSpace(env("IAF_PLATFORM_URL"))
	if url == "" {
		url = "http://localhost:8080"
	}
	return SubmitTo(ctx, p, url, token)
}

// SubmitTo checks the provider file with the platform's provider checks, then publishes it: the
// platform opens a change request for the version. Nothing is published when a check reports an
// error. The token is sent, never printed.
func SubmitTo(ctx context.Context, p *Provider, platformURL, token string) (*Submission, error) {
	y, err := p.YAML()
	if err != nil {
		return nil, err
	}
	body, _ := json.Marshal(map[string]string{"yaml": string(y)})
	base := strings.TrimRight(platformURL, "/")
	var checked struct {
		Problems []struct {
			Level   string `json:"level"`
			Code    string `json:"code"`
			Field   string `json:"field"`
			Message string `json:"message"`
		} `json:"problems"`
	}
	if err := post(ctx, base, "/api/v1/providers:validate", body, token, http.StatusOK, &checked); err != nil {
		return nil, err
	}
	var errs []string
	for _, pr := range checked.Problems {
		if pr.Level == "error" {
			at := ""
			if pr.Field != "" {
				at = " at " + pr.Field
			}
			errs = append(errs, pr.Code+at+": "+pr.Message)
		}
	}
	if len(errs) > 0 {
		return nil, fmt.Errorf("the platform's checks reject provider %s:\n  - %s", p.name, strings.Join(errs, "\n  - "))
	}
	var s Submission
	if err := post(ctx, base, "/api/v1/providers:publish", body, token, http.StatusAccepted, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

var httpClient = &http.Client{Timeout: 30 * time.Second}

func post(ctx context.Context, base, path string, body []byte, token string, expected int, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := httpClient.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if res.StatusCode != expected {
		var e struct {
			Error string `json:"error"`
		}
		why := "unexpected answer"
		if json.Unmarshal(b, &e) == nil && e.Error != "" {
			why = e.Error
		}
		return fmt.Errorf("POST %s answered %d: %s", path, res.StatusCode, why)
	}
	if len(bytes.TrimSpace(b)) == 0 {
		return nil
	}
	return json.Unmarshal(b, out)
}
