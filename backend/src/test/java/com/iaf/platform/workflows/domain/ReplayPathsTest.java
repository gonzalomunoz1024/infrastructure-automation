package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.workflows.domain.ReplayPaths.Difference;
import com.iaf.platform.workflows.domain.ReplayPaths.Ran;
import com.iaf.platform.workflows.domain.ReplayPaths.Taken;

class ReplayPathsTest {

    static final JsonNodeFactory F = JsonNodeFactory.instance;

    static final WorkflowDefinition DEF = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: fan-out, title: Fan out, noun: fan, verb: out, owner: team-platform, version: 1.0.0 }
            policy: { rbac: [ops] }
            workflow:
              do:
                - look:
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: x } }
                - both:
                    fork:
                      branches:
                        - - left1: { notify: { channel: "#x", message: a } }
                          - left2: { notify: { channel: "#x", message: b } }
                        - - right1: { notify: { channel: "#x", message: c } }
                - guarded:
                    try:
                      do:
                        - risky:
                            call: provider
                            with: { provider: vsphere, action: vm.restart, input: { id: x } }
                      catch:
                        do:
                          - recover: { notify: { channel: "#x", message: d } }
                - done: { notify: { channel: "#x", message: e } }
              catch:
                do:
                  - alert: { notify: { channel: "#x", message: f } }
            """);

    static Ran ran(String id) {
        return new Ran(id, "succeeded", null);
    }

    static Ran provider(String id, String action) {
        return new Ran(id, "succeeded", F.objectNode().put("provider", "vsphere").put("action", action));
    }

    static List<Ran> walk(String... ids) {
        List<Ran> out = new ArrayList<>();
        for (String id : ids) {
            out.add(switch (id) {
                case "look" -> provider("look", "vm.get");
                case "risky" -> provider("risky", "vm.restart");
                default -> ran(id);
            });
        }
        return out;
    }

    static Map<String, List<Taken>> path(List<Ran> steps) {
        return ReplayPaths.path(steps, DEF);
    }

    @Test
    void aPathIsTheStepsTakenWithTheirProviderActionsLinedUpByForkBranch() {
        List<Ran> steps = new ArrayList<>(walk("look", "both", "right1", "left1", "left2", "guarded", "risky", "done"));
        steps.add(new Ran("skippedOne", "skipped", null));
        steps.add(new Ran("both/childStep", "succeeded", null));
        steps.add(ran("look"));

        Map<String, List<Taken>> path = path(steps);

        assertThat(path).containsOnlyKeys(ReplayPaths.MAIN, "both#0", "both#1");
        assertThat(path.get(ReplayPaths.MAIN)).containsExactly(new Taken("look", "vsphere vm.get"), new Taken("both", null), new Taken("guarded", null),
                new Taken("risky", "vsphere vm.restart"), new Taken("done", null));
        assertThat(path.get("both#0")).extracting(Taken::step).containsExactly("left1", "left2");
        assertThat(path.get("both#1")).extracting(Taken::step).containsExactly("right1");
        // branches that ran in another interleaving are the same path
        assertThat(ReplayPaths.compare(path, path(walk("look", "both", "left1", "right1", "left2", "guarded", "risky", "done")), true, "the job", "1.1.0"))
                .isEmpty();
        // an action as written stands in when the run recorded no input
        assertThat(ReplayPaths.path(List.of(ran("look")), DEF).get(ReplayPaths.MAIN)).containsExactly(new Taken("look", "vsphere vm.get"));
    }

    @Test
    void theFirstStepWherePathsPartIsTheDifference() {
        Map<String, List<Taken>> job = path(walk("look", "both", "left1", "left2", "right1", "guarded", "risky", "done"));

        assertThat(ReplayPaths.compare(job, path(walk("look", "guarded", "risky", "both", "left1", "left2", "right1", "done")), true, "the job", "1.1.0"))
                .contains(new Difference("both", "the job ran both where 1.1.0 runs guarded"));
        List<Ran> otherAction = walk("look", "both", "left1", "left2", "right1", "guarded", "risky", "done");
        otherAction.set(6, provider("risky", "vm.reset"));
        assertThat(ReplayPaths.compare(job, path(otherAction), true, "the job", "1.1.0"))
                .contains(new Difference("risky", "the job ran risky (vsphere vm.restart) where 1.1.0 runs risky (vsphere vm.reset)"));
        assertThat(ReplayPaths.compare(job, path(walk("look", "both", "left1", "left2", "right1", "guarded", "risky")), true, "the job", "1.1.0"))
                .contains(new Difference("done", "the job ran done; 1.1.0 does not run it after risky"));
        assertThat(ReplayPaths.compare(job, path(walk("look", "both", "left1", "left2", "right1", "guarded", "risky", "done", "extra")), true, "the job", "1.1.0"))
                .contains(new Difference("extra", "1.1.0 runs extra after done; the job did not"));
        assertThat(ReplayPaths.compare(job, path(walk("look", "both", "left1", "right1", "guarded", "risky", "done")), true, "the job", "1.1.0"))
                .as("a branch that lost a step").contains(new Difference("left2", "the job ran left2; 1.1.0 does not run it after left1"));
    }

    @Test
    void aJobThatStoppedEarlyIsComparedAsFarAsItRanWithoutWhatOnlyRunsOnAFailure() {
        List<Ran> failed = walk("look", "both", "left1", "left2", "right1", "guarded");
        failed.add(new Ran("risky", "failed", F.objectNode().put("provider", "vsphere").put("action", "vm.restart")));
        failed.add(ran("recover"));
        failed.add(ran("alert"));
        Map<String, List<Taken>> job = path(failed);
        assertThat(job.get(ReplayPaths.MAIN)).extracting(Taken::step).containsExactly("look", "both", "guarded", "risky");

        Map<String, List<Taken>> full = path(walk("look", "both", "left1", "left2", "right1", "guarded", "risky", "done"));
        assertThat(ReplayPaths.compare(job, full, false, "the job", "1.1.0")).isEmpty();
        assertThat(ReplayPaths.compare(job, full, true, "the job", "1.1.0")).as("as a finished job it would part at done").isPresent();
        assertThat(ReplayPaths.compare(job, path(walk("look", "guarded", "risky")), false, "the job", "1.1.0"))
                .contains(new Difference("both", "the job ran both where 1.1.0 runs guarded"));
        assertThat(ReplayPaths.compare(path(List.of()), full, false, "the job", "1.1.0")).as("nothing ran, nothing to part from").isEmpty();
        assertThat(ReplayPaths.path(List.of(new Ran("look", "pending", null)), DEF).get(ReplayPaths.MAIN)).isEmpty();
    }
}
