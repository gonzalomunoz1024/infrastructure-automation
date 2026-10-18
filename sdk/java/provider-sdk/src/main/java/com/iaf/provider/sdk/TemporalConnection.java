package com.iaf.provider.sdk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/**
 * The service stubs options a worker connects with, from its {@link WorkerSettings}: the address,
 * TLS with the settings' CA (the system's CAs without one) and client certificate, and the API key
 * as a bearer token. A file that cannot be read fails here, naming the variable it came from.
 */
final class TemporalConnection {

    private TemporalConnection() {
    }

    static WorkflowServiceStubsOptions options(WorkerSettings s) {
        WorkflowServiceStubsOptions.Builder b = WorkflowServiceStubsOptions.newBuilder().setTarget(s.address());
        if (s.tlsCa() != null || s.tlsCert() != null) {
            b.setSslContext(sslContext(s));
        } else if (s.apiKey() != null) {
            b.setEnableHttps(true);
        }
        if (s.apiKey() != null) {
            String key = s.apiKey();
            b.addApiKey(() -> key);
        }
        return b.build();
    }

    /** How the worker connects, for its start-up line: never the key itself. */
    static String describe(WorkerSettings s) {
        String transport = s.tlsCert() != null ? "mutual TLS" : s.tls() ? "TLS" : "plaintext";
        return s.apiKey() == null ? transport : transport + " with an API key";
    }

    private static SslContext sslContext(WorkerSettings s) {
        TrustManager trust = s.tlsCa() == null ? null : trustManager(s.tlsCa());
        SimpleSslContextBuilder ssl = s.tlsCert() == null
                ? SimpleSslContextBuilder.noKeyOrCertChain()
                : SimpleSslContextBuilder.forPKCS8(read(s.tlsCert(), WorkerSettings.TLS_CERT_ENV), read(s.tlsKey(), WorkerSettings.TLS_KEY_ENV));
        if (trust != null) {
            ssl.setTrustManager(trust);
        }
        try {
            return ssl.build();
        } catch (SSLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot set up TLS to Temporal from " + WorkerSettings.TLS_CERT_ENV + " and " + WorkerSettings.TLS_KEY_ENV
                    + " (PEM, the key in PKCS #8: BEGIN PRIVATE KEY): " + e.getMessage(), e);
        }
    }

    private static TrustManager trustManager(Path ca) {
        try {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(read(ca, WorkerSettings.TLS_CA_ENV));
            if (certs.isEmpty()) {
                throw new IllegalArgumentException(ca + " (" + WorkerSettings.TLS_CA_ENV + ") holds no PEM certificate");
            }
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate c : certs) {
                store.setCertificateEntry("ca-" + i++, c);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            return factory.getTrustManagers()[0];
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException(ca + " (" + WorkerSettings.TLS_CA_ENV + ") is not a PEM certificate: " + e.getMessage(), e);
        }
    }

    private static InputStream read(Path file, String env) {
        try {
            return new ByteArrayInputStream(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + " (" + env + "): " + e, e);
        }
    }
}
