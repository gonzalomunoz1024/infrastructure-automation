package com.iaf.platform.common.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.demo.DemoReleases.Outcome;
import com.iaf.platform.common.demo.DemoReleases.Stage;
import com.iaf.platform.common.demo.DemoReleases.Story;

/** The release stories every demo adapter follows, and how they route the jobs of the seeded history. */
class DemoReleasesTest {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    /** A random that always draws {@code value} from {@code nextInt(100)}. */
    static Random drawing(int value) {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }

    @Test
    void theStoriesAreConsistentWithEachOther() {
        assertThat(DemoReleases.STORIES).extracting(Story::id).doesNotHaveDuplicates();
        for (Story s : DemoReleases.STORIES) {
            assertThat(s.from()).as(s.id()).isNotEqualTo(s.to());
            assertThat(s.canary()).as(s.id()).isEqualTo(s.strategy().equals(DemoReleases.CANARY));
            assertThat(s.active()).as(s.id()).isEqualTo(s.outcome() == Outcome.CANARY);
            if (!s.active()) {
                assertThat(s.endedAgo()).as(s.id()).isLessThanOrEqualTo(s.startedAgo());
            }
            if (s.failure() != null) {
                assertThat(s.outcome()).as(s.id()).isEqualTo(Outcome.ROLLED_BACK);
                assertThat(s.failure().errorClass()).as(s.id()).matches("[a-z_]+");
            }
            if (!s.canary()) {
                assertThat(s.jobs()).as(s.id()).isZero();
                assertThat(s.outcome()).as(s.id()).isEqualTo(Outcome.PROMOTED);
            }
            Duration previous = null;
            for (Stage stage : s.stages()) {
                assertThat(stage.percent()).as(s.id()).isBetween(1, 100);
                assertThat(stage.ago()).as(s.id()).isLessThanOrEqualTo(s.startedAgo());
                if (previous != null) {
                    assertThat(stage.ago()).as(s.id() + " stages run oldest first").isLessThan(previous);
                }
                previous = stage.ago();
            }
        }
    }

    @Test
    void theStoriesOfAWorkflowComeOldestFirst() {
        List<Story> mongo = DemoReleases.of("mongodb-upgrade");
        assertThat(mongo).extracting(Story::id).containsExactly("rel_demo_mongo_rollback", "rel_demo_mongo_republish");
        assertThat(DemoReleases.of("create-database")).isEmpty();
    }

    @Test
    void theStoryRoutingAtATimeIsTheLastOneStartedByThen() {
        assertThat(DemoReleases.at("mongodb-upgrade", NOW.minus(Duration.ofDays(9)), NOW)).isEmpty();
        assertThat(DemoReleases.at("mongodb-upgrade", NOW.minus(Duration.ofDays(7)), NOW)).map(Story::id).contains("rel_demo_mongo_rollback");
        assertThat(DemoReleases.at("mongodb-upgrade", NOW.minus(Duration.ofDays(5)), NOW)).map(Story::id).contains("rel_demo_mongo_republish");
        assertThat(DemoReleases.at("mongodb-upgrade", NOW.minus(Duration.ofDays(6)), NOW)).map(Story::id)
                .as("a story starts routing the moment it starts").contains("rel_demo_mongo_republish");
    }

    @Test
    void jobsBeforeTheFirstReleaseRanTheVersionItStartedFrom() {
        assertThat(DemoReleases.versionAt("mongodb-upgrade", NOW.minus(Duration.ofDays(12)), NOW, drawing(0))).contains("3.0.2");
        assertThat(DemoReleases.versionAt("vm-restart", NOW.minus(Duration.ofHours(3)), NOW, drawing(0))).contains("1.1.0");
        assertThat(DemoReleases.versionAt("create-database", NOW.minus(Duration.ofHours(3)), NOW, drawing(0)))
                .as("no story, no opinion").isEmpty();
    }

    @Test
    void aCanarySplitsTrafficByItsStageShareWhileItRuns() {
        Instant during = NOW.minus(Duration.ofMinutes(40));
        assertThat(DemoReleases.versionAt("vm-restart", during, NOW, drawing(9))).as("a draw under 10 lands on the candidate").contains("1.2.0");
        assertThat(DemoReleases.versionAt("vm-restart", during, NOW, drawing(10))).as("a draw of 10 or more stays current").contains("1.1.0");
        Story vmRestart = DemoReleases.of("vm-restart").get(0);
        assertThat(vmRestart.splitting(during, NOW)).isTrue();
        assertThat(vmRestart.splitting(NOW.minus(Duration.ofHours(2)), NOW)).isFalse();
        assertThat(vmRestart.stageAt(during, NOW).percent()).isEqualTo(10);
        assertThat(vmRestart.endedAt(NOW)).isNull();
    }

    @Test
    void aFinishedReleaseLeavesItsSettledVersionInPlace() {
        // rolled back after 102 minutes: the current version stays until the republish two days later
        Instant afterRollback = NOW.minus(Duration.ofDays(7));
        assertThat(DemoReleases.versionAt("mongodb-upgrade", afterRollback, NOW, drawing(0))).contains("3.0.2");
        Story rollback = DemoReleases.of("mongodb-upgrade").get(0);
        assertThat(rollback.splitting(afterRollback, NOW)).isFalse();
        assertThat(rollback.settled()).isEqualTo("3.0.2");
        assertThat(rollback.endedAt(NOW)).isEqualTo(NOW.minus(Duration.ofDays(8)).plus(Duration.ofMinutes(102)));
        // republished to everyone six days ago: every job since runs the new version
        assertThat(DemoReleases.versionAt("mongodb-upgrade", NOW.minus(Duration.ofDays(5)), NOW, drawing(0))).contains("3.1.0");
        Story republish = DemoReleases.of("mongodb-upgrade").get(1);
        assertThat(republish.canary()).isFalse();
        assertThat(republish.stageAt(NOW, NOW)).isNull();
        assertThat(republish.lastStage()).isNull();
        // promoted by hand after five hours: the candidate is the version since
        assertThat(DemoReleases.versionAt("create-vm", NOW.minus(Duration.ofHours(20)), NOW, drawing(99))).contains("2.0.1");
        assertThat(DemoReleases.versionAt("create-vm", NOW.minus(Duration.ofHours(28)), NOW, drawing(99))).contains("1.9.0");
    }

    @Test
    void aMultiStageCanaryReportsTheStageRunningAtATime() {
        Story s = new Story("rel_test", "wf", "1.0.0", "1.1.0", DemoReleases.CANARY, Outcome.CANARY, Duration.ofHours(10),
                List.of(new Stage(Duration.ofHours(10), 10), new Stage(Duration.ofHours(4), 50)), null, "ana", null, null, 0);
        assertThat(s.stageAt(NOW.minus(Duration.ofHours(6)), NOW).percent()).isEqualTo(10);
        assertThat(s.stageAt(NOW.minus(Duration.ofHours(4)), NOW).percent()).isEqualTo(50);
        assertThat(s.stageAt(NOW.minus(Duration.ofHours(1)), NOW).percent()).isEqualTo(50);
        assertThat(s.lastStage().percent()).isEqualTo(50);
        assertThat(DemoReleases.at("wf", NOW, NOW)).as("only the shared stories route").isEmpty();
    }
}
