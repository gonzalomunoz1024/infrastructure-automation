package provider

import (
	"encoding/json"
	"reflect"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type disk struct {
	SizeGB int  `json:"sizeGb"`
	SSD    bool `json:"ssd"`
}

type audit struct {
	By string `json:"by"`
}

type request struct {
	audit
	Site   string            `json:"site" description:"Where it goes"`
	Note   *string           `json:"note"`
	Count  int               `json:"count,omitempty"`
	Size   string            `json:"size" enum:"small,large"`
	Disks  []disk            `json:"disks"`
	Labels map[string]string `json:"labels"`
	At     time.Time         `json:"at"`
	Ratio  float64           `json:"ratio"`
	Extra  any               `json:"extra"`
	Raw    json.RawMessage   `json:"raw"`
	Hidden string            `json:"-"`
	Plain  string
	secret string
}

type loop struct {
	Name string `json:"name"`
	Next *loop  `json:"next"`
}

func schemaOf[T any](t *testing.T) map[string]any {
	t.Helper()
	raw, err := SchemaOf(reflect.TypeFor[T]())
	require.NoError(t, err)
	return decode(t, raw)
}

func TestAStructIsAnObjectWhoseFieldsAreRequiredUnlessOptional(t *testing.T) {
	s := schemaOf[request](t)

	assert.Equal(t, "object", s["type"])
	assert.Equal(t, []any{"by", "site", "size", "disks", "labels", "at", "ratio", "extra", "raw", "Plain"}, s["required"])
}

func TestFieldsMapToTheirJSONTypes(t *testing.T) {
	p := schemaOf[request](t)["properties"].(map[string]any)

	assert.Equal(t, map[string]any{"type": "string", "description": "Where it goes"}, p["site"])
	assert.Equal(t, map[string]any{"type": "string"}, p["note"])
	assert.Equal(t, map[string]any{"type": "integer"}, p["count"])
	assert.Equal(t, map[string]any{"type": "string", "enum": []any{"small", "large"}}, p["size"])
	assert.Equal(t, map[string]any{"type": "array", "items": map[string]any{
		"type":       "object",
		"properties": map[string]any{"sizeGb": map[string]any{"type": "integer"}, "ssd": map[string]any{"type": "boolean"}},
		"required":   []any{"sizeGb", "ssd"},
	}}, p["disks"])
	assert.Equal(t, map[string]any{"type": "object", "additionalProperties": map[string]any{"type": "string"}}, p["labels"])
	assert.Equal(t, map[string]any{"type": "string", "format": "date-time"}, p["at"])
	assert.Equal(t, map[string]any{"type": "number"}, p["ratio"])
	assert.Equal(t, map[string]any{}, p["extra"])
	assert.Equal(t, map[string]any{}, p["raw"])
	assert.Equal(t, map[string]any{"type": "string"}, p["by"], "an embedded struct's fields are the struct's own")
	assert.NotContains(t, p, "Hidden")
	assert.NotContains(t, p, "secret")
}

func TestATypeWithoutADerivableSchemaNeedsAnExplicitOne(t *testing.T) {
	_, err := SchemaOf(reflect.TypeFor[chan int]())
	assert.ErrorContains(t, err, "give the schema explicitly")

	_, err = SchemaOf(reflect.TypeFor[loop]())
	assert.ErrorContains(t, err, "refers to itself")

	_, err = SchemaOf(reflect.TypeFor[map[int]string]())
	assert.ErrorContains(t, err, "map keys are strings")
}

func TestProblemsSayWhatDoesNotConform(t *testing.T) {
	raw, err := SchemaOf(reflect.TypeFor[disk]())
	require.NoError(t, err)
	check, err := compile(raw)
	require.NoError(t, err)

	assert.Empty(t, problems(check, json.RawMessage(`{"sizeGb":10,"ssd":true}`)))
	ps := problems(check, json.RawMessage(`{"sizeGb":"ten"}`))
	require.Len(t, ps, 2)
	assert.Contains(t, ps[0]+ps[1], "ssd")
	assert.Contains(t, ps[0]+ps[1], "/sizeGb")
}
