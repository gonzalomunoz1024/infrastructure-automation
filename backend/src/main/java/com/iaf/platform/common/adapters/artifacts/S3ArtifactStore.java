package com.iaf.platform.common.adapters.artifacts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.ArtifactsProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The {@link ArtifactStorePort} on an S3-compatible bucket (profile {@code s3}): objects at
 * {@code <prefix>sha256/<first two digits>/<digest>}, written once with {@code If-None-Match: *}
 * (a 412 means the digest is already stored) and with the SHA-256 checksum the store verifies;
 * reads check the digest again. Credentials come from a Vault reference, or the AWS default chain.
 */
@Slf4j
@Component
@Profile("s3")
@Primary
public class S3ArtifactStore implements ArtifactStorePort {

    static final Duration CREDENTIAL_TTL = Duration.ofMinutes(5);

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3ArtifactStore(ArtifactsProperties props, CredentialsPort credentials) {
        this(client(props.getS3(), credentials), props.getS3().getBucket(), props.getS3().getPrefix());
    }

    S3ArtifactStore(S3Client s3, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("platform.artifacts.s3.bucket is required with the s3 profile");
        }
        this.s3 = s3;
        this.bucket = bucket.trim();
        String p = prefix == null ? "" : prefix.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    static S3Client client(ArtifactsProperties.S3 cfg, CredentialsPort credentials) {
        S3ClientBuilder b = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60)).build())
                .region(Region.of(cfg.getRegion() == null || cfg.getRegion().isBlank() ? "us-east-1" : cfg.getRegion().trim()))
                .credentialsProvider(cfg.getCredential() == null || cfg.getCredential().isBlank()
                        ? DefaultCredentialsProvider.builder().build()
                        : new VaultKeys(credentials, cfg.getCredential().trim()))
                .forcePathStyle(cfg.isPathStyle())
                .serviceConfiguration(c -> c.chunkedEncodingEnabled(false))
                // S3-compatible stores do not all speak the newer default checksums; integrity is checked end to end anyway
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            b.endpointOverride(URI.create(cfg.getEndpoint().trim()));
        }
        return b.build();
    }

    @Override
    public Mono<Artifact> put(Path file) {
        return blocking(() -> {
            Artifact a = ArtifactFiles.hash(file);
            upload(a, RequestBody.fromFile(file));
            return a;
        });
    }

    @Override
    public Mono<Artifact> put(byte[] content) {
        return blocking(() -> {
            byte[] bytes = content == null ? new byte[0] : content;
            Artifact a = new Artifact(HexFormat.of().formatHex(ArtifactFiles.sha256().digest(bytes)), bytes.length);
            upload(a, RequestBody.fromBytes(bytes));
            return a;
        });
    }

    private void upload(Artifact a, RequestBody body) {
        String key = keyOf(a.sha256());
        if (head(key)) {
            return;
        }
        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .ifNoneMatch("*")
                    .contentLength(a.size())
                    .contentType("application/octet-stream")
                    .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(a.sha256())))
                    .metadata(Map.of("sha256", a.sha256()))
                    .build(), body);
            log.debug("stored artifact {} ({} bytes) in s3://{}/{}", a.sha256(), a.size(), bucket, key);
        } catch (S3Exception e) {
            if (e.statusCode() != 412) {
                throw e;
            }
            // 412: the same digest was stored meanwhile; write-once keeps the first copy
        }
    }

    @Override
    public Mono<Path> get(String sha256, Path target) {
        return blocking(() -> {
            String digest = ArtifactStorePort.digest(sha256);
            try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(keyOf(digest)).build())) {
                return Optional.of(ArtifactFiles.copyVerified(in, digest, target));
            } catch (NoSuchKeyException e) {
                return Optional.<Path>empty();
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return Optional.<Path>empty();
                }
                throw e;
            }
        }).flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Boolean> exists(String sha256) {
        return blocking(() -> head(keyOf(ArtifactStorePort.digest(sha256))));
    }

    String keyOf(String digest) {
        return prefix + "sha256/" + digest.substring(0, 2) + "/" + digest;
    }

    private boolean head(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private static <T> Mono<T> blocking(Callable<T> call) {
        return Mono.fromCallable(() -> {
            try {
                return call.call();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Access keys from the vault ({@code <access key id>:<secret access key>}), re-read every few minutes; never logged. */
    static final class VaultKeys implements AwsCredentialsProvider {

        private final CredentialsPort credentials;
        private final String reference;
        private volatile AwsCredentials cached;
        private volatile Instant expires = Instant.EPOCH;

        VaultKeys(CredentialsPort credentials, String reference) {
            this.credentials = credentials;
            this.reference = reference;
        }

        @Override
        public AwsCredentials resolveCredentials() {
            if (cached != null && Instant.now().isBefore(expires)) {
                return cached;
            }
            String value = credentials.resolve(reference).blockOptional(Duration.ofSeconds(10))
                    .orElseThrow(() -> new IllegalStateException("credential " + reference + " is not in the vault"));
            int colon = value.indexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                throw new IllegalStateException("credential " + reference + " must hold <access key id>:<secret access key>");
            }
            cached = AwsBasicCredentials.create(value.substring(0, colon).trim(), value.substring(colon + 1).trim());
            expires = Instant.now().plus(CREDENTIAL_TTL);
            return cached;
        }
    }
}
