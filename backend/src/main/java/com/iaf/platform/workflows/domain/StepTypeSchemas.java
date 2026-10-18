package com.iaf.platform.workflows.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the designer's node panel and the natural-language builder need to know about each step
 * type: a title, a JSON Schema of the step's parameters ({@code with} for call steps, the step
 * block itself for the others) and a sentence on what it does.
 */
public final class StepTypeSchemas {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private StepTypeSchemas() {
    }

    public static String title(StepType t) {
        return switch (t) {
            case PROVIDER -> "Provider action";
            case HTTP -> "HTTP request";
            case ANSIBLE -> "Ansible playbook";
            case TERRAFORM -> "Terraform module";
            case IDP -> "IDP tile";
            case ACTIVITY -> "Activity";
            case RUN -> "Run workflow";
            case APPROVAL -> "Approval";
            case SWITCH -> "Switch";
            case FORK -> "Fork";
            case FOR -> "For each";
            case TRY -> "Try / catch";
            case WAIT -> "Wait";
            case SET -> "Set variables";
            case RAISE -> "Raise error";
            case USE -> "Use resource";
            case REGISTER -> "Register resource";
            case NOTIFY -> "Notify";
            case SCRIPT -> "Script";
        };
    }

    public static String description(StepType t) {
        return switch (t) {
            case PROVIDER -> "Call one action of a registered provider; the output is the action's response.";
            case HTTP -> "Send a raw HTTP request when no provider action fits; the credential is a vault: reference.";
            case ANSIBLE -> "Run a playbook from git, an upload or the playbook library in an execution environment; pin it to a commit.";
            case TERRAFORM -> "Plan, apply or destroy a pinned Terraform module with the given variables.";
            case IDP -> "Run a tile of the developer portal with an input (mock until the IDP tiles API is wired).";
            case ACTIVITY -> "Run an activity a team-owned worker registers on its task queue.";
            case RUN -> "Run a published workflow as a child job; version is a range like ^1.";
            case APPROVAL -> "Pause until someone of the group signs off, with a timeout and an optional escalation.";
            case SWITCH -> "Branch on expressions: each case jumps to a later step (then) or runs a nested branch (do).";
            case FORK -> "Run branches in parallel and continue when all of them are done.";
            case FOR -> "Run the body once per item of a list; the item is reachable as $<each>.";
            case TRY -> "Run steps and, when one fails, the catch steps with the error as $<as>.";
            case WAIT -> "Pause for a duration (PT5M) or until an expression is true.";
            case SET -> "Set variables reachable later as $vars.<name>.";
            case RAISE -> "Fail the job with a named error.";
            case USE -> "Resolve a registry resource by id, optionally waiting until it is ready and checking access.";
            case REGISTER -> "Record what the job created in the registry, with what it was built from.";
            case NOTIFY -> "Send a message to a channel.";
            case SCRIPT -> "Run a small sandboxed script (JavaScript, Python, Bash or Go) over an input and return its result.";
        };
    }

    public static ObjectNode schema(StepType t) {
        return switch (t) {
            case PROVIDER -> object("provider", "action")
                    .props(prop("provider", "string", "The provider name from the registry"),
                            prop("action", "string", "The action id, like thing.get"),
                            prop("input", "object", "The action's input fields; values may be expressions"))
                    .build();
            case HTTP -> object("url")
                    .props(enumProp("method", "GET", "POST", "PUT", "PATCH", "DELETE"),
                            prop("url", "string", "Absolute URL; may contain expressions"),
                            prop("headers", "object", null),
                            any("body", "Request body"),
                            prop("credential", "string", "A vault:<path> reference"))
                    .build();
            case ANSIBLE -> object("playbook")
                    .props(playbook(),
                            prop("inventory", "string", "dynamic or an inventory name"),
                            prop("limit", "string", "Hosts to limit the run to"),
                            prop("extraVars", "object", "Playbook variables"),
                            prop("credential", "string", "A vault:<path> reference to the SSH key"),
                            prop("executionEnvironment", "string", "The execution environment image"),
                            prop("checkModeOnTest", "boolean", "Run in check mode on the test environment"))
                    .build();
            case TERRAFORM -> object("module", "action")
                    .props(obj("module", object("source").props(prop("source", "string", "Module source"), prop("version", "string", "Pinned version")).build()),
                            enumProp("action", "plan", "apply", "destroy"),
                            prop("vars", "object", "Module variables"),
                            prop("credential", "string", "A vault:<path> reference"))
                    .build();
            case IDP -> object("tile")
                    .props(prop("tile", "string", "The tile id"), prop("input", "object", "The tile's input"))
                    .build();
            case ACTIVITY -> object("taskQueue", "activity")
                    .props(prop("taskQueue", "string", "The team's task queue"), prop("activity", "string", "The activity name"), prop("input", "object", null))
                    .build();
            case RUN -> object("workflow")
                    .props(prop("workflow", "string", "A published workflow name"),
                            prop("version", "string", "A range like ^1, ~1.2 or 1.2.0"),
                            prop("input", "object", "The child job's input"))
                    .build();
            case APPROVAL -> object("group")
                    .props(prop("group", "string", "The approver group"),
                            prop("message", "string", "What the approver decides on; may contain expressions"),
                            prop("timeout", "string", "A duration like 4h"),
                            obj("escalation", object().props(prop("after", "string", "A duration"), prop("to", "string", "Group or person")).build()))
                    .build();
            case SWITCH -> object("cases")
                    .props(arr("cases", object()
                            .props(prop("when", "string", "An expression; absent on the default case"),
                                    prop("then", "string", "A later step id or end"),
                                    prop("default", "boolean", null),
                                    arr("do", F.objectNode().put("description", "Nested steps, run instead of a jump")))
                            .build()))
                    .build();
            case FORK -> object("branches")
                    .props(arr("branches", F.objectNode().put("type", "array").put("description", "The steps of one branch")))
                    .build();
            case FOR -> object("each", "in")
                    .props(prop("each", "string", "The loop variable, like item"),
                            prop("in", "string", "An expression yielding a list"),
                            arr("do", F.objectNode().put("description", "The loop body")))
                    .build();
            case TRY -> object("do")
                    .props(arr("do", F.objectNode()),
                            obj("catch", object().props(prop("as", "string", "The alias of the error, default error"), arr("do", F.objectNode())).build()))
                    .build();
            case WAIT -> object()
                    .props(prop("duration", "string", "An ISO duration like PT5M, or 30m"), prop("until", "string", "An expression"))
                    .build();
            case SET -> {
                ObjectNode s = F.objectNode();
                s.put("type", "object");
                s.put("description", "Each key is a variable; each value an expression or literal");
                s.set("additionalProperties", F.objectNode().put("type", "string"));
                s.put("minProperties", 1);
                yield s;
            }
            case RAISE -> object("error")
                    .props(prop("error", "string", "The error name, like UpgradeFailed"), prop("message", "string", null))
                    .build();
            case USE -> object("type")
                    .props(prop("type", "string", "A resource type"),
                            prop("id", "string", "The resource id; may be an expression"),
                            prop("selector", "object", "Attributes to match when there is no id"),
                            enumProp("waitUntil", "ready"),
                            prop("checkAccess", "boolean", "Ask policy whether the requester may use it"))
                    .build();
            case REGISTER -> object("type")
                    .props(prop("type", "string", "A resource type"),
                            prop("id", "string", "The id; the registry mints one when absent"),
                            prop("state", "string", "A state of the type, default ready"),
                            prop("attributes", "object", "Values of the type's schema"),
                            arr("builtFrom", F.objectNode().put("type", "string")))
                    .build();
            case NOTIFY -> object("channel", "message")
                    .props(prop("channel", "string", "A channel like #ops"), prop("message", "string", "May contain expressions"))
                    .build();
            case SCRIPT -> object("code")
                    .props(enumProp("language", "javascript", "python", "bash", "go"),
                            prop("code", "string", "javascript and python: the function body, input in scope; bash and go: a program reading JSON on stdin"),
                            prop("input", "object", "What the script sees as input"))
                    .build();
        };
    }

    // ---------------------------------------------------------------- tiny schema builder

    private static Builder object(String... required) {
        return new Builder(required);
    }

    private static final class Builder {
        private final ObjectNode node = F.objectNode();
        private final ObjectNode props = F.objectNode();

        Builder(String... required) {
            node.put("type", "object");
            if (required.length > 0) {
                ArrayNode r = node.putArray("required");
                for (String s : required) {
                    r.add(s);
                }
            }
        }

        Builder props(ObjectNode... entries) {
            for (ObjectNode e : entries) {
                props.setAll(e);
            }
            return this;
        }

        ObjectNode build() {
            node.set("properties", props);
            return node;
        }
    }

    private static ObjectNode prop(String name, String type, String description) {
        ObjectNode p = F.objectNode();
        p.put("type", type);
        if (description != null) {
            p.put("description", description);
        }
        return wrap(name, p);
    }

    private static ObjectNode any(String name, String description) {
        ObjectNode p = F.objectNode();
        if (description != null) {
            p.put("description", description);
        }
        return name == null ? p : wrap(name, p);
    }

    private static ObjectNode enumProp(String name, String... values) {
        ObjectNode p = F.objectNode();
        p.put("type", "string");
        ArrayNode e = p.putArray("enum");
        for (String v : values) {
            e.add(v);
        }
        return wrap(name, p);
    }

    private static ObjectNode obj(String name, ObjectNode schema) {
        return wrap(name, schema);
    }

    private static ObjectNode arr(String name, JsonNode items) {
        ObjectNode p = F.objectNode();
        p.put("type", "array");
        p.set("items", items);
        return wrap(name, p);
    }

    private static ObjectNode playbook() {
        ObjectNode git = object("repo", "ref", "path").props(prop("repo", "string", null), prop("ref", "string", "Tag or branch"), prop("path", "string", "Playbook path in the repo"),
                prop("credential", "string", "A vault:<path> reference for a private repository")).build();
        ObjectNode upload = object("file", "sha256").props(prop("file", "string", null), prop("sha256", "string", null),
                prop("path", "string", "The playbook to run inside an uploaded archive")).build();
        ObjectNode asObject = object().props(obj("git", git), obj("upload", upload), prop("library", "string", "<provider>/<playbook>"), prop("commit", "string", "The commit every run uses")).build();
        ObjectNode asText = F.objectNode();
        asText.put("type", "string");
        asText.put("description", "A library playbook: <provider>/<playbook>");
        ObjectNode p = F.objectNode();
        p.putArray("oneOf").add(asText).add(asObject);
        p.put("description", "Where the playbook comes from: git, upload or library, pinned to a commit");
        return wrap("playbook", p);
    }

    private static ObjectNode wrap(String name, ObjectNode schema) {
        ObjectNode o = F.objectNode();
        o.set(name, schema);
        return o;
    }
}
