package com.iaf.console.identity.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;

/** The small shared pieces: ids, If-Match versions, names and paging limits. */
class CommonTest {

    @Test
    void idsHaveTheirPrefixesAndAccountIdsTwelveDigits() {
        Ids ids = new Ids(Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC), new Random(7));
        assertThat(ids.user()).startsWith("user_").hasSize(31);
        assertThat(ids.group()).startsWith("grp_");
        assertThat(ids.role()).startsWith("role_");
        assertThat(ids.policy()).startsWith("pol_");
        assertThat(ids.accessKey()).startsWith("ck_");
        assertThat(ids.quotaRequest()).startsWith("qreq_");
        assertThat(ids.accessRequest()).startsWith("areq_");
        assertThat(ids.event()).startsWith("evt_");
        for (int i = 0; i < 100; i++) {
            assertThat(ids.accountId()).matches("^[1-9][0-9]{11}$");
        }
        assertThat(ids.secret()).hasSize(43).matches("^[A-Za-z0-9_-]+$");
        assertThat(ids.secret()).isNotEqualTo(ids.secret());
        assertThat(new Ids(Clock.systemUTC(), new Random(7)).user()).isNotEqualTo(new Ids(Clock.systemUTC(), new Random(8)).user());
    }

    @Test
    void ifMatchReadsVersionsAndConflictsOnAStaleOne() {
        assertThat(Versions.ifMatch(null)).isNull();
        assertThat(Versions.ifMatch(" ")).isNull();
        assertThat(Versions.ifMatch("3")).isEqualTo(3L);
        assertThat(Versions.ifMatch("\"3\"")).isEqualTo(3L);
        assertThat(Versions.ifMatch("W/\"3\"")).isEqualTo(3L);
        assertThatThrownBy(() -> Versions.ifMatch("abc")).isInstanceOf(IllegalArgumentException.class);
        Versions.check(null, 5, "the thing");
        Versions.check(5L, 5, "the thing");
        assertThatThrownBy(() -> Versions.check(4L, 5, "the thing")).isInstanceOf(ConflictException.class).hasMessageContaining("moved on");
    }

    @Test
    void namesAndLimits() {
        assertThat(Names.isName("sre-team")).isTrue();
        assertThat(Names.isName("SRE")).isFalse();
        assertThat(Names.isName("a")).isFalse();
        assertThat(Names.isEmail("dana@example.com")).isTrue();
        assertThat(Names.isEmail("dana@")).isFalse();
        assertThat(Names.normalizeEmail(" Dana@Example.com ")).isEqualTo("dana@example.com");
        assertThat(Paging.limit(null)).isEqualTo(50);
        assertThat(Paging.limit(7)).isEqualTo(7);
        assertThat(Paging.limit(501)).isEqualTo(200);
        assertThatThrownBy(() -> Paging.limit(0)).isInstanceOf(IllegalArgumentException.class);
        Names.Check check = new Names.Check().require(false, "name", "is required").require(true, "other", "fine");
        assertThat(check.ok()).isFalse();
        assertThat(check.problems()).hasSize(1);
        assertThatThrownBy(() -> check.done("the thing")).isInstanceOf(InvalidInputException.class).hasMessageContaining("the thing is not valid");
        new Names.Check().done("nothing wrong");
    }
}
