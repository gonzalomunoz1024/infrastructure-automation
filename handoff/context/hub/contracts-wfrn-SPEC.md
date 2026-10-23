# wfrn: the resource name

Every console resource has one immutable name, minted by the state service at request time, in
the same six-part shape as an ARN. This file is the specification; `fixtures.json` beside it is
the one set of test cases every implementation runs (the Java library on `console-libs`, the
TypeScript package on `console-web`, and the platform's copy once P-05 ships), so the twins cannot
drift apart. A change to this file is a change to the fixtures, in the same commit.

```
wfrn:<partition>:<service>:<region>:<account>:<type>/<id>
wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya
```

## Segments

| # | Segment | Rule | Example |
| --- | --- | --- | --- |
| 0 | prefix | the literal `wfrn` | `wfrn` |
| 1 | `partition` | `[a-z0-9-]{1,32}`; fixed per installation (a sovereign or air-gapped install gets its own) | `wf` |
| 2 | `service` | `[a-z0-9-]{2,32}`; the ResourceType's `metadata.service` (P-06), the console's service grouping | `compute` |
| 3 | `region` | `[a-z0-9-]{2,32}`, or empty for account-level things that live in no region (IAM roles, access keys) | `global`, `` |
| 4 | `account` | exactly 12 ASCII digits, or empty for organization-level things (the organization, accounts themselves, guardrails) | `123456789012`, `` |
| 5 | `type` | `[a-z0-9-]{1,64}`; the ResourceType's `metadata.name` | `vm` |
| 6 | `id` | the part after the first `/`: one or more path segments of `[a-z0-9-]{1,128}` joined by `/`; the first segment of a platform-minted name is `<type>-<ulid>` | `vm-01jbx7q2m4k9d3c8f5r6t1w0ya`, `bucket-01jbx7q2m4k9d3c8f5r6t1w0ya/logs/2026` |

- The name is split on `:` exactly five times (segments 1 to 5), then the rest is split on its
  first `/` into `type` and `id`. The `id` may itself contain `/` for nested resources; it never
  contains `:`.
- Everything is lowercase. Parsing is strict: an uppercase letter anywhere is invalid
  (`wfrn.case`), never folded. The one exception is the error message, which may quote the input.
- The whole name is at most 1,024 characters (`wfrn.length`).
- A ULID in an id is the 26-character Crockford base32 form in lowercase:
  `[0-7][0-9a-hjkmnp-tv-z]{25}` (the alphabet has no `i`, `l`, `o`, `u`). The state service mints
  one per resource; it is time ordered, so `_id` order is creation order.
- The provider's own id (`vm-1042` in vSphere) is never part of the name. It is kept beside it as
  `providerId`, so a provider that re-creates an object, or two providers that use the same id,
  never break a name.
- Names are never reused, even after deletion. A deleted resource's name resolves to its tombstone
  (410 Gone) for 90 days.
- The wildcards `*` and `?` are allowed only in patterns (IAM policies, search), never in a stored
  name (`wfrn.wildcard`).

## Canonical form

`format(parts)` is the only way to build a name, and `format(parse(s)) == s` for every valid `s`.
There is no normalisation: a name is either canonical or invalid.

## Patterns

A pattern is a name in which `*` matches any run of zero or more characters and `?` matches
exactly one character, over the whole string, across `:` and `/` (AWS's rule, so a policy written
as `wfrn:wf:compute:*:123456789012:vm/*` reads as it would in IAM, and `wfrn:wf:*` matches every
name in the partition). An empty segment in a pattern matches only an empty segment. Matching is
literal apart from the two wildcards: no regular-expression characters, no case folding.

A pattern is judged with the same rules as a name (length, case, prefix, the segment alphabet and
maximum lengths), with three relaxations:

- a segment that contains a wildcard is checked only for its alphabet (`[a-z0-9*?-]`) and its
  maximum length, so a lone `*` is a valid region, account or type; a segment without a wildcard is
  checked exactly as in a name;
- a pattern may stop early at a segment that ends in `*` (`wfrn:wf:*`, `wfrn:wf:compute:*`): the
  segments before it are checked, the rest is anything;
- the `id` part may be absent when the type segment ends in `*`
  (`wfrn:wf:compute:global:123456789012:*`).

`matches(pattern, name)` throws for an invalid pattern (with the codes below) and answers `false`,
never throws, for a name that is not a valid wfrn.

## Minted ids

`mintId(type, ulid)` is `<type>-<ulid>`. The ULID is the state service's; the function only
formats. `parseMintedId("vm-01jbx7q2m4k9d3c8f5r6t1w0ya")` gives `{ type: "vm", ulid: "01jbx7q2m4k9d3c8f5r6t1w0ya" }`
and fails on an id whose last 26 characters are not a ULID or whose prefix is not the type.

## Error codes

Every implementation reports the first failing rule with one of these codes, so a message shown
to a person says the same thing whichever twin judged it.

| Code | When |
| --- | --- |
| `wfrn.length` | longer than 1,024 characters |
| `wfrn.case` | contains an uppercase letter |
| `wfrn.wildcard` | a stored name contains `*` or `?` |
| `wfrn.prefix` | does not start with `wfrn:` |
| `wfrn.segments` | fewer than six `:`-separated parts, or the last part has no `/` |
| `wfrn.partition` | segment 1 breaks its rule |
| `wfrn.service` | segment 2 breaks its rule |
| `wfrn.region` | segment 3 breaks its rule |
| `wfrn.account` | segment 4 breaks its rule |
| `wfrn.type` | segment 5 breaks its rule |
| `wfrn.id` | the id part is empty, or a path segment of it breaks its rule (empty, too long, bad character) |

The order of checks is the order of this table: length, case and wildcard first (over the whole
string), then the prefix, the segment count, and each segment left to right.

## The fixtures

`fixtures.json` has five lists:

- `valid`: `{ name, input, parts }`: `parse(input)` succeeds with exactly `parts`, and
  `format(parts)` gives back `input`.
- `invalid`: `{ name, input, code }`: `parse(input)` fails with that code.
- `format`: `{ name, parts, expected }`: `format(parts)` gives `expected`; `parts` with an invalid
  value have `"error": "<code>"` instead, and `format` must refuse them with that code.
- `patterns`: `{ name, pattern, matches: [], misses: [] }`: `matches(pattern, s)` is true for each
  of `matches` and false for each of `misses`; `invalidPatterns` lists patterns the implementation
  must refuse, with the code.
- `mintedIds`: `{ name, type, ulid, id, parsed }`: `mintId(type, ulid) == id` and
  `parseMintedId(id) == parsed`; entries with `"error"` instead of `parsed` must be refused.

A test runs every entry of every list and reports each failing entry by its `name`.
