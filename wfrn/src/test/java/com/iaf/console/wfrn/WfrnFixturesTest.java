package com.iaf.console.wfrn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Every entry of every list of the shared fixtures, reported by its name. */
class WfrnFixturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicNode> fixtures() throws IOException {
        JsonNode f = load();
        return Stream.of(
                dynamicContainer("valid", list(f.get("valid"), this::valid)),
                dynamicContainer("invalid", list(f.get("invalid"), this::invalid)),
                dynamicContainer("format", list(f.get("format"), this::format)),
                dynamicContainer("patterns", list(f.get("patterns"), this::pattern)),
                dynamicContainer("invalidPatterns", list(f.get("invalidPatterns"), this::invalidPattern)),
                dynamicContainer("mintedIds", list(f.get("mintedIds"), this::mintedId)));
    }

    private static JsonNode load() throws IOException {
        try (InputStream in = WfrnFixturesTest.class.getResourceAsStream("/wfrn/fixtures.json")) {
            assertThat(in).as("wfrn/fixtures.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    private interface Check {
        void run(JsonNode entry);
    }

    private static List<DynamicNode> list(JsonNode entries, Check check) {
        List<DynamicNode> out = new ArrayList<>();
        assertThat(entries).isNotNull();
        for (JsonNode e : entries) {
            out.add(dynamicTest(e.get("name").asText(), () -> check.run(e)));
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    private void valid(JsonNode e) {
        Wfrn parsed = Wfrn.parse(e.get("input").asText());
        assertThat(parsed).isEqualTo(parts(e.get("parts")));
        assertThat(parsed.format()).isEqualTo(e.get("input").asText());
        assertThat(parts(e.get("parts")).format()).isEqualTo(e.get("input").asText());
    }

    private void invalid(JsonNode e) {
        assertThatThrownBy(() -> Wfrn.parse(e.get("input").asText()))
                .isInstanceOf(WfrnException.class)
                .extracting(x -> ((WfrnException) x).code())
                .isEqualTo(e.get("code").asText());
    }

    private void format(JsonNode e) {
        if (e.has("expected")) {
            assertThat(parts(e.get("parts")).format()).isEqualTo(e.get("expected").asText());
        } else {
            assertThatThrownBy(() -> parts(e.get("parts")))
                    .isInstanceOf(WfrnException.class)
                    .extracting(x -> ((WfrnException) x).code())
                    .isEqualTo(e.get("error").asText());
        }
    }

    private void pattern(JsonNode e) {
        String pattern = e.get("pattern").asText();
        for (JsonNode m : e.get("matches")) {
            assertThat(Wfrn.matches(pattern, m.asText())).as("%s matches %s", pattern, m.asText()).isTrue();
        }
        for (JsonNode m : e.get("misses")) {
            assertThat(Wfrn.matches(pattern, m.asText())).as("%s misses %s", pattern, m.asText()).isFalse();
        }
    }

    private void invalidPattern(JsonNode e) {
        assertThatThrownBy(() -> WfrnPattern.parse(e.get("pattern").asText()))
                .isInstanceOf(WfrnException.class)
                .extracting(x -> ((WfrnException) x).code())
                .isEqualTo(e.get("code").asText());
    }

    private void mintedId(JsonNode e) {
        String type = e.get("type").asText();
        String id = e.get("id").asText();
        if (e.has("parsed")) {
            assertThat(MintedIds.mint(type, e.get("ulid").asText())).isEqualTo(id);
            MintedIds.MintedId parsed = MintedIds.parse(type, id);
            assertThat(parsed.type()).isEqualTo(e.get("parsed").get("type").asText());
            assertThat(parsed.ulid()).isEqualTo(e.get("parsed").get("ulid").asText());
            assertThat(MintedIds.parse(id)).isEqualTo(parsed);
        } else {
            assertThatThrownBy(() -> MintedIds.parse(type, id))
                    .isInstanceOf(WfrnException.class)
                    .extracting(x -> ((WfrnException) x).code())
                    .isEqualTo(e.get("error").asText());
        }
    }

    private static Wfrn parts(JsonNode p) {
        return new Wfrn(p.get("partition").asText(), p.get("service").asText(), p.get("region").asText(), p.get("account").asText(),
                p.get("type").asText(), p.get("id").asText());
    }
}
