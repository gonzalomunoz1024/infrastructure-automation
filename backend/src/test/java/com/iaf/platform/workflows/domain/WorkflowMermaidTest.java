package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.workflows.SampleFixtures;

class WorkflowMermaidTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    /** What may stand between the quotes of a label: no quote, line break or character Mermaid reads as syntax, except as an entity code or the name/type break. */
    static final Pattern SAFE = Pattern.compile("(?:[^\"#&<>`|%\\n]|#\\d+;|<br/>)*");
    static final Pattern QUOTED = Pattern.compile("\"([^\"\\n]*)\"");
    static final Pattern NODE = Pattern.compile("(\\w+)(?:\\(\\[\"[^\"]*\"\\]\\)|\\[\"[^\"]*\"\\]|\\{\\{\"[^\"]*\"\\}\\}|\\{\"[^\"]*\"\\})");
    static final Pattern SUBGRAPH = Pattern.compile("subgraph (\\w+) \\[\"[^\"]*\"\\]");
    static final Pattern EDGE = Pattern.compile("(\\w+) (?:-->|-\\.->)(?:\\|\"[^\"]*\"\\|)? (\\w+)");
    static final Set<String> KEYWORDS = Set.of("end", "subgraph", "graph", "flowchart", "direction", "style", "class", "classDef", "click", "linkStyle",
            "default", "call", "href");

    static int occurrences(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * Every line is the header, a node, a subgraph, its end or an edge; subgraphs close; edges join
     * declared ids, none of them a Mermaid keyword; every label is escaped.
     */
    static void wellFormed(String chart, String where) {
        String[] lines = chart.split("\n");
        assertThat(lines[0]).as(where).isEqualTo("flowchart LR");
        assertThat(chart).as(where).endsWith("\n");
        Set<String> declared = new HashSet<>();
        int open = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher node = NODE.matcher(line);
            Matcher subgraph = SUBGRAPH.matcher(line);
            Matcher edge = EDGE.matcher(line);
            if (subgraph.matches()) {
                assertThat(declared.add(subgraph.group(1))).as(where + ": " + line + " is declared once").isTrue();
                open++;
            } else if (line.equals("end")) {
                open--;
                assertThat(open).as(where + ": an end closes a subgraph").isNotNegative();
            } else if (node.matches()) {
                assertThat(declared.add(node.group(1))).as(where + ": " + line + " is declared once").isTrue();
            } else if (edge.matches()) {
                assertThat(declared).as(where + ": " + line).contains(edge.group(1), edge.group(2));
            } else {
                throw new AssertionError(where + ": line " + i + " is not Mermaid this class writes: " + lines[i]);
            }
            assertThat(occurrences(line, "\"") % 2).as(where + ": " + line).isZero();
        }
        assertThat(open).as(where + ": every subgraph ends").isZero();
        assertThat(declared).as(where).doesNotContainAnyElementsOf(KEYWORDS);
        Matcher quoted = QUOTED.matcher(chart);
        while (quoted.find()) {
            assertThat(quoted.group(1)).as(where).matches(SAFE);
        }
    }

    @Test
    void everySampleIsAFlowchartWithEachStepOnceByNameAndType() {
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(SampleFixtures.ROOT, "Workflow");
        assertThat(files).hasSizeGreaterThanOrEqualTo(9);
        for (var f : files) {
            String where = f.path().getFileName().toString();
            WorkflowDefinition def = WorkflowYaml.parse(f.yaml());
            String chart = WorkflowMermaid.of(def);
            wellFormed(chart, where);
            List<Step> steps = def.allSteps();
            assertThat(steps.stream().map(Step::displayName).distinct().count()).as(where + ": the sample names its steps apart").isEqualTo(steps.size());
            for (Step s : steps) {
                assertThat(occurrences(chart, "\"" + WorkflowMermaid.escape(s.displayName()) + WorkflowMermaid.BREAK + WorkflowMermaid.escape(WorkflowMermaid.kind(s)) + "\""))
                        .as(where + ": " + s.id()).isEqualTo(1);
            }
            assertThat(occurrences(chart, WorkflowMermaid.BREAK)).as(where + ": a label for each step and nothing else").isEqualTo(steps.size());
            assertThat(chart).as(where).contains("t0([\"" + WorkflowMermaid.escape(def.method() + " " + def.path()) + "\"])");
        }
    }

    @Test
    void aWorkflowIsItsWaysInItsStepsInOrderAndItsCatchOnError() {
        String chart = WorkflowMermaid.of(WorkflowYaml.parse(SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml")));

        assertThat(chart).isEqualTo("""
                flowchart LR
                  t0(["POST /api/v1/vms/{vmId}:restart"])
                  t1(["MCP tool vm_restart"])
                  t2(["alert from grafana: VMUnresponsive"])
                  subgraph main ["do"]
                    s1["Check VM exists<br/>provider: vsphere vm.get"]
                    s2["No backup running<br/>provider: backup jobs.list"]
                    s3{{"Approve restart<br/>approval"}}
                    s4["Restart with Ansible<br/>ansible"]
                    s5["Wait for power on<br/>provider: vsphere vm.waitForPowerState"]
                    s6["Tell the channel<br/>notify"]
                  end
                  subgraph onError ["catch"]
                    s7["Tell the on-call<br/>notify"]
                  end
                  t0 --> s1
                  t1 --> s1
                  t2 --> s1
                  s1 --> s2
                  s2 --> s3
                  s3 --> s4
                  s4 --> s5
                  s5 --> s6
                  main -.->|"on error"| onError
                """);
    }

    @Test
    void branchesForksLoopsAndTriesAreSubgraphsAndJumpsAndErrorsAreEdges() {
        WorkflowDefinition def = WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: shapes, title: Shapes, noun: shape, verb: draw, owner: team-platform, version: 1.0.0 }
                policy: { rbac: [ops] }
                triggers:
                  - event: { topic: shape.created }
                workflow:
                  do:
                    - pick:
                        name: Pick a size
                        switch:
                          - case: { when: "{{ $input.size == 'large' }}", do: [ { large: { set: { cpu: 8 } } } ] }
                          - case: { when: "{{ $input.size == 'none' }}", then: end }
                          - case: { when: "{{ $input.skip }}", then: tidy }
                    - both:
                        name: Both at once
                        fork:
                          branches:
                            - - left:
                                  name: Left
                                  call: provider
                                  with: { provider: vsphere, action: vm.get, input: { id: x } }
                                  compensate:
                                    - undoLeft: { name: Undo left, notify: { channel: "#x", message: undo } }
                            - - each:
                                  name: Each item
                                  for:
                                    each: item
                                    in: "{{ $input.items }}"
                                    do:
                                      - child: { name: Child, run: { workflow: create-vm, version: "^2" } }
                    - guarded:
                        name: Guarded
                        try:
                          do:
                            - risky: { name: Risky, call: http, with: { method: GET, url: "https://example.org" } }
                          catch:
                            do:
                              - giveUp: { name: Give up, raise: { error: Failed, message: stop } }
                    - tidy: { name: Tidy, wait: PT1M }
                """);

        String chart = WorkflowMermaid.of(def);

        wellFormed(chart, "shapes");
        assertThat(chart).isEqualTo("""
                flowchart LR
                  t0(["POST /api/v1/shapes:draw"])
                  t1(["event shape.created"])
                  s1{"Pick a size<br/>switch"}
                  subgraph s1c1 ["case 1"]
                    s2["large<br/>set"]
                  end
                  subgraph s3 ["Both at once<br/>fork"]
                    subgraph s3b1 ["branch 1"]
                      s4["Left<br/>provider: vsphere vm.get"]
                      subgraph s4k ["compensate"]
                        s5["Undo left<br/>notify"]
                      end
                    end
                    subgraph s3b2 ["branch 2"]
                      subgraph s6 ["Each item<br/>for"]
                        s7["Child<br/>run: create-vm ^2"]
                      end
                    end
                  end
                  subgraph s8 ["Guarded<br/>try"]
                    s9["Risky<br/>http"]
                  end
                  subgraph s8c ["catch"]
                    s10["Give up<br/>raise"]
                  end
                  s11["Tidy<br/>wait"]
                  done(["End"])
                  t0 --> s1
                  t1 --> s1
                  s1 -->|"$input.size == 'large'"| s1c1
                  s4 -.->|"compensate"| s4k
                  s2 --> s3
                  s1 -->|"otherwise"| s3
                  s8 -.->|"on error"| s8c
                  s3 --> s8
                  s8 --> s11
                  s1 -->|"$input.size == 'none'"| done
                  s1 -->|"$input.skip"| s11
                """);
    }

    @Test
    void aNameMermaidWouldReadAsSyntaxIsWrittenAsEntityCodesOnOneLine() {
        WorkflowDefinition def = WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: odd, title: Odd, noun: odd, verb: run, owner: team-platform, version: 1.0.0 }
                policy: { rbac: [ops] }
                workflow:
                  do:
                    - weird:
                        name: "He said \\"hi\\" <b>#1</b> | 100% `x` & end"
                        notify: { channel: "#x", message: m }
                    - end:
                        name: "two\\nlines; %%{init: {}}%%"
                        notify: { channel: "#x", message: m }
                """);

        String chart = WorkflowMermaid.of(def);

        wellFormed(chart, "odd");
        assertThat(chart).contains("s1[\"He said #34;hi#34; #60;b#62;#35;1#60;/b#62; #124; 100#37; #96;x#96; #38; end<br/>notify\"]")
                .contains("s2[\"two lines; #37;#37;{init: {}}#37;#37;<br/>notify\"]")
                .contains("s1 --> s2");
        assertThat(WorkflowMermaid.escape(null)).isEmpty();
        assertThat(WorkflowMermaid.condition("{{ " + "x".repeat(80) + " }}")).hasSize(WorkflowMermaid.CONDITION).endsWith("…");
    }

    @Test
    void aChangeRequestPreviewsTheWorkflowItWritesAndNothingElse() {
        String yaml = SampleFixtures.yaml("workflows/team-compute/vm-restart.yaml");

        assertThat(WorkflowMermaid.preview(changeRequest(ChangeRequest.Kind.WORKFLOW, yaml))).contains(WorkflowMermaid.of(WorkflowYaml.parse(yaml)));
        assertThat(WorkflowMermaid.preview(changeRequest(ChangeRequest.Kind.PROVIDER, "apiVersion: iaf.platform/v1\nkind: Provider\n"))).isEmpty();
        assertThat(WorkflowMermaid.preview(changeRequest(ChangeRequest.Kind.WORKFLOW, "metadata: ["))).as("a file that does not parse").isEmpty();
        assertThat(WorkflowMermaid.preview(changeRequest(ChangeRequest.Kind.WORKFLOW, null))).isEmpty();
    }

    static ChangeRequest changeRequest(ChangeRequest.Kind kind, String yaml) {
        return new ChangeRequest("cr_1", kind, "vm-restart", "1.2.1", "change/vm-restart-1.2.1", "Update VM restart 1.2.1", "ana", ChangeRequest.Status.OPEN,
                List.of(), "canary", yaml, "", NOW, null, null, null);
    }
}
