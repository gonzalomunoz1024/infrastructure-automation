package provider

import (
	"bytes"
	"encoding/json"
	"fmt"
	"reflect"
	"sort"
	"strings"
	"time"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"golang.org/x/text/language"
	"golang.org/x/text/message"
)

var printer = message.NewPrinter(language.English)

// object is a JSON object whose keys keep the order they were set in, so derived schemas and the
// provider file read top-down (type first, then properties, then required).
type object struct {
	keys []string
	vals map[string]any
}

func newObject() *object { return &object{vals: map[string]any{}} }

func (o *object) set(key string, v any) *object {
	if _, ok := o.vals[key]; !ok {
		o.keys = append(o.keys, key)
	}
	o.vals[key] = v
	return o
}

func (o *object) MarshalJSON() ([]byte, error) {
	var b bytes.Buffer
	b.WriteByte('{')
	for i, k := range o.keys {
		if i > 0 {
			b.WriteByte(',')
		}
		kb, _ := json.Marshal(k)
		b.Write(kb)
		b.WriteByte(':')
		vb, err := json.Marshal(o.vals[k])
		if err != nil {
			return nil, err
		}
		b.Write(vb)
	}
	b.WriteByte('}')
	return b.Bytes(), nil
}

var (
	timeType       = reflect.TypeFor[time.Time]()
	rawMessageType = reflect.TypeFor[json.RawMessage]()
	marshalerType  = reflect.TypeFor[json.Marshaler]()
)

// SchemaOf derives the JSON Schema (draft 2020-12) of a Go type the way encoding/json reads and
// writes it. A struct is an object whose exported fields are its properties, named by their json
// tags; a field is required unless it is a pointer or tagged omitempty or omitzero. The tags
// description:"..." and enum:"a,b,c" describe a field. Strings, numbers, booleans, slices, maps
// with string keys, time.Time and any (or json.RawMessage, any value) map to their JSON types.
func SchemaOf(t reflect.Type) (json.RawMessage, error) {
	s, err := derive(t, map[reflect.Type]bool{})
	if err != nil {
		return nil, err
	}
	return json.Marshal(s)
}

func derive(t reflect.Type, seen map[reflect.Type]bool) (*object, error) {
	if t == nil {
		return newObject(), nil
	}
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	switch {
	case t == timeType:
		return newObject().set("type", "string").set("format", "date-time"), nil
	case t == rawMessageType:
		return newObject(), nil
	}
	switch t.Kind() {
	case reflect.String:
		return newObject().set("type", "string"), nil
	case reflect.Bool:
		return newObject().set("type", "boolean"), nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return newObject().set("type", "integer"), nil
	case reflect.Float32, reflect.Float64:
		return newObject().set("type", "number"), nil
	case reflect.Interface:
		return newObject(), nil
	case reflect.Slice, reflect.Array:
		if t.Elem().Kind() == reflect.Uint8 {
			return newObject().set("type", "string").set("contentEncoding", "base64"), nil
		}
		items, err := derive(t.Elem(), seen)
		if err != nil {
			return nil, err
		}
		return newObject().set("type", "array").set("items", items), nil
	case reflect.Map:
		if t.Key().Kind() != reflect.String {
			return nil, fmt.Errorf("cannot derive a JSON Schema from %s: map keys are strings in JSON", t)
		}
		values, err := derive(t.Elem(), seen)
		if err != nil {
			return nil, err
		}
		return newObject().set("type", "object").set("additionalProperties", values), nil
	case reflect.Struct:
		if t.Implements(marshalerType) || reflect.PointerTo(t).Implements(marshalerType) {
			return nil, fmt.Errorf("cannot derive a JSON Schema from %s: it marshals itself; give the schema explicitly", t)
		}
		return structSchema(t, seen)
	}
	return nil, fmt.Errorf("cannot derive a JSON Schema from %s; give the schema explicitly", t)
}

func structSchema(t reflect.Type, seen map[reflect.Type]bool) (*object, error) {
	if seen[t] {
		return nil, fmt.Errorf("%s refers to itself; give its schema explicitly", t)
	}
	seen[t] = true
	defer delete(seen, t)
	properties := newObject()
	var required []string
	if err := fields(t, seen, properties, &required); err != nil {
		return nil, err
	}
	o := newObject().set("type", "object").set("properties", properties)
	if len(required) > 0 {
		o.set("required", required)
	}
	return o, nil
}

func fields(t reflect.Type, seen map[reflect.Type]bool, properties *object, required *[]string) error {
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		tag := f.Tag.Get("json")
		if tag == "-" {
			continue
		}
		name, opts, _ := strings.Cut(tag, ",")
		ft := f.Type
		if f.Anonymous && name == "" {
			et := ft
			if et.Kind() == reflect.Pointer {
				et = et.Elem()
			}
			if et.Kind() == reflect.Struct {
				if err := fields(et, seen, properties, required); err != nil {
					return err
				}
				continue
			}
		}
		if !f.IsExported() {
			continue
		}
		if name == "" {
			name = f.Name
		}
		s, err := derive(ft, seen)
		if err != nil {
			return fmt.Errorf("field %s: %w", f.Name, err)
		}
		if d := f.Tag.Get("description"); d != "" {
			s.set("description", d)
		}
		if e := f.Tag.Get("enum"); e != "" {
			s.set("enum", strings.Split(e, ","))
		}
		properties.set(name, s)
		optional := ft.Kind() == reflect.Pointer || strings.Contains(","+opts+",", ",omitempty,") || strings.Contains(","+opts+",", ",omitzero,")
		if !optional {
			*required = append(*required, name)
		}
	}
	return nil
}

// objectJSON is v as a JSON object (map keys sorted, struct fields in order); an error when it is
// not one.
func objectJSON(v any) (json.RawMessage, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	b = bytes.TrimSpace(b)
	if len(b) == 0 || b[0] != '{' {
		return nil, fmt.Errorf("not a JSON object")
	}
	return b, nil
}

func compile(schema json.RawMessage) (*jsonschema.Schema, error) {
	doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(schema))
	if err != nil {
		return nil, err
	}
	c := jsonschema.NewCompiler()
	c.DefaultDraft(jsonschema.Draft2020)
	if err := c.AddResource("schema.json", doc); err != nil {
		return nil, err
	}
	return c.Compile("schema.json")
}

// problems lists what is wrong with value against the schema, sorted; empty when it conforms.
func problems(schema *jsonschema.Schema, value json.RawMessage) []string {
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(value))
	if err != nil {
		return []string{"not JSON: " + err.Error()}
	}
	err = schema.Validate(inst)
	if err == nil {
		return nil
	}
	ve, ok := err.(*jsonschema.ValidationError)
	if !ok {
		return []string{err.Error()}
	}
	var out []string
	collect(ve, &out)
	sort.Strings(out)
	return out
}

func collect(ve *jsonschema.ValidationError, out *[]string) {
	if len(ve.Causes) == 0 {
		loc := "/" + strings.Join(ve.InstanceLocation, "/")
		*out = append(*out, loc+": "+ve.ErrorKind.LocalizedString(printer))
		return
	}
	for _, c := range ve.Causes {
		collect(c, out)
	}
}
