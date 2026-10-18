package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.providers.domain.AnsiblePlaybooks.Playbook;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** Which files of a repository are playbooks, and the extra vars each one takes. */
class AnsiblePlaybooksTest {

    static final String RESTART = """
            ---
            # Restarts one VM, gracefully unless told otherwise.
            # Waits for it to come back.
            - name: Restart a VM
              hosts: "{{ target }}"
              vars:
                graceful: true
                wait_seconds: "{{ timeout | default(300) }}"
              roles:
                - role: vm_power
                  power_action: restart
              tasks:
                - name: Check the input
                  ansible.builtin.assert:
                    that:
                      - vm_id is defined
                - ansible.builtin.debug:
                    msg: "{{ vm_id }} for {{ reason | default('maintenance') }} on {{ inventory_hostname }}"
                - ansible.builtin.set_fact:
                    started: true
                - ansible.builtin.debug:
                    msg: "{{ started }} {{ hostvars[inventory_hostname].ansible_host }}"
                - name: Note the ticket
                  ansible.builtin.debug:
                    msg: "{{ ticket | upper }}"
                  when: ticket is defined
            """;

    static Map<String, String> repository() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("site.yml", "- import_playbook: vm/restart.yml\n");
        files.put("vm/restart.yml", RESTART);
        files.put("roles/vm_power/meta/argument_specs.yml", """
                argument_specs:
                  main:
                    options:
                      vm_id:
                        type: str
                        required: true
                        description: The VM to act on
                      power_action:
                        type: str
                        choices: [start, stop, restart]
                      retries:
                        type: int
                        default: 3
                      admin_password:
                        type: str
                        no_log: true
                """);
        files.put("roles/vm_power/defaults/main.yml", """
                retries: 3
                power_timeout: 600
                api_url: "https://{{ vcenter_host }}/sdk"
                """);
        files.put("roles/vm_power/vars/main.yml", "internal_flag: true\n");
        files.put("roles/vm_power/tasks/main.yml", """
                - ansible.builtin.include_tasks: power.yml
                - ansible.builtin.debug:
                    msg: "{{ internal_flag }} {{ power_timeout }} {{ api_url }}"
                """);
        files.put("roles/vm_power/tasks/power.yml", """
                - name: Power
                  ansible.builtin.debug:
                    msg: "{{ power_action }} {{ vm_id }} {{ item }}"
                  loop: "{{ vm_list | default([]) }}"
                  register: power_result
                - ansible.builtin.debug:
                    msg: "{% for r in power_result.results %}{{ r.changed }}{% endfor %}"
                """);
        files.put("group_vars/all.yml", "vcenter_user: automation\n");
        files.put("mongo/set-fcv.yml", """
                - hosts: mongo
                  vars_prompt:
                    - name: fcv
                      prompt: Feature compatibility version
                      private: false
                  tasks:
                    - ansible.builtin.fail:
                        msg: fcv is required
                      when: fcv is not defined
                    - ansible.builtin.command: "mongosh --eval 'setFCV({{ fcv }})'"
                """);
        files.put("decommission-vm.yml", """
                - hosts: all
                  tasks:
                    - ansible.builtin.debug:
                        msg: "{{ vm_id }}"
                """);
        files.put("checks/health.yml", """
                - hosts: all
                  check_mode: true
                  tasks:
                    - ansible.builtin.ping:
                """);
        files.put("tasks/common.yml", "- ansible.builtin.debug: { msg: hi }\n");
        files.put("inventory/hosts.yml", "all:\n  hosts:\n    web1: {}\n");
        files.put("broken.yml", "- hosts: [\n");
        files.put("requirements.yml", "collections:\n  - community.vmware\n");
        files.put("docker-compose.yml", "services:\n  db: { image: postgres }\n");
        return files;
    }

    static JsonNode json(String yaml) {
        return WorkflowYaml.readTree(yaml);
    }

    static Playbook playbook(AnsiblePlaybooks.Repository repo, String path) {
        return repo.playbooks().stream().filter(p -> p.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void playbooksAreTheListsOfPlaysOutsideRoleAndVariableFolders() {
        AnsiblePlaybooks.Repository repo = AnsiblePlaybooks.analyze(repository(), null);
        assertThat(repo.playbooks()).extracting(Playbook::path)
                .containsExactly("checks/health.yml", "decommission-vm.yml", "mongo/set-fcv.yml", "site.yml", "vm/restart.yml");
        assertThat(repo.skipped()).containsExactly("broken.yml (not valid YAML)");
        assertThat(repo.requirements()).containsExactly("requirements.yml");
    }

    @Test
    void aPlaybooksInputsComeFromArgumentSpecsDefaultsPlayVarsAndWhatItUsesWithoutAValue() {
        Playbook restart = playbook(AnsiblePlaybooks.analyze(repository(), ""), "vm/restart.yml");
        assertThat(restart.action()).isEqualTo("vm.restart");
        assertThat(restart.title()).isEqualTo("Restart a VM");
        assertThat(restart.effect()).isEqualTo(Effect.CHANGE);
        assertThat(restart.roles()).containsExactly("vm_power");
        assertThat(restart.description()).isEqualTo("Restarts one VM, gracefully unless told otherwise. Waits for it to come back.");
        assertThat(restart.vars()).isEqualTo(json("""
                type: object
                required: [vm_id, vcenter_host, target]
                properties:
                  vm_id: { type: string, description: The VM to act on }
                  vcenter_host: { type: string }
                  target: { type: string }
                  graceful: { type: boolean, default: true }
                  timeout: { type: integer, default: 300 }
                  retries: { type: integer, default: 3 }
                  power_timeout: { type: integer, default: 600 }
                  vm_list: { type: array, default: [] }
                  reason: { type: string, default: maintenance }
                  ticket: { type: string }
                """));
        assertThat(restart.sources()).containsEntry("vm_id", "roles/vm_power/meta/argument_specs.yml")
                .containsEntry("retries", "roles/vm_power/meta/argument_specs.yml")
                .containsEntry("power_timeout", "roles/vm_power/defaults/main.yml")
                .containsEntry("graceful", "play vars of vm/restart.yml")
                .containsEntry("target", "used by the playbook without a value");
        assertThat(restart.secrets()).containsExactly("admin_password");
        assertThat(restart.notes()).isEmpty();
    }

    @Test
    void anImportedPlaybookBringsItsVariablesButNotItsTitle() {
        Playbook site = playbook(AnsiblePlaybooks.analyze(repository(), ""), "site.yml");
        assertThat(site.action()).isEqualTo("site.run");
        assertThat(site.title()).isEqualTo("Site");
        assertThat(site.vars().path("required")).isEqualTo(json("[vm_id, vcenter_host, target]"));
    }

    @Test
    void aPromptIsAnInputAndAFailWhenItIsMissingMakesItRequired() {
        Playbook fcv = playbook(AnsiblePlaybooks.analyze(repository(), ""), "mongo/set-fcv.yml");
        assertThat(fcv.action()).isEqualTo("mongo.setFcv");
        assertThat(fcv.vars()).isEqualTo(json("""
                type: object
                required: [fcv]
                properties:
                  fcv: { type: string, description: Feature compatibility version }
                """));
        assertThat(fcv.sources()).containsEntry("fcv", "vars_prompt of mongo/set-fcv.yml");
    }

    @Test
    void theNameSaysWhatAPlaybookDoesAndCheckModeMakesItARead() {
        AnsiblePlaybooks.Repository repo = AnsiblePlaybooks.analyze(repository(), "");
        Playbook decommission = playbook(repo, "decommission-vm.yml");
        assertThat(decommission.action()).isEqualTo("vm.decommission");
        assertThat(decommission.effect()).isEqualTo(Effect.DELETE);
        Playbook health = playbook(repo, "checks/health.yml");
        assertThat(health.action()).isEqualTo("checks.health");
        assertThat(health.effect()).isEqualTo(Effect.READ);
    }

    @Test
    void aFolderLimitsThePlaybooksAndNamesThemFromInsideIt() {
        AnsiblePlaybooks.Repository repo = AnsiblePlaybooks.analyze(repository(), "/mongo/");
        assertThat(repo.playbooks()).extracting(Playbook::path, Playbook::action).containsExactly(org.assertj.core.groups.Tuple.tuple("mongo/set-fcv.yml", "fcv.set"));
    }

    @Test
    void rolesAreFoundThroughTheRolesPathAndMissingOnesAreNoted() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ansible.cfg", "[defaults]\ninventory = ./inventory\nroles_path = ./shared/roles:/etc/ansible/roles\n");
        files.put("shared/roles/patch/defaults/main.yml", "reboot: false\n");
        files.put("patch.yml", """
                - hosts: all
                  roles:
                    - patch
                    - acme.linux.hardening
                    - { role: monitoring, when: monitoring_enabled | default(true) }
                """);
        Playbook patch = AnsiblePlaybooks.analyze(files, "").playbooks().get(0);
        assertThat(patch.roles()).containsExactly("patch");
        assertThat(patch.vars()).isEqualTo(json("""
                type: object
                properties:
                  reboot: { type: boolean, default: false }
                  monitoring_enabled: { type: boolean, default: true }
                """));
        assertThat(patch.notes()).containsExactly(
                "Role acme.linux.hardening comes from a collection that is not in the repository; add its variables to the action's vars by hand",
                "Role monitoring is not in the repository (installed from requirements.yml?); add its variables to the action's vars by hand");
    }

    @Test
    void whatGroupAndHostVariablesSetIsNotAnInputWhateverTheirFilesAreCalled() {
        assertThat(AnsiblePlaybooks.wanted("lamp/group_vars/all")).isTrue();
        assertThat(AnsiblePlaybooks.wanted("lamp/host_vars/db1.json")).isTrue();
        assertThat(AnsiblePlaybooks.wanted("lamp/group_vars/notes.txt")).isFalse();
        assertThat(AnsiblePlaybooks.wanted("lamp/README")).isFalse();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lamp/site.yml", """
                - hosts: webservers
                  tasks:
                    - ansible.builtin.debug:
                        msg: "{{ httpd_port }} {{ dbname }} {{ backup_host }} {{ release }}"
                """);
        files.put("lamp/group_vars/all", "httpd_port: 80\n");
        files.put("lamp/group_vars/dbservers/main.yml", "dbname: app\n");
        files.put("lamp/host_vars/db1.json", "{ \"backup_host\": \"nas1\" }\n");
        Playbook site = AnsiblePlaybooks.analyze(files, "lamp").playbooks().get(0);
        assertThat(site.vars()).isEqualTo(json("{ type: object, required: [release], properties: { release: { type: string } } }"));
    }

    @Test
    void secretsAndVaultValuesAreNeverInputs() {
        Map<String, String> files = Map.of("deploy-app.yml", """
                - hosts: app
                  vars:
                    db_password: !vault |
                      $ANSIBLE_VAULT;1.1;AES256
                      62313365396662343061393464336163
                    release: "1.4.2"
                  tasks:
                    - ansible.builtin.debug:
                        msg: "{{ release }} {{ db_password }} {{ api_token }}"
                """);
        Playbook deploy = AnsiblePlaybooks.analyze(files, "").playbooks().get(0);
        assertThat(deploy.action()).isEqualTo("app.deploy");
        assertThat(deploy.effect()).isEqualTo(Effect.CREATE);
        assertThat(deploy.vars()).isEqualTo(json("{ type: object, properties: { release: { type: string, default: '1.4.2' } } }"));
        assertThat(deploy.secrets()).containsExactly("api_token");
        assertThat(deploy.notes()).containsExactly("deploy-app.yml uses values encrypted with ansible-vault: the runner needs the vault password to run it");
        assertThat(deploy.vars().toString()).doesNotContain("ANSIBLE_VAULT");
    }

    @Test
    void theDraftOffersOneActionPerPlaybookPinnedToTheCommitAndPassesTheChecks() {
        AnsiblePlaybooks.Repository repo = AnsiblePlaybooks.analyze(repository(), "");
        List<Playbook> chosen = repo.playbooks().stream().filter(p -> !p.path().equals("site.yml")).toList();
        String yaml = ProviderYaml.toYaml(ProviderYaml.fromJson(AnsiblePlaybooks.provider(new AnsiblePlaybooks.Draft("ops-playbooks", "Ops playbooks (Ansible)",
                "team-platform", "1.0.0", "The playbooks.", "https://github.com/acme/ops-playbooks", "v3.1.0", "vault:github/read-token",
                "4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d", "vault:ssh/automation", "ee-platform:2.17"), chosen)));
        ProviderDefinition def = ProviderYaml.parse(yaml);
        assertThat(def.type()).isEqualTo(ProviderType.ANSIBLE);
        assertThat(def.spec().source()).isEqualTo(json("""
                git: { repo: "https://github.com/acme/ops-playbooks", ref: v3.1.0, credential: "vault:github/read-token" }
                commit: 4e1d9a2c7b0f4e1d9a2c7b0f4e1d9a2c7b0f4e1d
                """));
        assertThat(def.spec().executionEnvironment()).isEqualTo("ee-platform:2.17");
        assertThat(def.spec().auth().credential()).isEqualTo("vault:ssh/automation");
        assertThat(def.actions()).extracting(ProviderDefinition.Action::id)
                .containsExactly("checks.health", "vm.decommission", "mongo.setFcv", "vm.restart");
        ProviderDefinition.Action decommission = def.action("vm.decommission").orElseThrow();
        assertThat(decommission.approvalRequired()).isTrue();
        assertThat(decommission.effectDetail()).contains("decommission-vm.yml");
        assertThat(def.action("checks.health").orElseThrow().retrySafe()).isTrue();
        assertThat(def.action("vm.restart").orElseThrow().retrySafe()).isFalse();
        assertThat(def.action("vm.restart").orElseThrow().playbook()).isEqualTo("vm/restart.yml");
        assertThat(ProviderChecks.check(yaml).errors()).isEmpty();
        assertThat(ProviderReview.review(def, ReviewEvidence.NONE).ready()).isTrue();
    }

    @Test
    void jinjaReferencesSkipAttributesFiltersTestsCallsAndLoopVariables() {
        Map<String, String> files = Map.of("report.yml", """
                - hosts: all
                  tasks:
                    - ansible.builtin.debug:
                        msg: >-
                          {{ server.name | regex_replace('a', 'b') }}
                          {{ lookup('env', 'HOME') }} {{ 'quoted_word' }}
                          {{ items | map(attribute='id') | list }}
                          {% for host in groups['web'] %}{{ host }}{% endfor %}
                          {% set total = count + 1 %}{{ total }}
                          {{ 'yes' if enabled is sameas true else 'no' }}
                      when: limit is not none and region in allowed
                """);
        Playbook report = AnsiblePlaybooks.analyze(files, "").playbooks().get(0);
        assertThat(report.action()).isEqualTo("report.run");
        assertThat(report.effect()).isEqualTo(Effect.READ);
        assertThat(report.vars().path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("server", "items", "count", "enabled", "limit", "region", "allowed");
        assertThat(report.vars().path("required")).isEqualTo(json("[server, items, count, enabled, region, allowed]"));
    }

    @Test
    void aHostileRepositoryCostsLinearWorkAndWhatTheBudgetLeavesIsSkipped() {
        // each part once took time quadratic in its length: unclosed brackets after variables, a long
        // statement that is almost a for loop, default( calls that never close, templates that never close
        String hostile = "{{ " + "a[".repeat(50_000) + " }}" + "{% for x" + " ".repeat(100_000) + "y %}"
                + "{{ " + "b | default(".repeat(20_000) + " }}" + "{{ c".repeat(50_000);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("noisy.yml", "- hosts: all\n  tasks:\n    - ansible.builtin.debug:\n        msg: \"" + hostile + "\"\n"
                + "    - ansible.builtin.debug:\n        msg: \"{% for k, v in settings | dict2items %}{{ k }}={{ v }} {{ zone }}{% endfor %}\"\n");
        for (int i = 0; i < 30; i++) {
            files.put("roles/r" + i + "/meta/main.yml", "dependencies: [r" + (i + 1) + "]\n");
            files.put("roles/r" + i + "/defaults/main.yml", "v" + i + ": " + i + "\n");
        }
        files.put("nested.yml", "- hosts: all\n  roles: [r0]\n");
        AnsiblePlaybooks.Repository repo = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> AnsiblePlaybooks.analyze(files, ""));
        Playbook noisy = playbook(repo, "noisy.yml");
        assertThat(noisy.vars().path("properties").fieldNames()).toIterable()
                .contains("a", "b", "settings", "zone").doesNotContain("c", "k", "v");
        assertThat(noisy.vars().path("required")).contains(json("a")).doesNotContain(json("b"));
        Playbook nested = playbook(repo, "nested.yml");
        assertThat(nested.roles()).hasSize(AnsiblePlaybooks.MAX_NESTING + 1);
        assertThat(nested.vars().path("properties").has("v19")).isTrue();
        assertThat(nested.vars().path("properties").has("v20")).isFalse();

        AnsiblePlaybooks.Repository spent = AnsiblePlaybooks.analyze(repository(), "", 0);
        assertThat(spent.playbooks()).isEmpty();
        assertThat(spent.skipped()).contains("checks/health.yml (" + AnsiblePlaybooks.TOO_LARGE + ")", "vm/restart.yml (" + AnsiblePlaybooks.TOO_LARGE + ")");
    }

    @Test
    void namesSplitIntoANounAndAVerb() {
        assertThat(AnsiblePlaybooks.nounVerb("vm/restart.yml")).containsExactly("vm", "restart");
        assertThat(AnsiblePlaybooks.nounVerb("mongo/set-fcv.yml")).containsExactly("mongo", "setFcv");
        assertThat(AnsiblePlaybooks.nounVerb("playbooks/restart_web_server.yml")).containsExactly("webServer", "restart");
        assertThat(AnsiblePlaybooks.nounVerb("01-bootstrap.yaml")).containsExactly("bootstrap", "run");
        assertThat(AnsiblePlaybooks.nounVerb("ops/db-tasks/backup.yml")).containsExactly("dbTasks", "backup");
        assertThat(AnsiblePlaybooks.nounVerb("examples/playbooks/4114/fix-mounts.yml")).containsExactly("examples", "fixMounts");
    }
}
