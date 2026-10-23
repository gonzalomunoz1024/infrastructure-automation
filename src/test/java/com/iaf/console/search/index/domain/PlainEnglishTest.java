package com.iaf.console.search.index.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.search.index.domain.Interpretation.Confidence;

/** Plain English read as a list's filter: what each kind of word becomes, and how sure the reading is. */
class PlainEnglishTest {

    /** A Friday; its week began on Monday 2026-10-19. */
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final List<ResourceTypeHints> TYPES = List.of(Fixtures.VM_HINTS, Fixtures.DB_HINTS);
    private static final AccountVocabulary ACCOUNT = AccountVocabulary.of(Map.of("vm", 3L, "database", 2L), Map.of("global", 4L, "eu-west", 1L),
            Map.of("env=prod", 3L, "env=dev", 1L, "team=web", 2L, "team=Payments", 1L, "owner=dana", 1L));

    private static PlainEnglish.Reading read(String text) {
        return PlainEnglish.read(text, TYPES, ACCOUNT, NOW, null);
    }

    private static PlainEnglish.Reading within(String type, String text) {
        return PlainEnglish.read(text, TYPES, ACCOUNT, NOW, type);
    }

    @Test
    void aSentenceBecomesTypeStateTagsTimeAndSort() {
        PlainEnglish.Reading r = read("Failed VMs tagged env=prod created this week, newest first");
        assertThat(r.type()).isEqualTo("vm");
        assertThat(r.state()).isEqualTo(ResourceState.FAILED);
        assertThat(r.tags()).containsExactly(Map.entry("env", "prod"));
        assertThat(r.times()).extracting(PlainEnglish.Time::term).containsExactly("created:>=2026-10-19");
        assertThat(r.sort()).isEqualTo(SearchQuery.Sort.CREATED_DESC);
        assertThat(r.q()).isEqualTo("type:vm state:failed tag:env=prod created:>=2026-10-19");
        assertThat(r.filterQ()).as("only what the list's filters cannot hold").isEqualTo("created:>=2026-10-19");
        assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(r.explanation()).isEqualTo("Virtual machines that failed, tagged env=prod, created this week (since 2026-10-19), newest first.");
        Interpretation answer = r.answer(4);
        assertThat(answer.type()).isEqualTo("vm");
        assertThat(answer.filter()).isEqualTo(new Interpretation.Filter("created:>=2026-10-19", "failed", null, null, Map.of("env", "prod")));
        assertThat(answer.sort()).isEqualTo("-created");
        assertThat(answer.unparsed()).isNull();
        assertThat(answer.total()).isEqualTo(4);
        QueryParser.parse(r.q(), null, NOW);
    }

    @Test
    void typesByNamePluralTitleOrATypoAway() {
        assertThat(read("vm").type()).isEqualTo("vm");
        assertThat(read("virtual machines").type()).isEqualTo("vm");
        assertThat(read("a virtual machine").type()).isEqualTo("vm");
        assertThat(read("databases").type()).isEqualTo("database");
        PlainEnglish.Reading typo = read("databse");
        assertThat(typo.type()).isEqualTo("database");
        assertThat(typo.confidence()).as("a typo was fixed").isEqualTo(Confidence.MEDIUM);
        assertThat(read("type:database").type()).isEqualTo("database");
        assertThat(read("vms").explanation()).isEqualTo("Virtual machines.");
        assertThat(read("failed").explanation()).isEqualTo("Resources that failed.");
    }

    @Test
    void statesByTheirEverydayNames() {
        assertThat(read("broken vms").state()).isEqualTo(ResourceState.FAILED);
        assertThat(read("running vms").state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(read("vms being created").state()).isEqualTo(ResourceState.PENDING);
        assertThat(read("databases being deleted").state()).isEqualTo(ResourceState.DELETING);
        assertThat(read("vms in an error state").state()).isEqualTo(ResourceState.FAILED);
        assertThat(read("how many active databases are there?").q()).isEqualTo("type:database state:active");
    }

    @Test
    void driftBothWays() {
        assertThat(read("drifted vms").drift()).isTrue();
        assertThat(read("vms changed outside the console").drift()).isTrue();
        assertThat(read("vms changed outside the console").guesses()).isEmpty();
        assertThat(read("databases out of sync").drift()).isTrue();
        assertThat(read("databases without drift").drift()).isFalse();
        assertThat(read("vms in sync").drift()).isFalse();
        assertThat(read("drift-free vms").drift()).isFalse();
    }

    @Test
    void tagsByNameValueOrBoth() {
        assertThat(read("team payments databases").tags()).as("a known name and one of its values, spelled as the resources spell it")
                .containsExactly(Map.entry("team", "Payments"));
        assertThat(read("vms tagged team web").tags()).containsExactly(Map.entry("team", "web"));
        assertThat(read("vms tagged team").tags()).as("a name alone: has the tag").containsExactly(Map.entry("team", ""));
        assertThat(read("vms tagged team").explanation()).isEqualTo("Virtual machines with a team tag.");
        assertThat(read("vms tagged dana").tags()).as("after tagged, a value only one name holds").containsExactly(Map.entry("owner", "dana"));
        assertThat(read("env:prod vms").tags()).containsExactly(Map.entry("env", "prod"));
        assertThat(read("vms with tag cost-centre=4711").tags()).as("any name=value, known or not").containsExactly(Map.entry("cost-centre", "4711"));
        assertThat(read("tag:Env=Prod").tags()).as("a typed name is lowercased, its value kept").containsExactly(Map.entry("env", "Prod"));
        PlainEnglish.Reading spaced = read("vms tagged \"owner=Dana Scully\"");
        assertThat(spaced.phrases()).as("quotes keep a phrase as free text").containsExactly("owner=Dana Scully");
        PlainEnglish.Reading prod = read("prod vms");
        assertThat(prod.tags()).as("a value alone is free text, which matches tags too").isEmpty();
        assertThat(prod.guesses()).containsExactly("prod");
        assertThat(prod.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void regionsOfTheAccount() {
        assertThat(read("databases in eu-west").region()).isEqualTo("eu-west");
        assertThat(read("vms in region global").region()).isEqualTo("global");
        assertThat(read("vms in the global region").region()).isEqualTo("global");
        assertThat(read("vms in region us-east-9").region()).as("named as a region, even one with no resource yet").isEqualTo("us-east-9");
        PlainEnglish.Reading in = read("vms in prod");
        assertThat(in.region()).isNull();
        assertThat(in.guesses()).containsExactly("prod");
    }

    @Test
    void timesInEnglish() {
        assertThat(time("vms created today")).isEqualTo("created:2026-10-23");
        assertThat(time("vms created yesterday")).isEqualTo("created:2026-10-22");
        assertThat(time("vms created in the last 24 hours")).isEqualTo("created:24h");
        assertThat(time("vms from the past 3 days")).isEqualTo("created:3d");
        assertThat(time("vms created last week")).isEqualTo("created:1w");
        assertThat(time("vms created last month")).as("a month is 30 days back").isEqualTo("created:30d");
        assertThat(time("vms created within 2 weeks")).isEqualTo("created:2w");
        assertThat(time("vms created 2 days ago")).isEqualTo("created:2d");
        assertThat(time("vms created a day ago")).isEqualTo("created:1d");
        assertThat(time("vms older than 90 days")).isEqualTo("created:<90d");
        assertThat(time("vms created more than three months ago")).isEqualTo("created:<90d");
        assertThat(time("vms created less than 2 hours ago")).isEqualTo("created:2h");
        assertThat(time("vms created since october 1")).isEqualTo("created:>=2026-10-01");
        assertThat(time("vms created after 2026-10-01")).isEqualTo("created:>2026-10-01");
        assertThat(time("vms created before 1st October 2026")).isEqualTo("created:<2026-10-01");
        assertThat(time("vms created on oct 5")).isEqualTo("created:2026-10-05");
        assertThat(time("vms created between 2026-10-07 and 2026-10-01")).as("in either order").isEqualTo("created:2026-10-01..2026-10-07");
        assertThat(time("vms created from oct 1 to oct 7")).isEqualTo("created:2026-10-01..2026-10-07");
        assertThat(time("vms created between oct 1 and oct 7")).isEqualTo("created:2026-10-01..2026-10-07");
        assertThat(time("vms created in october")).isEqualTo("created:2026-10-01..2026-10-31");
        assertThat(time("vms created in december")).as("the last December that has passed").isEqualTo("created:2025-12-01..2025-12-31");
        assertThat(time("vms created this month")).isEqualTo("created:>=2026-10-01");
        assertThat(time("vms updated in the past hour")).isEqualTo("updated:1h");
        assertThat(time("vms changed since yesterday")).isEqualTo("updated:>=2026-10-22");
        assertThat(time("vms not updated in 30 days")).isEqualTo("updated:<30d");
        assertThat(time("vms not changed since 2026-10-01")).isEqualTo("updated:<2026-10-01");
        assertThat(time("vms not touched this week")).isEqualTo("updated:<2026-10-19");
        assertThat(read("vms not updated in 30 days").explanation()).isEqualTo("Virtual machines updated more than 30 days ago.");
        assertThat(read("vms created in may").times()).as("in may is too often the verb").isEmpty();
        assertThat(read("two vms").times()).isEmpty();
        PlainEnglish.Reading both = read("vms created after 2026-10-01 and before 2026-10-10");
        assertThat(both.times()).extracting(PlainEnglish.Time::term).containsExactly("created:>2026-10-01", "created:<2026-10-10");
        QueryParser.parse(both.q(), null, NOW);
    }

    private static String time(String text) {
        PlainEnglish.Reading r = read(text);
        assertThat(r.times()).as(text).hasSize(1);
        assertThat(r.guesses()).as(text).isEmpty();
        assertThat(r.unparsed()).as(text).isEmpty();
        QueryParser.parse(r.q(), null, NOW);
        return r.times().get(0).term();
    }

    @Test
    void sorts() {
        assertThat(read("newest vms").sort()).isEqualTo(SearchQuery.Sort.CREATED_DESC);
        assertThat(read("oldest databases first").sort()).isEqualTo(SearchQuery.Sort.CREATED);
        assertThat(read("recently updated vms").sort()).isEqualTo(SearchQuery.Sort.UPDATED_DESC);
        assertThat(read("vms updated recently").sort()).isEqualTo(SearchQuery.Sort.UPDATED_DESC);
        assertThat(read("vms least recently changed").sort()).isEqualTo(SearchQuery.Sort.UPDATED);
        assertThat(read("vms sorted by name").sort()).isEqualTo(SearchQuery.Sort.NAME);
        assertThat(read("vms z to a").sort()).isEqualTo(SearchQuery.Sort.NAME_DESC);
        assertThat(read("new vms this week").times()).as("new sorts; the time still filters").extracting(PlainEnglish.Time::term)
                .containsExactly("created:>=2026-10-19");
    }

    @Test
    void theQuerySyntaxReadsAsItself() {
        PlainEnglish.Reading r = read("state:failed type:vm drift:true region:global wfrn:wf:compute:global:123456789012:vm/* created:7d");
        assertThat(r.q()).isEqualTo("type:vm state:failed region:global drift:true created:7d wfrn:wf:compute:global:123456789012:vm/*");
        assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(r.times().get(0).phrase()).isEqualTo("in the last 7 days");
        PlainEnglish.Reading bad = read("state:purple vms");
        assertThat(bad.unparsed()).containsExactly("state:purple");
        assertThat(bad.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void whatCannotBeAFilterIsLeftOutAndSaid() {
        PlainEnglish.Reading union = read("vms or databases");
        assertThat(union.type()).isEqualTo("vm");
        assertThat(union.unparsed()).containsExactly("or", "databases");
        assertThat(union.confidence()).isEqualTo(Confidence.LOW);
        PlainEnglish.Reading not = read("not failed vms");
        assertThat(not.state()).isNull();
        assertThat(not.unparsed()).containsExactly("not failed");
        assertThat(read("failed or pending vms").unparsed()).containsExactly("or", "pending");
        assertThat(read("newest vms by name").unparsed()).as("a second sort").containsExactly("by name");
    }

    @Test
    void theListThePersonIsOnHoldsTheType() {
        PlainEnglish.Reading same = within("vm", "failed vms");
        assertThat(same.type()).isEqualTo("vm");
        assertThat(same.confidence()).isEqualTo(Confidence.HIGH);
        PlainEnglish.Reading other = within("vm", "databases that failed");
        assertThat(other.type()).isEqualTo("vm");
        assertThat(other.state()).isEqualTo(ResourceState.FAILED);
        assertThat(other.unparsed()).containsExactly("databases");
        assertThat(other.confidence()).isEqualTo(Confidence.LOW);
        assertThat(within("vm", "web").confidence()).as("only free text").isEqualTo(Confidence.LOW);
        assertThat(within("vm", "web").explanation()).isEqualTo("Virtual machines matching “web”.");
        assertThatThrownBy(() -> within("Bad Type", "x")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void guessesAndPhrasesAreFreeText() {
        PlainEnglish.Reading r = read("vms owned by dana named \"web server\"");
        assertThat(r.type()).isEqualTo("vm");
        assertThat(r.guesses()).containsExactly("dana");
        assertThat(r.phrases()).containsExactly("web server");
        assertThat(r.q()).isEqualTo("type:vm \"web server\" dana");
        assertThat(r.confidence()).isEqualTo(Confidence.MEDIUM);
        PlainEnglish.Reading without = r.withoutGuesses();
        assertThat(without.guesses()).isEmpty();
        assertThat(without.unparsed()).containsExactly("dana");
        assertThat(without.q()).isEqualTo("type:vm \"web server\"");
        assertThat(without.confidence()).isEqualTo(Confidence.LOW);
        assertThat(read("show me everything").q()).isEmpty();
        assertThat(read("show me everything").confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(read("zebra").confidence()).isEqualTo(Confidence.LOW);
        assertThat(read("“curly quotes” vms").phrases()).containsExactly("curly quotes");
        assertThat(PlainEnglish.words("a, (b) \"c d")).extracting(PlainEnglish.Word::raw).containsExactly("a", "b", "c d");
    }
}
