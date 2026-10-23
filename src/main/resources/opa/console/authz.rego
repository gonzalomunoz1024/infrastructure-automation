# The console's authorization policy: the twin of the Java Evaluator, run by the OPA instances
# the console's services call (profile `opa`). The data is `data.console.accounts`, one entry per
# account, and `data.console.guardrails`, the organization's guardrails by id, as the identity
# service's bundle carries them. Input is the contract's AuthorizeRequest:
# {"principal": {"userId", "accountId", "groups", "service"}, "checks": [{"action", "resource", "context"}]}.
# `decisions` answers one decision per check, in order; `version` is the account's version.
package console.authz

import rego.v1

# The managed roles from the least to the most permissive: the order a suggested role is searched in.
managed_order := ["viewer", "auditor", "operator", "account-admin"]

default version := 0

# Now, in epoch seconds: a time-boxed role (an elevation, a break-glass grant) holds until its `until`.
now_s := time.now_ns() / 1000000000

version := data.console.accounts[input.principal.accountId].version

decisions := [d |
	some i
	c := input.checks[i]
	d := decide(input.principal, c)
]

is_service(p) if {
	p.service != ""
	not p.userId
}

is_service(p) if {
	p.service != ""
	p.userId == ""
}

decide(p, c) := {"allowed": false, "reason": "account unknown"} if {
	not data.console.accounts[p.accountId]
} else := {"allowed": true, "reason": "service principal"} if {
	is_service(p)
} else := {"allowed": false, "reason": sprintf("account %s", [data.console.accounts[p.accountId].status])} if {
	data.console.accounts[p.accountId].status != "active"
} else := {"allowed": false, "reason": "not a member of the account"} if {
	not data.console.accounts[p.accountId].users[p.userId]
} else := {"allowed": false, "reason": "user suspended"} if {
	data.console.accounts[p.accountId].users[p.userId].status != "active"
} else := rail_deny(s) if {
	s := first_rail(data.console.accounts[p.accountId], p, c, "deny")
} else := outside_rails if {
	has_rail_allows(data.console.accounts[p.accountId])
	not first_rail(data.console.accounts[p.accountId], p, c, "allow")
} else := explicit_deny(data.console.accounts[p.accountId], p, s) if {
	s := first_match(data.console.accounts[p.accountId], p, c, "deny")
} else := explicit_allow(data.console.accounts[p.accountId], p, s) if {
	s := first_permitted_allow(data.console.accounts[p.accountId], p, c)
} else := bounded_deny(data.console.accounts[p.accountId], s) if {
	s := first_match(data.console.accounts[p.accountId], p, c, "allow")
} else := default_deny(data.console.accounts[p.accountId], p, c)

explicit_deny(acct, p, s) := object.union(
	{
		"allowed": false,
		"reason": sprintf("denied by policy %s, statement %s", [acct.policies[s.policyId].name, s.sid]),
		"policyId": s.policyId,
		"statement": s.sid,
		"role": s.role,
		"scope": "policy",
	},
	elevated_field(acct, p, s.role),
)

explicit_allow(acct, p, s) := object.union(
	{
		"allowed": true,
		"reason": sprintf("allowed by role %s", [s.role]),
		"policyId": s.policyId,
		"statement": s.sid,
		"role": s.role,
		"scope": "policy",
	},
	elevated_field(acct, p, s.role),
)

# A role the user holds only for a while, not through a group, is marked on the decision.
elevated_field(acct, p, role) := {"elevated": true} if {
	not group_roles(acct, p)[role]
} else := {}

# A matching allow whose role's permission boundary refused it, and no other allow.
bounded_deny(acct, s) := {
	"allowed": false,
	"reason": sprintf("outside the permission boundary %s of role %s", [boundary_name(acct, s.role), s.role]),
	"policyId": acct.roles[s.role].boundary,
	"role": s.role,
	"requestable": false,
	"scope": "boundary",
}

boundary_name(acct, role) := acct.policies[acct.roles[role].boundary].name if {
	acct.policies[acct.roles[role].boundary]
} else := acct.roles[role].boundary

rail_deny(s) := {
	"allowed": false,
	"reason": sprintf("denied by guardrail %s, statement %s", [data.console.guardrails[s.guardrailId].name, s.sid]),
	"statement": s.sid,
	"requestable": false,
	"scope": "guardrail",
	"guardrailId": s.guardrailId,
}

outside_rails := {
	"allowed": false,
	"reason": "outside the organization's guardrails",
	"requestable": false,
	"scope": "guardrail",
}

# The statements of the guardrails that apply to the account: guardrails in the account's order, statements in order.
rails(acct) := [s |
	some gi, si
	gid := acct.guardrails[gi]
	st := data.console.guardrails[gid].statements[si]
	s := object.union(st, {"guardrailId": gid})
]

first_rail(acct, p, c, effect) := m[0] if {
	m := [s | s := rails(acct)[_]; s.effect == effect; matches(acct, p, c, s)]
	count(m) > 0
}

has_rail_allows(acct) if {
	some s in rails(acct)
	s.effect == "allow"
}

default_deny(acct, p, c) := {
	"allowed": false,
	"reason": sprintf("no policy allows %s on %s", [c.action, c.resource]),
	"requestable": true,
	"suggestedRole": suggestions(acct, p, c)[0],
} if {
	count(suggestions(acct, p, c)) > 0
} else := {
	"allowed": false,
	"reason": sprintf("no policy allows %s on %s", [c.action, c.resource]),
	"requestable": false,
}

# The managed roles that would allow the check, least permissive first.
suggestions(acct, p, c) := [r |
	some i
	r := managed_order[i]
	acct.roles[r]
	role_allows(acct, p, c, r)
]

role_allows(acct, p, c, r) if {
	count([s | s := statements_of_role(acct, r)[_]; s.effect == "deny"; matches(acct, p, c, s)]) == 0
	count([s | s := statements_of_role(acct, r)[_]; s.effect == "allow"; matches(acct, p, c, s)]) > 0
	boundary_permits(acct, p, c, r)
}

# A role without a boundary is not bounded; with one, no deny of the boundary may match and an
# allow must (a boundary policy that is missing allows nothing).
boundary_permits(acct, _, _, role) if {
	not acct.roles[role].boundary
}

boundary_permits(acct, p, c, role) if {
	b := acct.roles[role].boundary
	count([s | s := acct.policies[b].statements[_]; s.effect == "deny"; matches(acct, p, c, s)]) == 0
	count([s | s := acct.policies[b].statements[_]; s.effect == "allow"; matches(acct, p, c, s)]) > 0
}

first_permitted_allow(acct, p, c) := m[0] if {
	m := [s | s := statements(acct, p)[_]; s.effect == "allow"; matches(acct, p, c, s); boundary_permits(acct, p, c, s.role)]
	count(m) > 0
}

# The statements of the user's roles: roles sorted by name, policies in role order, statements in order.
# A role from a group is tried first, so `elevated` marks only what the time-boxed role alone allows.
statements(acct, p) := [s |
	some ri
	role := role_names(acct, p)[ri]
	s := statements_of_role(acct, role)[_]
]

role_names(acct, p) := array.concat(sort(group_roles(acct, p)), sort(grant_roles(acct, p) - group_roles(acct, p)))

group_roles(acct, p) := {r |
	some g in acct.users[p.userId].groups
	some r in acct.groups[g].roles
}

# The roles the user holds for a while that still hold now.
grant_roles(acct, p) := {g.role |
	some g in acct.users[p.userId].grants
	g.until > now_s
}

statements_of_role(acct, role) := [s |
	some pi, si
	pid := acct.roles[role].policies[pi]
	st := acct.policies[pid].statements[si]
	s := object.union(st, {"role": role, "policyId": pid})
]

first_match(acct, p, c, effect) := m[0] if {
	m := [s | s := statements(acct, p)[_]; s.effect == effect; matches(acct, p, c, s)]
	count(m) > 0
}

matches(acct, p, c, s) if {
	action_matches(s, c.action)
	resource_matches(s, c.resource)
	conditions_hold(acct, p, c, s)
}

action_matches(s, action) if {
	some pat in s.actions
	glob.match(pat, [], action)
	not action_excluded(s, action)
}

action_excluded(s, action) if {
	some pat in s.notActions
	glob.match(pat, [], action)
}

# A requested pattern's own wildcards are made literal, so a list over a pattern matches only a statement whose wildcard covers it.
sentinel(r) := replace(replace(r, "*", "~"), "?", "^")

resource_matches(s, resource) if {
	some pat in s.resources
	glob.match(pat, [], sentinel(resource))
}

conditions_hold(_, _, _, s) if {
	not s.conditions
}

conditions_hold(acct, p, c, s) if {
	every key, want in s.conditions {
		condition_holds(acct, p, c, key, want)
	}
}

condition_holds(acct, p, _, "requester.group", want) if {
	some g in acct.users[p.userId].groups
	value_matches(g, want)
}

condition_holds(_, p, c, key, want) if {
	key != "requester.group"
	got := derived(p, c, key)
	value_matches(got, want)
}

value_matches(got, want) if {
	is_array(want)
	some w in want
	w == got
}

value_matches(got, want) if {
	not is_array(want)
	want == got
}

derived(_, c, "region") := wfrn_segment(c.resource, 3)

derived(_, c, "account") := wfrn_segment(c.resource, 4)

derived(_, c, "service") := wfrn_segment(c.resource, 2)

derived(_, c, "type") := split(wfrn_segment(c.resource, 5), "/")[0]

derived(p, _, "requester.user") := p.userId

derived(_, c, "requester.email") := c.context.requester.email

derived(_, c, key) := c.context.tags[name] if {
	startswith(key, "tag.")
	name := substring(key, 4, -1)
}

derived(_, c, key) := c.context[name] if {
	startswith(key, "context.")
	name := substring(key, 8, -1)
}

wfrn_segment(r, i) := split(r, ":")[i]
