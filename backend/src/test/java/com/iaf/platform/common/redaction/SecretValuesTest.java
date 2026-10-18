package com.iaf.platform.common.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.ports.CredentialsPort;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SecretValuesTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void aRememberedSecretIsKnownAsWrittenAndInBase64() {
        SecretValues secrets = new SecretValues();
        secrets.remember("tok-123456");

        assertThat(secrets.known("tok-123456")).isTrue();
        assertThat(secrets.known(" tok-123456 ")).isTrue();
        assertThat(secrets.known(Base64.getEncoder().encodeToString("tok-123456".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(secrets.known("tok-1234567")).isFalse();
    }

    @Test
    void onlyDigestsAreKeptNeverTheValue() throws Exception {
        SecretValues secrets = new SecretValues();
        secrets.remember("plain-value-xyz");

        var field = SecretValues.class.getDeclaredField("digests");
        field.setAccessible(true);
        String kept = String.valueOf(field.get(secrets));

        assertThat(kept).doesNotContain("plain-value-xyz");
        assertThat(secrets.size()).isEqualTo(2);
    }

    @Test
    void theSetIsBoundedOldestFirst() {
        SecretValues secrets = new SecretValues();
        for (int i = 0; i < SecretValues.MAX; i++) {
            secrets.remember("secret-" + i);
        }

        assertThat(secrets.size()).isEqualTo(SecretValues.MAX);
        assertThat(secrets.known("secret-0")).isFalse();
        assertThat(secrets.known("secret-" + (SecretValues.MAX - 1))).isTrue();
    }

    @Test
    void withNothingRememberedOnlyFieldNamesAreMasked() throws Exception {
        SecretValues secrets = new SecretValues();
        JsonNode out = secrets.redact(json.readTree("{\"password\":\"p\",\"echo\":\"p\"}"));

        assertThat(out.get("password").asText()).isEqualTo("***");
        assertThat(out.get("echo").asText()).isEqualTo("p");
        assertThat(secrets.redact("text p")).isEqualTo("text p");
    }

    @Test
    void recordingCredentialsTellTheRedactorAboutResolvedAndStoredValues() {
        SecretValues secrets = new SecretValues();
        CredentialsPort vault = new CredentialsPort() {
            @Override
            public Mono<String> resolve(String reference) {
                return "vault:vsphere/token".equals(reference) ? Mono.just("resolved-token-1") : Mono.empty();
            }

            @Override
            public Mono<List<CredentialInfo>> list() {
                return Mono.just(List.of());
            }

            @Override
            public Mono<CredentialInfo> store(String name, String type, String value, String by) {
                return Mono.just(new CredentialInfo(name, type, CredentialsPort.referenceOf(name), null));
            }
        };
        RecordingCredentials recording = new RecordingCredentials(vault, secrets);

        StepVerifier.create(recording.resolve("vault:vsphere/token")).expectNext("resolved-token-1").verifyComplete();
        StepVerifier.create(recording.resolve("vault:missing")).verifyComplete();
        StepVerifier.create(recording.store("app/key", "api-key", "stored-key-2", "ana")).expectNextCount(1).verifyComplete();

        assertThat(secrets.known("resolved-token-1")).isTrue();
        assertThat(secrets.known("stored-key-2")).isTrue();
        assertThat(recording.delegate()).isSameAs(vault);
    }

    @Test
    void thePostProcessorWrapsCredentialsBeansOnlyOnce() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        SecretValues secrets = new SecretValues();
        factory.registerSingleton("secretValues", secrets);
        CredentialsRecordingPostProcessor post = new CredentialsRecordingPostProcessor(factory.getBeanProvider(SecretValues.class));
        CredentialsPort port = new RecordingCredentials(null, secrets);
        CredentialsPort plain = org.mockito.Mockito.mock(CredentialsPort.class);

        assertThat(post.postProcessAfterInitialization(plain, "vault")).isInstanceOf(RecordingCredentials.class);
        assertThat(post.postProcessAfterInitialization(port, "already")).isSameAs(port);
        Object other = new Object();
        assertThat(post.postProcessAfterInitialization(other, "other")).isSameAs(other);
    }
}
