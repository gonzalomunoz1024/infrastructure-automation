package com.iaf.provider.sdk;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the worker finds Temporal, how it proves who it is there, and how long it lets calls in
 * flight finish when it stops. A CA, a client certificate or an API key makes the connection TLS:
 * the CA verifies the frontend (the system's CAs without one), the certificate and its key are the
 * worker's identity for mutual TLS, the API key (Temporal Cloud) goes with every request as a
 * bearer token.
 *
 * @param address      the frontend address, {@code host:port}
 * @param namespace    the Temporal namespace the platform runs in
 * @param drainTimeout how long a stopping worker waits for the calls it is running
 * @param tlsCa        a PEM file of the CA that signed the frontend's certificate; may be null
 * @param tlsCert      a PEM file of the worker's client certificate, with {@code tlsKey}; may be null
 * @param tlsKey       a PEM file of the client certificate's private key in PKCS #8
 *                     ({@code BEGIN PRIVATE KEY}), with {@code tlsCert}; may be null
 * @param apiKey       a Temporal Cloud API key; may be null. {@link #toString()} never shows it.
 */
public record WorkerSettings(String address, String namespace, Duration drainTimeout, Path tlsCa, Path tlsCert, Path tlsKey, String apiKey) {

    public static final String ADDRESS_ENV = "TEMPORAL_ADDRESS";
    public static final String NAMESPACE_ENV = "TEMPORAL_NAMESPACE";
    public static final String DRAIN_ENV = "WORKER_DRAIN_SECONDS";
    public static final String TLS_CA_ENV = "IAF_TEMPORAL_TLS_CA";
    public static final String TLS_CERT_ENV = "IAF_TEMPORAL_TLS_CERT";
    public static final String TLS_KEY_ENV = "IAF_TEMPORAL_TLS_KEY";
    public static final String API_KEY_ENV = "IAF_TEMPORAL_API_KEY";

    private static final Logger log = LoggerFactory.getLogger(WorkerSettings.class);

    public WorkerSettings {
        if ((tlsCert == null) != (tlsKey == null)) {
            throw new IllegalArgumentException(TLS_CERT_ENV + " and " + TLS_KEY_ENV + " go together: the worker's client certificate and its key, for mutual TLS");
        }
        apiKey = blank(apiKey) ? null : apiKey.trim();
    }

    /** A plain connection, without TLS or an API key. */
    public WorkerSettings(String address, String namespace, Duration drainTimeout) {
        this(address, namespace, drainTimeout, null, null, null, null);
    }

    public static WorkerSettings defaults() {
        return new WorkerSettings("127.0.0.1:7233", "default", Duration.ofSeconds(30));
    }

    /**
     * {@code TEMPORAL_ADDRESS}, {@code TEMPORAL_NAMESPACE} and {@code WORKER_DRAIN_SECONDS}, each
     * falling back to the defaults (a drain time that is not a whole number of seconds is logged
     * and falls back too, as in the other SDKs), and {@code IAF_TEMPORAL_TLS_CA},
     * {@code IAF_TEMPORAL_TLS_CERT}, {@code IAF_TEMPORAL_TLS_KEY} and {@code IAF_TEMPORAL_API_KEY}
     * for TLS and Temporal Cloud.
     */
    public static WorkerSettings fromEnvironment() {
        return from(System.getenv());
    }

    static WorkerSettings from(Map<String, String> env) {
        WorkerSettings d = defaults();
        return new WorkerSettings(
                blank(env.get(ADDRESS_ENV)) ? d.address() : env.get(ADDRESS_ENV).trim(),
                blank(env.get(NAMESPACE_ENV)) ? d.namespace() : env.get(NAMESPACE_ENV).trim(),
                drain(env.get(DRAIN_ENV), d.drainTimeout()),
                path(env.get(TLS_CA_ENV)),
                path(env.get(TLS_CERT_ENV)),
                path(env.get(TLS_KEY_ENV)),
                env.get(API_KEY_ENV));
    }

    /** These settings with TLS: the CA (null for the system's CAs), and the client certificate and key for mutual TLS (both null without). */
    public WorkerSettings withTls(Path ca, Path cert, Path key) {
        return new WorkerSettings(address, namespace, drainTimeout, ca, cert, key, apiKey);
    }

    /** These settings with a Temporal Cloud API key. */
    public WorkerSettings withApiKey(String key) {
        return new WorkerSettings(address, namespace, drainTimeout, tlsCa, tlsCert, tlsKey, key);
    }

    /** Whether the worker talks TLS to the frontend: a CA, a client certificate or an API key asks for it. */
    public boolean tls() {
        return tlsCa != null || tlsCert != null || apiKey != null;
    }

    @Override
    public String toString() {
        return "WorkerSettings[address=" + address + ", namespace=" + namespace + ", drainTimeout=" + drainTimeout + ", tlsCa=" + tlsCa
                + ", tlsCert=" + tlsCert + ", tlsKey=" + tlsKey + ", apiKey=" + (apiKey == null ? "none" : "(set)") + "]";
    }

    private static Duration drain(String value, Duration fallback) {
        if (blank(value)) {
            return fallback;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds >= 0) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException e) {
            // logged below
        }
        log.warn("{} is a whole number of seconds, not '{}'; the worker drains for {}s", DRAIN_ENV, value.trim(), fallback.toSeconds());
        return fallback;
    }

    private static Path path(String value) {
        return blank(value) ? null : Path.of(value.trim());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
