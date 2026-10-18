package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;

/** The event shapes are those ansible-runner 2.x and the ansible.posix json callback print. */
class AnsibleEventsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A run where one task changed something, one failure was ignored and the playbook published two stats. */
    static final List<String> PASSING = List.of(
            "{\"event\":\"playbook_on_start\",\"counter\":1,\"stdout\":\"\",\"event_data\":{\"playbook\":\"site.yml\"}}",
            "{\"event\":\"playbook_on_play_start\",\"counter\":2,\"stdout\":\"\\r\\nPLAY [smoke] ****\",\"event_data\":{\"name\":\"smoke\",\"play\":\"smoke\",\"pattern\":\"all\"}}",
            "{\"event\":\"playbook_on_task_start\",\"counter\":3,\"event_data\":{\"name\":\"say hello\",\"task\":\"say hello\"}}",
            "{\"event\":\"runner_on_start\",\"counter\":4,\"event_data\":{\"host\":\"localhost\",\"task\":\"say hello\"}}",
            "{\"event\":\"runner_on_ok\",\"counter\":5,\"event_data\":{\"host\":\"localhost\",\"task\":\"say hello\",\"res\":{\"msg\":\"hello world\",\"changed\":false}}}",
            "{\"event\":\"playbook_on_task_start\",\"counter\":6,\"event_data\":{\"name\":\"change something\",\"task\":\"change something\"}}",
            "{\"event\":\"runner_on_ok\",\"counter\":8,\"event_data\":{\"host\":\"localhost\",\"task\":\"change something\",\"res\":{\"changed\":true}}}",
            "{\"event\":\"playbook_on_task_start\",\"counter\":9,\"event_data\":{\"name\":\"fail but ignore\",\"task\":\"fail but ignore\"}}",
            "{\"event\":\"runner_on_failed\",\"counter\":11,\"event_data\":{\"host\":\"localhost\",\"task\":\"fail but ignore\",\"ignore_errors\":true,"
                    + "\"res\":{\"changed\":false,\"msg\":\"ignored failure\"}}}",
            "{\"event\":\"playbook_on_stats\",\"counter\":15,\"event_data\":{\"changed\":{\"localhost\":1},\"dark\":{},\"failures\":{},"
                    + "\"ignored\":{\"localhost\":1},\"ok\":{\"localhost\":4},\"processed\":{\"localhost\":1},\"rescued\":{},\"skipped\":{},"
                    + "\"artifact_data\":{\"rebootRequired\":true,\"packages\":[{\"name\":\"openssl\"}],\"ok\":\"not the recap\"}}}");

    static List<String> feed(AnsibleEvents events, List<String> lines) {
        List<String> log = new ArrayList<>();
        lines.forEach(l -> log.add(events.event(l)));
        return log.stream().filter(Objects::nonNull).toList();
    }

    @Test
    void runnerEventsGiveTheRecapTheStatsAndTheLogLines() throws Exception {
        AnsibleEvents events = new AnsibleEvents(null);

        List<String> log = feed(events, PASSING);

        assertThat(log).containsExactly("PLAY [smoke]", "TASK [say hello]", "TASK [change something]", "changed: [localhost]",
                "TASK [fail but ignore]", "failed (ignored): [localhost] ignored failure");
        JsonNode out = events.output(false);
        assertThat(out).isEqualTo(JSON.readTree("""
                {"ok": 4, "changed": 1, "failed": 0, "unreachable": 0, "skipped": 0,
                 "hosts": {"localhost": {"ok": 4, "changed": 1, "unreachable": 0, "failed": 0, "skipped": 0}},
                 "rebootRequired": true, "packages": [{"name": "openssl"}],
                 "stats": {"rebootRequired": true, "packages": [{"name": "openssl"}], "ok": "not the recap"}}
                """));
        assertThat(events.failure(0, List.of())).isNull();
        assertThat(events.recapLines()).containsExactly("localhost: ok=4 changed=1 unreachable=0 failed=0 skipped=0");
    }

    @Test
    void failedHostsFailTheStepNamingTheTaskAndTheMessage() {
        AnsibleEvents events = new AnsibleEvents(s -> s.replace("hunter2", "****"));
        feed(events, List.of(
                "{\"event\":\"runner_on_failed\",\"event_data\":{\"host\":\"web1\",\"task\":\"break here\",\"res\":{\"msg\":\"disk is full, password hunter2\"}}}",
                "{\"event\":\"runner_on_failed\",\"event_data\":{\"host\":\"web2\",\"task\":\"break here\",\"res\":{\"stderr\":\"no space\"}}}",
                "{\"event\":\"playbook_on_stats\",\"event_data\":{\"ok\":{},\"changed\":{},\"dark\":{},\"failures\":{\"web1\":1,\"web2\":1},\"skipped\":{},"
                        + "\"processed\":{\"web1\":1,\"web2\":1},\"artifact_data\":{}}}"));

        ProviderCallException e = events.failure(2, List.of());

        assertThat(e.errorClass()).isEqualTo("failed");
        assertThat(e.retryable()).isFalse();
        assertThat(e.getMessage()).isEqualTo("2 hosts failed: web1 (task 'break here': disk is full, password ****); web2 (task 'break here': no space)");
        assertThat(events.output(true).path("checkMode").asBoolean()).isTrue();
    }

    @Test
    void unreachableHostsAreARetryableError() {
        AnsibleEvents events = new AnsibleEvents(null);
        List<String> log = feed(events, List.of(
                "{\"event\":\"runner_on_unreachable\",\"event_data\":{\"host\":\"db1\",\"task\":\"ping it\",\"res\":{\"msg\":\"ssh: connect to host db1 port 22: Connection refused\",\"unreachable\":true}}}",
                "{\"event\":\"playbook_on_stats\",\"event_data\":{\"ok\":{},\"dark\":{\"db1\":1},\"failures\":{},\"processed\":{\"db1\":1}}}"));

        ProviderCallException e = events.failure(4, List.of());

        assertThat(log).containsExactly("unreachable: [db1] ssh: connect to host db1 port 22: Connection refused");
        assertThat(e.errorClass()).isEqualTo("unreachable");
        assertThat(e.retryable()).isTrue();
        assertThat(e.getMessage()).isEqualTo("1 host unreachable: db1 (task 'ping it': ssh: connect to host db1 port 22: Connection refused)");
    }

    @Test
    void aRunThatEndsWithoutARecapFailsWithWhatAnsiblePrintedLast() {
        AnsibleEvents events = new AnsibleEvents(null);

        List<String> log = feed(events, List.of("[ERROR]: the playbook: missing.yml could not be found"));

        assertThat(log).containsExactly("[ERROR]: the playbook: missing.yml could not be found");
        assertThat(events.failure(1, List.of("some stderr")).getMessage())
                .isEqualTo("ansible exited with 1: [ERROR]: the playbook: missing.yml could not be found");
        assertThat(new AnsibleEvents(null).failure(1, List.of("warning", "the last stderr line")).getMessage())
                .isEqualTo("ansible exited with 1: the last stderr line");
    }

    @Test
    void theJsonCallbackDocumentGivesTheSameOutput() throws Exception {
        JsonNode doc = JSON.readTree("""
                {"custom_stats": {}, "global_custom_stats": {"rebootRequired": true},
                 "plays": [{"play": {"name": "smoke"}, "tasks": [
                   {"task": {"name": "say hello"}, "hosts": {"localhost": {"changed": false, "msg": "hello world"}}},
                   {"task": {"name": "fail but ignore"}, "hosts": {"localhost": {"changed": false, "failed": true, "msg": "ignored failure"}}}]}],
                 "stats": {"localhost": {"changed": 1, "failures": 0, "ignored": 1, "ok": 4, "rescued": 0, "skipped": 0, "unreachable": 0}}}
                """);
        AnsibleEvents events = new AnsibleEvents(null);

        events.document(doc);

        assertThat(AnsibleEvents.tasks(doc)).containsExactly("PLAY [smoke]", "TASK [say hello]", "TASK [fail but ignore]");
        assertThat(events.failure(0, List.of())).as("an ignored failure leaves no failed host in the recap").isNull();
        assertThat(events.output(false)).isEqualTo(JSON.readTree("""
                {"ok": 4, "changed": 1, "failed": 0, "unreachable": 0, "skipped": 0,
                 "hosts": {"localhost": {"ok": 4, "changed": 1, "unreachable": 0, "failed": 0, "skipped": 0}},
                 "rebootRequired": true, "stats": {"rebootRequired": true}}
                """));
    }
}
