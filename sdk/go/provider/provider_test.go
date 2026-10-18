package provider

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gopkg.in/yaml.v3"
)

func decode(t *testing.T, b []byte) map[string]any {
	t.Helper()
	var m map[string]any
	require.NoError(t, json.Unmarshal(b, &m))
	return m
}

func path(v any, keys ...any) any {
	for _, k := range keys {
		switch key := k.(type) {
		case string:
			m, _ := v.(map[string]any)
			v = m[key]
		case int:
			l, _ := v.([]any)
			if key >= len(l) {
				return nil
			}
			v = l[key]
		}
	}
	return v
}

func TestAProviderDescribesItselfAsTheProviderFileThePlatformReads(t *testing.T) {
	doc := decode(t, newInventory().builder().MustBuild().Document())

	assert.Equal(t, "iaf.platform/v1", doc["apiVersion"])
	assert.Equal(t, "Provider", doc["kind"])
	assert.Equal(t, "inventory", path(doc, "metadata", "name"))
	assert.Equal(t, "team-platform", path(doc, "metadata", "owner"))
	assert.Equal(t, "1.2.0", path(doc, "metadata", "version"))
	assert.Equal(t, "sdk", path(doc, "spec", "type"))
	assert.Equal(t, map[string]any{"taskQueue": "provider-inventory", "heartbeatTimeout": "30s"}, path(doc, "spec", "worker"))
	assert.Len(t, path(doc, "spec", "actions"), 3)
	assert.Equal(t, "item.create", path(doc, "spec", "actions", 2, "id"))
	assert.Equal(t, "create", path(doc, "spec", "actions", 2, "effect"))
	assert.Equal(t, "item.preview", path(doc, "spec", "actions", 2, "plan"))
	assert.Equal(t, []any{"name"}, path(doc, "spec", "actions", 2, "input", "required"))
	assert.Equal(t, "The item id", path(doc, "spec", "actions", 0, "input", "properties", "id", "description"))
	assert.Equal(t, []any{"active", "retired"}, path(doc, "spec", "actions", 0, "output", "properties", "state", "enum"))
	assert.Equal(t, "{{ $input.id }}", path(doc, "spec", "actions", 0, "sample", "id"))
	assert.Equal(t, map[string]any{"class": "not_found", "retry": false, "description": "No item has that id"}, path(doc, "spec", "errors", 0))
	assert.Equal(t, true, path(doc, "spec", "errors", 1, "retry"))
	assert.Equal(t, "#platform-alerts", path(doc, "spec", "alerts", "channel"))
}

func TestTheYAMLReadsBackAsTheSameDocumentInTheSameOrder(t *testing.T) {
	p := newInventory().builder().MustBuild()

	y, err := p.YAML()
	require.NoError(t, err)

	text := string(y)
	assert.True(t, strings.HasPrefix(text, "apiVersion: iaf.platform/v1\nkind: Provider\nmetadata:\n  name: inventory\n"), text)
	assert.Less(t, strings.Index(text, "type: object"), strings.Index(text, "properties:"), "a schema reads type first")
	assert.Contains(t, text, `channel: '#platform-alerts'`)
	var back any
	require.NoError(t, yaml.Unmarshal(y, &back))
	viaJSON, err := json.Marshal(back)
	require.NoError(t, err)
	assert.JSONEq(t, string(p.Document()), string(viaJSON))
}

func TestTheTaskQueueDefaultsToProviderDashNameAndCanBeNamed(t *testing.T) {
	assert.Equal(t, "provider-inventory", newInventory().builder().MustBuild().TaskQueue())
	assert.Equal(t, "inventory-eu", newInventory().builder().TaskQueue("inventory-eu").MustBuild().TaskQueue())
}

func TestWithoutAHeartbeatTimeoutTheWorkerBlockOnlyNamesTheQueue(t *testing.T) {
	doc := decode(t, newInventory().builder().HeartbeatTimeout(0).MustBuild().Document())

	assert.Equal(t, map[string]any{"taskQueue": "provider-inventory"}, path(doc, "spec", "worker"))
}

func TestDurationsAreWrittenTheWayProviderFilesSpellThem(t *testing.T) {
	assert.Equal(t, "30s", formatDuration(30*time.Second))
	assert.Equal(t, "2m", formatDuration(2*time.Minute))
	assert.Equal(t, "1h", formatDuration(time.Hour))
	assert.Equal(t, "1500ms", formatDuration(1500*time.Millisecond))
}

func TestEveryProblemIsListedAtOnce(t *testing.T) {
	echo := func(_ context.Context, _ *Call, in map[string]any) (map[string]any, error) { return in, nil }

	_, err := New("Bad Name").
		Version("1.0").
		TaskQueue("no spaces allowed").
		HeartbeatTimeout(200*time.Millisecond).
		Error("not a class", false, "").
		Action(NewAction("item.get", echo).Title("Get")).
		Action(NewAction("item.get", echo).Effect(Read)).
		Action(NewAction("itemcreate", echo).Effect(Create).Plan("item.preview")).
		Build()

	require.Error(t, err)
	for _, want := range []string{
		"The name is lowercase letters",
		"Owner is the team",
		"Version is semantic",
		"The task queue is letters",
		"HeartbeatTimeout is at least one second",
		"Action item.get says what it does",
		"Action id item.get is used twice",
		"Action ids read as <noun>.<verb>",
		"The plan of itemcreate names another action",
		"An error class is a word",
	} {
		assert.Contains(t, err.Error(), want)
	}
}

func TestAProviderOffersAtLeastOneAction(t *testing.T) {
	_, err := New("empty").Owner("team-platform").Version("1.0.0").Build()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "A provider offers at least one action")
}

func TestInputsAndOutputsAreJSONObjects(t *testing.T) {
	_, err := New("strings").Owner("team-platform").Version("1.0.0").
		Action(NewAction("text.echo", func(_ context.Context, _ *Call, in string) (string, error) { return in, nil }).Effect(Read)).
		Build()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "The input of text.echo is a JSON object")
	assert.Contains(t, err.Error(), "The output of text.echo is a JSON object")
}

func TestAnExplicitSchemaReplacesTheDerivedOne(t *testing.T) {
	schema := `{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}`
	p := New("search").Owner("team-platform").Version("1.0.0").
		Action(NewAction("item.search", func(_ context.Context, _ *Call, in map[string]any) (map[string]any, error) { return in, nil }).
			Effect(Read).
			Input(schema).
			Output(map[string]any{"type": "object"})).
		MustBuild()

	assert.JSONEq(t, schema, string(p.Action("item.search").InputSchema()))
	assert.JSONEq(t, `{"type":"object"}`, string(p.Action("item.search").OutputSchema()))
	assert.Nil(t, p.Action("item.nope"))
}
