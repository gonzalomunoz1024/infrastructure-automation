package com.iaf.platform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The demo agent's edit mode on a draft, and its explain mode. */
class DraftEditorTest {

    static final String RESTART = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata:
              name: vm-restart
              title: VM restart
              owner: team-compute
              description: Restart a virtual machine after safety checks
            api:
              method: POST
              path: /api/v1/vms/{vmId}:restart
              input:
                type: object
                required: [vmId]
                properties:
                  vmId: { type: string, x-resource: { type: vm } }
                  reason: { type: string }
            mcp:
              expose: true
            policy:
              approval: { required: true, group: compute-approvers, timeout: 4h }
            triggers:
              - alert:
                  match: { source: grafana, labels: { alertname: VMUnresponsive } }
                  input: { vmId: "{{ $alert.labels.vm_id }}" }
            workflow:
              do:
                - vm:
                    name: Use the VM
                    use: { type: vm, id: "{{ $input.vmId }}" }
                    output: { as: vm }
                - checkVmExists:
                    name: Check VM exists
                    call: provider
                    with:
                      provider: vsphere
                      action: vm.get
                      input: { id: "{{ $input.vmId }}" }
                - approval:
                    name: Approve restart
                    approval: { group: compute-approvers, message: "Restart {{ $input.vmId }}?", timeout: 4h }
                - restart:
                    name: Restart with Ansible
                    call: ansible
                    with:
                      playbook:
                        git: { repo: github.com/acme/ops-playbooks, ref: v3.1.0, path: vm/restart.yml }
                - notifyDone:
                    name: Tell the channel
                    notify: { channel: "#compute-ops", message: "Restarted {{ $input.vmId }}" }
              catch:
                do:
                  - notifyOwner:
                      name: Tell the on-call
                      notify: { channel: "#compute-alerts", message: "failed" }
              outputs:
                vmName: "{{ $vm.name }}"
                powerState: "{{ $json.powerState }}"
            """;

    @Test
    void anExplainRequestIsToldApartFromAnEdit() {
        assertThat(DraftExplainer.asks("Explain what this workflow does")).isTrue();
        assertThat(DraftExplainer.asks("please describe the flow")).isTrue();
        assertThat(DraftExplainer.asks("Can you summarise it?")).isTrue();
        assertThat(DraftExplainer.asks("summarize the steps")).isTrue();
        assertThat(DraftExplainer.asks("What does it do?")).isTrue();
        assertThat(DraftExplainer.asks("walk me through the steps")).isTrue();

        assertThat(DraftExplainer.asks("add an approval before the restart")).isFalse();
        assertThat(DraftExplainer.asks("set the description to Restart a VM")).isFalse();
        assertThat(DraftExplainer.asks("rename the step to explain")).isFalse();
        assertThat(DraftExplainer.asks("")).isFalse();
        assertThat(DraftExplainer.asks(null)).isFalse();
    }

    @Test
    void explainingSaysWhatStartsItEveryStepInOrderItsApprovalsAndOutputsAndChangesNothing() {
        String before = WorkflowDoc.toYaml(WorkflowDoc.of(RESTART));

        DraftEditor.Result r = DraftEditor.edit(RESTART, "Explain what this workflow does", Registry.EMPTY, null);

        assertThat(r.explained()).isTrue();
        assertThat(r.yaml()).as("the draft is left as it was").isEqualTo(before);
        assertThat(r.changes()).isEmpty();
        assertThat(r.questions()).as("no could-not-apply question").isEmpty();
        String text = r.explanation();
        assertThat(text).startsWith("VM restart (vm-restart), owned by team-compute: Restart a virtual machine after safety checks.");
        assertThat(text).contains("It is started by: the API call POST /api/v1/vms/{vmId}:restart, taking vmId (required) and reason; "
                + "AI agents, through its MCP tool; an alert from grafana labelled alertname=VMUnresponsive.");
        assertThat(text).contains("Its steps, in order:\n"
                + "1. Use the VM: uses the vm {{ $input.vmId }}, checking first that the requester may use it\n"
                + "2. Check VM exists: calls the vsphere provider's vm.get\n"
                + "3. Approve restart: waits for an approval by compute-approvers (for up to 4h)\n"
                + "4. Restart with Ansible: runs the Ansible playbook vm/restart.yml from github.com/acme/ops-playbooks\n"
                + "5. Tell the channel: notifies #compute-ops\n"
                + "If a step fails: Tell the on-call: notifies #compute-alerts.");
        assertThat(text).contains("Approvals: Approve restart by compute-approvers; its policy requires an approval by compute-approvers before it runs.");
        assertThat(text).endsWith("It returns vmName and powerState.");
    }

    @Test
    void nestedBlocksAreExplainedInlineAndAnEmptyDraftSaysSo() {
        String nested = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: patch-all }
                workflow:
                  do:
                    - eachVm:
                        name: Patch every VM
                        for:
                          each: vm
                          in: "{{ $input.vms }}"
                          do:
                            - patch:
                                name: Patch
                                call: provider
                                with: { provider: patching, action: patch.apply }
                    - wait:
                        name: Settle
                        wait: PT5M
                """;

        String text = DraftEditor.edit(nested, "describe it", Registry.EMPTY, null).explanation();

        assertThat(text).startsWith("patch-all.");
        assertThat(text).contains("Nothing starts it yet: it has no API path and no trigger.");
        assertThat(text).contains("1. Patch every VM: repeats for each vm in {{ $input.vms }} (Patch: calls the patching provider's patch.apply)");
        assertThat(text).contains("2. Settle: waits PT5M");
        assertThat(text).contains("It asks nobody for an approval.");
        assertThat(text).endsWith("It returns no outputs beyond the job's status.");
    }

    @Test
    void anEditIsStillAnEdit() {
        DraftEditor.Result r = DraftEditor.edit(RESTART, "rename the step Tell the channel to Announce", Registry.EMPTY, null);

        assertThat(r.explained()).isFalse();
        assertThat(r.explanation()).isNull();
        assertThat(r.changes()).singleElement().asString().contains("to 'Announce'");
    }
}
