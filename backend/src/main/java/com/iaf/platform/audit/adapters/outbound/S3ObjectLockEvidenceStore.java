package com.iaf.platform.audit.adapters.outbound;

import java.net.URI;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.config.PlatformProperties;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Profile {@code worm-s3}: evidence as S3 Object Lock objects in {@code platform.audit.worm.s3.bucket},
 * each locked in COMPLIANCE mode until {@code platform.audit.retention-years} after it was written.
 * AWS credentials come from the SDK's default chain (environment, web identity, instance role),
 * never from the platform's configuration. Startup fails when the bucket has no Object Lock.
 */
@Component
@Profile("worm-s3")
@Primary
public class S3ObjectLockEvidenceStore extends WormEvidenceStore {

    private final S3Client s3;

    @Autowired
    public S3ObjectLockEvidenceStore(WormProperties worm, PlatformProperties platform, ObjectProvider<Clock> clock) {
        this(client(worm.getS3()), worm.getS3(), platform.getAudit().getRetentionYears(), clock.getIfAvailable(Clock::systemUTC));
    }

    S3ObjectLockEvidenceStore(S3Client s3, WormProperties.S3 config, int retentionYears) {
        this(s3, config, retentionYears, Clock.systemUTC());
    }

    S3ObjectLockEvidenceStore(S3Client s3, WormProperties.S3 config, int retentionYears, Clock clock) {
        super(locked(new S3ObjectLockObjects(s3, config.getBucket(), config.getPrefix())), retentionYears, clock);
        this.s3 = s3;
    }

    @PreDestroy
    public void close() {
        s3.close();
    }

    private static S3ObjectLockObjects locked(S3ObjectLockObjects objects) {
        objects.requireObjectLock();
        return objects;
    }

    static S3Client client(WormProperties.S3 config) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(config.isPathStyle());
        if (!config.getRegion().isBlank()) {
            builder.region(Region.of(config.getRegion().trim()));
        }
        if (!config.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(config.getEndpoint().trim()));
        }
        return builder.build();
    }
}
