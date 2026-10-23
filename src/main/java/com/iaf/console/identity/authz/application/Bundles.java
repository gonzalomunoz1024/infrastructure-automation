package com.iaf.console.identity.authz.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.identity.authz.domain.Compiled;

import reactor.core.publisher.Mono;

/**
 * The OPA bundle: a gzipped tar with {@code .manifest} (revision = the global revision, roots
 * {@code console}), {@code console/data.json} (the accounts and the guardrails) and {@code console/authz.rego}. Built
 * from the current snapshot and kept until the revision moves; the ETag is the revision.
 */
public final class Bundles {

    public static final String REGO_PATH = "opa/console/authz.rego";

    /** A built bundle and its revision. */
    public record Bundle(long revision, byte[] bytes) {

        public String etag() {
            return "\"" + revision + "\"";
        }
    }

    private final Snapshots snapshots;
    private final ObjectMapper json;
    private final byte[] rego;
    private volatile Bundle cached;

    public Bundles(Snapshots snapshots, ObjectMapper json) {
        this.snapshots = snapshots;
        this.json = json;
        this.rego = regoBytes();
    }

    public Mono<Bundle> current() {
        return snapshots.get().map(compiled -> {
            Bundle held = cached;
            if (held != null && held.revision() == compiled.revision()) {
                return held;
            }
            Bundle built = build(compiled);
            cached = built;
            return built;
        });
    }

    public Bundle build(Compiled compiled) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out); Tar tar = new Tar(gzip)) {
                tar.entry(".manifest", json.writeValueAsBytes(Map.of("revision", Long.toString(compiled.revision()), "roots", new String[] {"console"})));
                tar.entry("console/data.json",
                        json.writeValueAsBytes(Map.of("accounts", compiled.accounts(), "guardrails", compiled.guardrails(), "version", compiled.revision())));
                tar.entry("console/authz.rego", rego);
            }
            return new Bundle(compiled.revision(), out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String rego() {
        return new String(rego, StandardCharsets.UTF_8);
    }

    private static byte[] regoBytes() {
        try (InputStream in = Bundles.class.getClassLoader().getResourceAsStream(REGO_PATH)) {
            if (in == null) {
                throw new IllegalStateException("the policy " + REGO_PATH + " is not on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A minimal ustar writer: regular files, names under 100 characters, fixed mode and time. */
    static final class Tar implements AutoCloseable {

        private final java.io.OutputStream out;

        Tar(java.io.OutputStream out) {
            this.out = out;
        }

        void entry(String name, byte[] content) throws IOException {
            byte[] header = new byte[512];
            put(header, 0, 100, name);
            put(header, 100, 8, "0000644");
            put(header, 108, 8, "0000000");
            put(header, 116, 8, "0000000");
            put(header, 124, 12, String.format("%011o", content.length));
            put(header, 136, 12, String.format("%011o", Instant.EPOCH.getEpochSecond()));
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            put(header, 257, 6, "ustar");
            header[263] = '0';
            header[264] = '0';
            int sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            put(header, 148, 7, String.format("%06o", sum));
            header[155] = ' ';
            out.write(header);
            out.write(content);
            int pad = (512 - content.length % 512) % 512;
            out.write(new byte[pad]);
        }

        private static void put(byte[] header, int offset, int length, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
        }

        @Override
        public void close() throws IOException {
            out.write(new byte[1024]);
        }
    }
}
