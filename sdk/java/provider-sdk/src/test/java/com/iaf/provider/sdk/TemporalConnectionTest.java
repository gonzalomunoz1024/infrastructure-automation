package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.grpc.Metadata;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/** How the worker's settings become the options of its connection to Temporal (no server). */
class TemporalConnectionTest {

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @TempDir
    Path dir;

    @Test
    void aPlainConnectionHasNoTlsAndNoKey() {
        WorkflowServiceStubsOptions o = TemporalConnection.options(WorkerSettings.defaults());

        assertThat(o.getTarget()).isEqualTo("127.0.0.1:7233");
        assertThat(o.getSslContext()).isNull();
        assertThat(o.getEnableHttps()).isFalse();
        assertThat(o.getGrpcMetadataProviders()).isNullOrEmpty();
        assertThat(TemporalConnection.describe(WorkerSettings.defaults())).isEqualTo("plaintext");
    }

    @Test
    void anApiKeyAloneTalksTlsWithTheSystemsCasAndSendsTheKeyAsABearerToken() {
        WorkerSettings s = WorkerSettings.defaults().withApiKey("test-api-key");

        WorkflowServiceStubsOptions o = TemporalConnection.options(s);

        assertThat(o.getEnableHttps()).isTrue();
        assertThat(o.getSslContext()).isNull();
        assertThat(o.getGrpcMetadataProviders()).singleElement()
                .satisfies(p -> assertThat(p.getMetadata().get(AUTHORIZATION)).isEqualTo("Bearer test-api-key"));
        assertThat(TemporalConnection.describe(s)).isEqualTo("TLS with an API key");
    }

    @Test
    void aCaVerifiesTheFrontend() throws Exception {
        Path ca = write("ca.crt", pem("CERTIFICATE", someCertificate().getEncoded()));
        WorkerSettings s = WorkerSettings.defaults().withTls(ca, null, null);

        WorkflowServiceStubsOptions o = TemporalConnection.options(s);

        assertThat(o.getSslContext()).isNotNull();
        assertThat(o.getSslContext().isClient()).isTrue();
        assertThat(o.getGrpcMetadataProviders()).isNullOrEmpty();
        assertThat(TemporalConnection.describe(s)).isEqualTo("TLS");
    }

    @Test
    void aClientCertificateAndItsKeyMakeTheConnectionMutualTls() throws Exception {
        Path ca = write("ca.crt", pem("CERTIFICATE", someCertificate().getEncoded()));
        Path cert = write("tls.crt", pem("CERTIFICATE", someCertificate().getEncoded()));
        Path key = write("tls.key", pem("PRIVATE KEY", KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate().getEncoded()));
        WorkerSettings s = WorkerSettings.defaults().withTls(ca, cert, key).withApiKey("test-api-key");

        WorkflowServiceStubsOptions o = TemporalConnection.options(s);

        assertThat(o.getSslContext()).isNotNull();
        assertThat(o.getSslContext().isClient()).isTrue();
        assertThat(o.getGrpcMetadataProviders()).hasSize(1);
        assertThat(TemporalConnection.describe(s)).isEqualTo("mutual TLS with an API key");
    }

    @Test
    void aFileThatCannotBeReadNamesItsVariable() {
        Path missing = dir.resolve("nowhere/ca.crt");

        assertThatThrownBy(() -> TemporalConnection.options(WorkerSettings.defaults().withTls(missing, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IAF_TEMPORAL_TLS_CA")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void aCaFileWithoutACertificateSaysSo() throws Exception {
        Path ca = write("ca.crt", "not a certificate\n");

        assertThatThrownBy(() -> TemporalConnection.options(WorkerSettings.defaults().withTls(ca, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ca + " (IAF_TEMPORAL_TLS_CA)");
    }

    @Test
    void aKeyThatIsNotPkcs8PemSaysWhatItShouldBe() throws Exception {
        Path cert = write("tls.crt", pem("CERTIFICATE", someCertificate().getEncoded()));
        Path key = write("tls.key", "not a key\n");

        assertThatThrownBy(() -> TemporalConnection.options(WorkerSettings.defaults().withTls(null, cert, key)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IAF_TEMPORAL_TLS_CERT and IAF_TEMPORAL_TLS_KEY")
                .hasMessageContaining("PKCS #8");
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.US_ASCII);
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    /** A public CA certificate of the JDK's trust store: a real certificate, and nothing secret. */
    private static X509Certificate someCertificate() throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init((KeyStore) null);
        for (TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509TrustManager x && x.getAcceptedIssuers().length > 0) {
                return x.getAcceptedIssuers()[0];
            }
        }
        assumeTrue(false, "the JDK's trust store has no certificate");
        throw new IllegalStateException();
    }
}
