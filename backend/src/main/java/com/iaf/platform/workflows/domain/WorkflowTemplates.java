package com.iaf.platform.workflows.domain;

import java.util.List;

/**
 * Generic starters for the designer's "New workflow" dialog. Each is a valid file; the
 * {@code notes} of each step say what to fill in. No product names: the placeholders are
 * {@code your-provider}, {@code your.action}, {@code your-team} and friends.
 */
public final class WorkflowTemplates {

    public record Template(String id, String title, String description, String yaml) {
    }

    private WorkflowTemplates() {
    }

    public static List<Template> all() {
        return List.of(
                new Template("call-and-notify", "Call a provider and notify",
                        "One provider action, then a message to a channel; a catch branch tells the channel when it fails.", CALL_AND_NOTIFY),
                new Template("approval-then-change", "Approval, then a change",
                        "A human signs off, the change runs, a read action verifies it.", APPROVAL_THEN_CHANGE),
                new Template("create-and-register", "Create and register a resource",
                        "Create something through a provider, wait until it is ready and record it in the registry.", CREATE_AND_REGISTER),
                new Template("composite", "Composite: run other workflows",
                        "Run published workflows as child jobs, in parallel, and combine their outputs.", COMPOSITE),
                new Template("bulk-change", "Bulk-friendly change with verification",
                        "A change on one registered resource with a verify step, tuned for batch runs in waves.", BULK_CHANGE),
                new Template("alert-remediation", "Alert-triggered remediation",
                        "Started by an alert: inspect, stop when already healthy, remediate, verify, notify.", ALERT_REMEDIATION));
    }

    static final String CALL_AND_NOTIFY = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: call-and-notify
              title: Call a provider and notify
              noun: thing
              verb: refresh
              owner: your-team
              folder: Starters
              tags: [starter]
              version: 0.1.0
              description: Call one provider action and tell a channel what happened.
            api:
              method: POST
              path: /api/v1/things:refresh
              input:
                type: object
                required: [id]
                properties:
                  id: { type: string, title: Id }
                  reason: { type: string, title: Reason }
              response: { status: 202 }
              preview: true
            mcp:
              expose: true
              description: Call a provider action for one thing and notify the channel.
            policy:
              rbac: [ops]
            rollout:
              strategy: canary
              stages: [10, 50, 100]
            workflow:
              do:
                - callProvider:
                    name: Call the provider
                    call: provider
                    with: { provider: your-provider, action: your.action, input: { id: "{{ $input.id }}" } }
                    output: { as: result }
                    notes: "Fill in: the provider and action from the registry, and the action's input fields."
                - notifyDone:
                    name: Tell the channel
                    notify: { channel: "#your-channel", message: "{{ $input.id }} refreshed by {{ $job.requester }}" }
                    notes: "Fill in: the channel to notify."
              catch:
                do:
                  - notifyFailed:
                      name: Tell the channel it failed
                      notify: { channel: "#your-channel", message: "{{ $job.id }} failed: {{ $error.message }}" }
              outputs:
                result: "{{ $result }}"
            """;

    static final String APPROVAL_THEN_CHANGE = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: approval-then-change
              title: Approval, then a change
              noun: thing
              verb: update
              owner: your-team
              folder: Starters
              tags: [starter, approval]
              version: 0.1.0
              description: A human approves, the change runs, a read verifies it.
            api:
              method: PUT
              path: /api/v1/things/{id}
              input:
                type: object
                required: [id, reason]
                properties:
                  id: { type: string, title: Id }
                  reason: { type: string, title: Reason, minLength: 10 }
              response: { status: 202 }
              preview: true
            mcp:
              expose: true
              description: Change one thing after an approval.
            policy:
              approval: { required: true, group: your-approvers, timeout: 4h }
              rbac: [ops]
              change: { required: true }
            rollout:
              strategy: canary
              stages: [10, 50, 100]
            workflow:
              do:
                - inspect:
                    name: Read the current state
                    call: provider
                    with: { provider: your-provider, action: your.get, input: { id: "{{ $input.id }}" } }
                    output: { as: current }
                    notes: "Fill in: the read action that shows the approver what will change."
                - approval:
                    name: Approve the change
                    approval:
                      group: your-approvers
                      message: "Change {{ $input.id }} ({{ $current.state }})? {{ $input.reason }}"
                      timeout: 4h
                    notes: "Fill in: the approver group; keep policy.approval.group in sync."
                - change:
                    name: Apply the change
                    call: provider
                    with: { provider: your-provider, action: your.change, input: { id: "{{ $input.id }}" } }
                    retry: { maxAttempts: 1 }
                    timeout: 30m
                    notes: "Fill in: the action that changes the thing. Keep maxAttempts at 1 unless it is safe to retry."
                - verify:
                    name: Verify
                    call: provider
                    with: { provider: your-provider, action: your.get, input: { id: "{{ $input.id }}" } }
                    assert: "{{ $json.state == 'expected' }}"
                    notes: "Fill in: the field and value that prove the change worked."
              outputs:
                state: "{{ $('verify').json.state }}"
            """;

    static final String CREATE_AND_REGISTER = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: create-and-register
              title: Create and register a resource
              noun: thing
              verb: create
              owner: your-team
              folder: Starters
              tags: [starter, provisioning]
              version: 0.1.0
              description: Create something through a provider, wait until it is ready and record it in the registry.
            api:
              method: POST
              path: /api/v1/things
              input:
                type: object
                required: [name, environment]
                properties:
                  name: { type: string, title: Name, pattern: "^[a-z][a-z0-9-]{2,30}$" }
                  environment: { type: string, title: Environment, enum: [dev, test, prod], default: dev }
                  owner: { type: string, title: Owner team }
              response: { status: 202 }
              preview: true
            mcp:
              expose: true
              description: Create a thing and register it. The job output has its id.
            policy:
              rbac: [ops, developer]
            rollout:
              strategy: canary
              stages: [10, 50, 100]
            workflow:
              do:
                - create:
                    name: Create
                    call: provider
                    with: { provider: your-provider, action: your.create, input: { name: "{{ $input.name }}", environment: "{{ $input.environment }}" } }
                    output: { as: created }
                    retry: { maxAttempts: 2, backoff: exponential }
                    timeout: 20m
                    notes: "Fill in: the create action and its input fields."
                - waitReady:
                    name: Wait until ready
                    call: provider
                    with: { provider: your-provider, action: your.get, input: { id: "{{ $created.id }}" } }
                    assert: "{{ $json.state == 'ready' }}"
                    timeout: 15m
                    notes: "Fill in: the read action and the field that says the thing is ready."
                - register:
                    name: Register
                    register:
                      type: your-resource-type
                      id: "{{ $created.id }}"
                      attributes: { name: "{{ $input.name }}", environment: "{{ $input.environment }}", owner: "{{ $input.owner ?? $job.requester }}" }
                    notes: "Fill in: the resource type from the registry and the attributes its schema requires."
              outputs:
                id: "{{ $created.id }}"
                name: "{{ $input.name }}"
            """;

    static final String COMPOSITE = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: composite
              title: "Composite: run other workflows"
              noun: stack
              verb: create
              owner: your-team
              folder: Starters
              tags: [starter, composite]
              version: 0.1.0
              description: Build something bigger from published workflows, run as child jobs.
            api:
              method: POST
              path: /api/v1/stacks
              input:
                type: object
                required: [name, environment]
                properties:
                  name: { type: string, title: Name, pattern: "^[a-z][a-z0-9-]{2,20}$" }
                  environment: { type: string, title: Environment, enum: [dev, test, prod] }
              response: { status: 202 }
              preview: true
            mcp:
              expose: true
              description: Create a stack from other workflows in one job.
            policy:
              approval: { required: true, group: your-approvers, timeout: 12h }
              rbac: [ops, developer]
            rollout:
              strategy: canary
              stages: [10, 50, 100]
            workflow:
              do:
                - approval:
                    name: Approve the stack
                    approval:
                      group: your-approvers
                      message: "Create {{ $input.environment }} stack {{ $input.name }}?"
                      timeout: 12h
                    notes: "Fill in: the approver group."
                - build:
                    name: Build in parallel
                    fork:
                      branches:
                        - - first:
                              name: First part
                              run:
                                workflow: first-workflow
                                version: "^1"
                                input: { name: "{{ $input.name }}-a", environment: "{{ $input.environment }}" }
                              output: { as: first }
                              notes: "Fill in: a published workflow from the registry and its input."
                        - - second:
                              name: Second part
                              run:
                                workflow: second-workflow
                                version: "^1"
                                input: { name: "{{ $input.name }}-b", environment: "{{ $input.environment }}" }
                              output: { as: second }
                              notes: "Fill in: another published workflow, or remove the branch."
                - notifyDone:
                    name: Tell the channel
                    notify: { channel: "#your-channel", message: "Stack {{ $input.name }} ready for {{ $job.requester }}" }
                    notes: "Fill in: the channel to notify."
              outputs:
                first: "{{ $first.outputs }}"
                second: "{{ $second.outputs }}"
            """;

    static final String BULK_CHANGE = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: bulk-change
              title: Bulk-friendly change with verification
              noun: thing
              verb: tune
              owner: your-team
              folder: Starters
              tags: [starter, batch]
              version: 0.1.0
              description: A change on one registered resource with verification, tuned for batch runs in waves.
            api:
              method: POST
              path: /api/v1/things/{id}:tune
              input:
                type: object
                required: [id]
                properties:
                  id: { type: string, title: Id, x-resource: { type: your-resource-type } }
                  setting: { type: string, title: Setting, default: standard }
              response: { status: 202 }
              preview: true
              batch:
                max: 500
                rollout: { waves: [5, 25, 100], maxParallel: 10, stopIfFailedPercent: 5 }
            mcp:
              expose: true
              description: Tune one thing. Use the batch tool for a fleet.
            policy:
              rbac: [ops]
            rollout:
              strategy: canary
              stages: [10, 50, 100]
            workflow:
              do:
                - useTarget:
                    name: Use the resource
                    use: { type: your-resource-type, id: "{{ $input.id }}", checkAccess: true }
                    output: { as: target }
                    notes: "Fill in: the resource type; keep api.input.id.x-resource.type in sync."
                - change:
                    name: Apply the change
                    call: provider
                    with: { provider: your-provider, action: your.change, input: { id: "{{ $input.id }}", setting: "{{ $input.setting }}" } }
                    retry: { maxAttempts: 1 }
                    timeout: 20m
                    notes: "Fill in: the action that changes the thing. The batch waves and stopIfFailedPercent are in api.batch."
                - verify:
                    name: Verify
                    call: provider
                    with: { provider: your-provider, action: your.get, input: { id: "{{ $input.id }}" } }
                    assert: "{{ $json.setting == $input.setting }}"
                    notes: "Fill in: the read action and the field that proves the change."
              outputs:
                id: "{{ $input.id }}"
                setting: "{{ $('verify').json.setting }}"
            """;

    static final String ALERT_REMEDIATION = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: alert-remediation
              title: Alert-triggered remediation
              noun: thing
              verb: remediate
              owner: your-team
              folder: Starters
              tags: [starter, alert]
              version: 0.1.0
              description: Started by an alert; inspects first, stops when already healthy, remediates, verifies and notifies.
            api:
              method: POST
              path: /api/v1/things/{id}:remediate
              input:
                type: object
                required: [id]
                properties:
                  id: { type: string, title: Id }
                  reason: { type: string, title: Reason }
              response: { status: 202 }
              preview: true
            mcp:
              expose: true
              description: Remediate one thing after an alert.
            policy:
              rbac: [ops, sre]
            triggers:
              - alert:
                  match: { source: your-monitoring, labels: { severity: critical } }
                  input: { id: "{{ $alert.labels.id }}", reason: "{{ $alert.annotations.summary }}" }
            rollout:
              strategy: canary
              stages: [10, 50, 100]
            workflow:
              do:
                - inspect:
                    name: Inspect
                    call: provider
                    with: { provider: your-provider, action: your.get, input: { id: "{{ $input.id }}" } }
                    output: { as: current }
                    notes: "Fill in: the read action; the alert's match block and input mapping are in triggers."
                - alreadyHealthy:
                    name: Already healthy?
                    switch:
                      - case: { when: "{{ $current.state == 'healthy' }}", then: end }
                      - default: remediate
                    notes: "Fill in: the field and value that mean nothing needs doing."
                - remediate:
                    name: Remediate
                    call: provider
                    with: { provider: your-provider, action: your.restart, input: { id: "{{ $input.id }}" } }
                    retry: { maxAttempts: 1 }
                    timeout: 30m
                    notes: "Fill in: the remediation action, or swap this step for call: ansible with a pinned playbook."
                - verify:
                    name: Verify
                    call: provider
                    with: { provider: your-provider, action: your.get, input: { id: "{{ $input.id }}" } }
                    assert: "{{ $json.state == 'healthy' }}"
                    timeout: 10m
                - notifyDone:
                    name: Tell the channel
                    notify: { channel: "#your-channel", message: "Remediated {{ $input.id }}: {{ $input.reason }}" }
                    notes: "Fill in: the channel to notify."
              catch:
                do:
                  - pageOnCall:
                      name: Page the on-call
                      notify: { channel: "#your-alerts", message: "Remediation {{ $job.id }} of {{ $input.id }} failed: {{ $error.message }}" }
              outputs:
                state: "{{ $('verify').json.state }}"
            """;
}
