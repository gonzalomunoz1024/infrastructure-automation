package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.config.PlatformProperties;

import io.grpc.Metadata;
import io.temporal.serviceclient.GrpcMetadataProvider;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/** The connection and payload settings of the {@code temporal} profile, built without a server. */
class TemporalConfigTest {

    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @TempDir
    Path dir;

    /** The credentials store of these tests: what the references name, nothing else. */
    final List<String> asked = new ArrayList<>();

    UnaryOperator<String> vault(Map<String, String> secrets) {
        return reference -> {
            asked.add(reference);
            return secrets.get(reference);
        };
    }

    static PlatformProperties.Temporal temporal() {
        PlatformProperties.Temporal t = new PlatformProperties.Temporal();
        t.setTarget("temporal.example.com:7233");
        return t;
    }

    static List<String> authorization(WorkflowServiceStubsOptions options) {
        List<String> values = new ArrayList<>();
        if (options.getGrpcMetadataProviders() == null) {
            return values;
        }
        for (GrpcMetadataProvider p : options.getGrpcMetadataProviders()) {
            Iterable<String> all = p.getMetadata().getAll(AUTHORIZATION);
            if (all != null) {
                all.forEach(values::add);
            }
        }
        return values;
    }

    @Test
    void withNothingSetTheConnectionIsPlainAndCarriesNoKey() {
        WorkflowServiceStubsOptions options = TemporalConfig.stubsOptions(temporal(), vault(Map.of()));

        assertThat(options.getTarget()).isEqualTo("temporal.example.com:7233");
        assertThat(options.getSslContext()).isNull();
        assertThat(options.getEnableHttps()).isFalse();
        assertThat(authorization(options)).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void aTemporalCloudApiKeyIsReadFromTheVaultSentWithEveryCallAndTurnsTlsOn() {
        PlatformProperties.Temporal t = temporal();
        t.setApiKey("vault:temporal/api-key");

        WorkflowServiceStubsOptions options = TemporalConfig.stubsOptions(t, vault(Map.of("vault:temporal/api-key", "test-api-key")));

        assertThat(authorization(options)).containsExactly("Bearer test-api-key");
        assertThat(options.getEnableHttps()).isTrue();
        assertThat(asked).containsExactly("vault:temporal/api-key");
    }

    @Test
    void aKeySettingThatIsNotAVaultReferenceOrNamesNothingStopsTheStart() {
        PlatformProperties.Temporal inline = temporal();
        inline.setApiKey("test-api-key");
        PlatformProperties.Temporal missing = temporal();
        missing.setApiKey("vault:temporal/nothing-here");
        PlatformProperties.Temporal payloads = temporal();
        payloads.setPayloadKey("c2l4dGVlbi1ieXRlcy1rZXk=");

        assertThatThrownBy(() -> TemporalConfig.stubsOptions(inline, vault(Map.of()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.temporal.api-key must be a vault: reference");
        assertThatThrownBy(() -> TemporalConfig.stubsOptions(missing, vault(Map.of()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No secret at vault:temporal/nothing-here");
        assertThatThrownBy(() -> TemporalConfig.payloadEncryption(payloads, vault(Map.of()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.temporal.payload-key must be a vault: reference");
        assertThat(asked).containsExactly("vault:temporal/nothing-here");
    }

    @Test
    void thePayloadKeyIsReadFromTheVaultAndABlankOneEncryptsNothing() {
        String key = PayloadEncryptionTest.key();
        PlatformProperties.Temporal t = temporal();
        t.setPayloadKey("vault:temporal/payload-key");

        PayloadEncryption codec = TemporalConfig.payloadEncryption(t, vault(Map.of("vault:temporal/payload-key", key)));

        assertThat(codec).isNotNull();
        assertThat(codec.keyId()).isEqualTo(PayloadEncryption.of(key, "platform-interpreter").keyId());
        assertThat(TemporalConfig.payloadEncryption(temporal(), vault(Map.of()))).isNull();
    }

    @Test
    void aCaAndAClientCertificateMakeAnMtlsConnection() throws Exception {
        Path cert = dir.resolve("client.pem");
        Path key = dir.resolve("client.key");
        selfSigned(cert, key);
        PlatformProperties.Temporal mtls = temporal();
        mtls.getTls().setCa(cert.toString());
        mtls.getTls().setCert(cert.toString());
        mtls.getTls().setKey(key.toString());
        PlatformProperties.Temporal caOnly = temporal();
        caOnly.getTls().setCa(cert.toString());

        WorkflowServiceStubsOptions both = TemporalConfig.stubsOptions(mtls, vault(Map.of()));
        WorkflowServiceStubsOptions server = TemporalConfig.stubsOptions(caOnly, vault(Map.of()));

        assertThat(both.getSslContext()).isNotNull();
        assertThat(both.getSslContext().isClient()).isTrue();
        assertThat(server.getSslContext()).isNotNull();
    }

    @Test
    void aClientCertificateWithoutItsKeyOrAFileThatCannotBeReadStopsTheStart() {
        PlatformProperties.Temporal half = temporal();
        half.getTls().setCert(dir.resolve("client.pem").toString());
        PlatformProperties.Temporal gone = temporal();
        gone.getTls().setCa(dir.resolve("missing-ca.pem").toString());

        assertThatThrownBy(() -> TemporalConfig.stubsOptions(half, vault(Map.of()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("go together");
        assertThatThrownBy(() -> TemporalConfig.stubsOptions(gone, vault(Map.of()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-ca.pem");
    }

    @Test
    void theWorkerSendsAHeartbeatAtLeastEveryHeartbeatInterval() {
        PlatformProperties.Temporal t = temporal();
        t.setHeartbeatInterval(Duration.ofSeconds(15));
        PlatformProperties.Temporal off = temporal();
        off.setHeartbeatInterval(Duration.ZERO);

        assertThat(TemporalConfig.workerOptions(t).getMaxHeartbeatThrottleInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(TemporalConfig.workerOptions(off).getMaxHeartbeatThrottleInterval()).as("the SDK's own default").isNotEqualTo(Duration.ZERO);
    }

    /** A throwaway self-signed certificate and its PKCS#8 key, made by openssl for this test only. */
    private static void selfSigned(Path cert, Path key) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/openssl")) || Files.isExecutable(Path.of("/bin/openssl")), "openssl is not installed");
        Process p = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                "-keyout", key.toString(), "-out", cert.toString(), "-days", "1", "-subj", "/CN=platform-test")
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(output).isZero();
    }
}
