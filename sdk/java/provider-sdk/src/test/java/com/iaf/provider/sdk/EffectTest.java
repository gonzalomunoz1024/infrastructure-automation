package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

class EffectTest {

    @Test
    void theProviderFileSpellsTheEffectsAsThePlatformDoes() {
        assertThat(Effect.values()).extracting(Effect::key).containsExactly("read", "create", "change", "delete");
    }

    @Test
    void anActionThatChangesSomethingWritesChange() throws Exception {
        Provider p = Provider.named("probe").owner("team-platform").version("1.0.0")
                .action(Action.json("item.rename", (ctx, in) -> in).effect(Effect.CHANGE))
                .build();

        JsonNode file = new ObjectMapper(new YAMLFactory()).readTree(p.toYaml());

        assertThat(file.at("/spec/actions/0/effect").asText()).isEqualTo("change");
    }

    @Test
    @SuppressWarnings("deprecation")
    void theOldSpellingStillReadsAsChange() throws Exception {
        assertThat(Effect.of("update")).isSameAs(Effect.CHANGE);
        assertThat(Effect.of(" Change ")).isSameAs(Effect.CHANGE);
        assertThat(Effect.UPDATE).isSameAs(Effect.CHANGE);
        assertThat(Json.MAPPER.readValue("\"update\"", Effect.class)).isSameAs(Effect.CHANGE);
        assertThat(Json.MAPPER.writeValueAsString(Effect.CHANGE)).isEqualTo("\"change\"");
        assertThatThrownBy(() -> Effect.of("modify")).hasMessageContaining("read, create, change or delete");
    }
}
