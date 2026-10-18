package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.ByteString;
import com.iaf.platform.testing.TestWeb;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;

/** The AES-GCM codec of {@code platform.temporal.payload-key}: what it seals, what it lets through, and what it refuses to open. */
class PayloadEncryptionTest {

    static final String QUEUE = "platform-interpreter";
    static final SecureRandom RANDOM = new SecureRandom();

    /** A throwaway key for one test, base64. */
    static String key() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    static Payload json(String text) {
        return Payload.newBuilder().putMetadata("encoding", ByteString.copyFromUtf8("json/plain")).setData(ByteString.copyFromUtf8(text)).build();
    }

    static String encoding(Payload p) {
        return p.getMetadataMap().get("encoding").toStringUtf8();
    }

    static ActivitySerializationContext activity(String taskQueue, boolean local) {
        return new ActivitySerializationContext("default", "job-1", "InterpreterWorkflow", "CallProvider", taskQueue, local);
    }

    @Test
    void aPayloadIsSealedWholeAndOpensToWhatItWas() {
        PayloadEncryption codec = PayloadEncryption.of(key(), QUEUE);
        Payload plain = json("{\"password\":\"not-a-real-one\"}");

        Payload sealed = codec.encode(List.of(plain)).get(0);

        assertThat(encoding(sealed)).isEqualTo(PayloadEncryption.ENCODING);
        assertThat(sealed.getMetadataMap().get(PayloadEncryption.METADATA_CIPHER).toStringUtf8()).isEqualTo("AES/GCM/NoPadding");
        assertThat(sealed.getMetadataMap().get(PayloadEncryption.METADATA_KEY_ID).toStringUtf8()).isEqualTo(codec.keyId()).hasSize(16);
        assertThat(sealed.getData().toStringUtf8()).doesNotContain("password", "not-a-real-one", "json/plain");
        assertThat(codec.encode(List.of(plain)).get(0).getData()).as("a fresh nonce every time").isNotEqualTo(sealed.getData());
        assertThat(codec.decode(List.of(sealed))).containsExactly(plain);
    }

    @Test
    void aSealedPayloadThatWasChangedDoesNotOpen() {
        PayloadEncryption codec = PayloadEncryption.of(key(), QUEUE);
        Payload sealed = codec.encode(List.of(json("{\"vmId\":\"vm-1042\"}"))).get(0);
        byte[] data = sealed.getData().toByteArray();
        data[data.length - 5] ^= 0x01;
        Payload tampered = sealed.toBuilder().setData(ByteString.copyFrom(data)).build();
        Payload truncated = sealed.toBuilder().setData(sealed.getData().substring(0, 8)).build();

        assertThatThrownBy(() -> codec.decode(List.of(tampered))).isInstanceOf(PayloadCodecException.class).hasMessageContaining("changed after it was sealed");
        assertThatThrownBy(() -> codec.decode(List.of(truncated))).isInstanceOf(PayloadCodecException.class).hasMessageContaining("too short");
    }

    @Test
    void aPayloadSealedUnderAnotherKeyIsRefusedNamingBothKeys() {
        PayloadEncryption mine = PayloadEncryption.of(key(), QUEUE);
        PayloadEncryption theirs = PayloadEncryption.of(key(), QUEUE);
        Payload sealed = theirs.encode(List.of(json("{}"))).get(0);
        // the key id is authenticated: relabelling the payload does not make it open
        Payload relabelled = sealed.toBuilder().putMetadata(PayloadEncryption.METADATA_KEY_ID, ByteString.copyFromUtf8(mine.keyId())).build();

        assertThatThrownBy(() -> mine.decode(List.of(sealed))).isInstanceOf(PayloadCodecException.class)
                .hasMessageContaining(theirs.keyId()).hasMessageContaining(mine.keyId());
        assertThatThrownBy(() -> mine.decode(List.of(relabelled))).isInstanceOf(PayloadCodecException.class);
    }

    @Test
    void whatWasWrittenBeforeTheKeyWasSetGoesThroughAsItIs() {
        PayloadEncryption codec = PayloadEncryption.of(key(), QUEUE);
        Payload plain = json("{\"step\":\"get\"}");

        assertThat(codec.decode(List.of(plain))).containsExactly(plain);
    }

    @Test
    void aCallOnATeamsWorkerIsLeftReadableAndThePlatformsOwnAreSealed() {
        PayloadEncryption codec = PayloadEncryption.of(key(), QUEUE);
        Payload plain = json("{\"title\":\"quarterly\"}");

        PayloadCodec team = codec.withContext(activity("provider-reports", false));
        assertThat(team.encode(List.of(plain))).containsExactly(plain);
        assertThat(team.decode(team.encode(List.of(plain)))).containsExactly(plain);
        assertThat(team.decode(codec.encode(List.of(plain)))).as("what the platform sealed still opens").containsExactly(plain);

        assertThat(encoding(codec.withContext(activity(QUEUE, false)).encode(List.of(plain)).get(0))).isEqualTo(PayloadEncryption.ENCODING);
        assertThat(encoding(codec.withContext(activity(QUEUE, true)).encode(List.of(plain)).get(0))).as("a local activity").isEqualTo(PayloadEncryption.ENCODING);
        assertThat(encoding(codec.withContext(new WorkflowSerializationContext("default", "job-1")).encode(List.of(plain)).get(0)))
                .as("the workflow's own input, result, signals").isEqualTo(PayloadEncryption.ENCODING);
    }

    @Test
    void theConverterSealsThePlatformsPayloadsAndReadsThemBack() {
        DataConverter converter = TemporalConfig.dataConverter(TestWeb.json(), PayloadEncryption.of(key(), QUEUE));
        Map<String, Object> input = Map.of("vmId", "vm-1042", "reason", "stuck");

        Optional<Payloads> written = converter.toPayloads(input);
        Optional<Payloads> forTheTeam = converter.withContext(activity("provider-reports", false)).toPayloads(input);

        assertThat(encoding(written.orElseThrow().getPayloads(0))).isEqualTo(PayloadEncryption.ENCODING);
        assertThat(written.orElseThrow().toString()).doesNotContain("vm-1042");
        assertThat(converter.fromPayloads(0, written, JsonNode.class, JsonNode.class).path("vmId").asText()).isEqualTo("vm-1042");
        assertThat(encoding(forTheTeam.orElseThrow().getPayloads(0))).isEqualTo("json/plain");
        assertThat(converter.fromPayloads(0, forTheTeam, JsonNode.class, JsonNode.class).path("reason").asText()).isEqualTo("stuck");
    }

    @Test
    void aKeyThatIsNotThirtyTwoBytesOfBase64IsRefused() {
        assertThatThrownBy(() -> PayloadEncryption.of("not base64!", QUEUE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> PayloadEncryption.of(Base64.getEncoder().encodeToString(new byte[16]), QUEUE)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte").hasMessageContaining("16 bytes");
    }
}
