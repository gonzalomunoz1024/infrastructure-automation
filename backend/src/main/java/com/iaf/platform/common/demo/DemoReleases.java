package com.iaf.platform.common.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The release history the demo adapters share ({@code platform.demo.seed-history}), so every
 * view tells the same story: the workflows slice keeps the version each release replaced and the
 * change request that published the new one, the runs slice routes its seeded jobs by it, and
 * the releases slice records the releases, judged on those jobs. Durations are relative to the
 * start of the process. The names are sample content; a story whose workflow is not in the
 * content dir is skipped by every consumer.
 */
public final class DemoReleases {

    public static final String CANARY = "canary";
    public static final String ALL = "all";

    public enum Outcome {
        /** Still running at its last stage. */
        CANARY,
        PROMOTED,
        ROLLED_BACK
    }

    /** One canary stage: how long ago it started and the share of new jobs the candidate took. */
    public record Stage(Duration ago, int percent) {
    }

    /** The error a candidate ran into: its error class, as a step reports it, and the message. */
    public record Failure(String errorClass, String message) {
    }

    /**
     * One release of {@code workflow} from {@code from} to {@code to}. {@code stages} is empty for
     * a publish to everyone; {@code endedAgo} is null while the canary runs. {@code endedBy} is
     * who took the final decision, null for the gates. {@code failure} is the error the candidate
     * ran into when the error-types gate rolled it back. {@code jobs} is how many jobs
     * the seeded history adds inside the canary, on top of the workflow's usual traffic.
     */
    public record Story(String id, String workflow, String from, String to, String strategy, Outcome outcome, Duration startedAgo,
            List<Stage> stages, Duration endedAgo, String startedBy, String endedBy, Failure failure, int jobs) {

        public Instant startedAt(Instant now) {
            return now.minus(startedAgo);
        }

        /** Null while the release is running. */
        public Instant endedAt(Instant now) {
            return endedAgo == null ? null : now.minus(endedAgo);
        }

        public boolean active() {
            return endedAgo == null;
        }

        public boolean canary() {
            return !stages.isEmpty();
        }

        /** The stage running at {@code at}, or the last one; null for a publish to everyone. */
        public Stage stageAt(Instant at, Instant now) {
            Stage current = null;
            for (Stage s : stages) {
                if (current == null || !now.minus(s.ago()).isAfter(at)) {
                    current = s;
                }
            }
            return current;
        }

        public Stage lastStage() {
            return stages.isEmpty() ? null : stages.get(stages.size() - 1);
        }

        /** Whether {@code at} falls inside the canary, while it split traffic between the two versions. */
        public boolean splitting(Instant at, Instant now) {
            Instant end = endedAt(now);
            return canary() && !at.isBefore(startedAt(now)) && (end == null || at.isBefore(end));
        }

        /** The version left in place once the release ended. */
        public String settled() {
            return outcome == Outcome.ROLLED_BACK ? from : to;
        }
    }

    /**
     * mongodb-upgrade 3.1.0 met an error the current version never had and was rolled back by the
     * gates, then went out to everyone two days later once the provider was fixed; create-vm 2.0.1
     * was promoted by its owner after five hours at 10%; vm-restart 1.2.0 is a canary at 10%.
     */
    public static final List<Story> STORIES = List.of(
            new Story("rel_demo_mongo_rollback", "mongodb-upgrade", "3.0.2", "3.1.0", CANARY, Outcome.ROLLED_BACK, Duration.ofDays(8),
                    List.of(new Stage(Duration.ofDays(8), 10)), Duration.ofDays(8).minus(Duration.ofMinutes(102)), "ana", null,
                    new Failure("replica_set_not_ready", "The replica set did not elect a primary within 10m of the upgrade"), 8),
            new Story("rel_demo_mongo_republish", "mongodb-upgrade", "3.0.2", "3.1.0", ALL, Outcome.PROMOTED, Duration.ofDays(6),
                    List.of(), Duration.ofDays(6), "ana", "ana", null, 0),
            new Story("rel_demo_create_vm", "create-vm", "1.9.0", "2.0.1", CANARY, Outcome.PROMOTED, Duration.ofHours(31),
                    List.of(new Stage(Duration.ofHours(31), 10)), Duration.ofHours(26), "gonzalo", "gonzalo", null, 10),
            new Story("rel_demo_vm_restart", "vm-restart", "1.1.0", "1.2.0", CANARY, Outcome.CANARY, Duration.ofMinutes(80),
                    List.of(new Stage(Duration.ofMinutes(80), 10)), null, "mateo", null, null, 12));

    private DemoReleases() {
    }

    /** The stories of one workflow, oldest first. */
    public static List<Story> of(String workflow) {
        return STORIES.stream().filter(s -> s.workflow().equals(workflow))
                .sorted(Comparator.comparing(Story::startedAgo).reversed())
                .toList();
    }

    /** The story that was routing jobs of {@code workflow} at {@code at}: the last one started by then. */
    public static Optional<Story> at(String workflow, Instant at, Instant now) {
        Story found = null;
        for (Story s : of(workflow)) {
            if (!s.startedAt(now).isAfter(at)) {
                found = s;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * The version a job of {@code workflow} started at {@code at} ran, or empty when no story
     * covers the workflow: the first story's {@code from} before it, the candidate for the stage's
     * share of jobs while a canary splits traffic (drawn from {@code rnd}), and whatever the
     * release left in place after it ended.
     */
    public static Optional<String> versionAt(String workflow, Instant at, Instant now, Random rnd) {
        List<Story> stories = of(workflow);
        if (stories.isEmpty()) {
            return Optional.empty();
        }
        Optional<Story> story = at(workflow, at, now);
        if (story.isEmpty()) {
            return Optional.of(stories.get(0).from());
        }
        Story s = story.get();
        if (s.splitting(at, now)) {
            return Optional.of(rnd.nextInt(100) < s.stageAt(at, now).percent() ? s.to() : s.from());
        }
        return Optional.of(s.settled());
    }

}
