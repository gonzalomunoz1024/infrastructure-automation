package com.iaf.platform.audit.adapters.outbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Write-once objects in an S3 bucket with Object Lock. Every object is put with
 * {@code If-None-Match: *} (a taken key answers 412 and is never replaced) and locked in
 * {@code COMPLIANCE} mode until its retain-until date, which nobody, the root account included,
 * can shorten or remove. The bucket must have Object Lock enabled; {@link #requireObjectLock()}
 * checks it before the store takes any evidence.
 */
public class S3ObjectLockObjects implements WormObjects {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3ObjectLockObjects(S3Client s3, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("platform.audit.worm.s3.bucket is required with the worm-s3 profile");
        }
        this.s3 = s3;
        this.bucket = bucket.trim();
        String p = prefix == null ? "" : prefix.trim();
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    /** Fails unless the bucket has Object Lock enabled: evidence must never land where it can be deleted. */
    public void requireObjectLock() {
        GetObjectLockConfigurationResponse config;
        try {
            config = s3.getObjectLockConfiguration(GetObjectLockConfigurationRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            String code = e.awsErrorDetails() == null ? String.valueOf(e.statusCode()) : e.awsErrorDetails().errorCode();
            throw new IllegalStateException("bucket " + bucket + " has no readable Object Lock configuration (" + code
                    + "); evidence needs a bucket created with Object Lock enabled", e);
        }
        if (config.objectLockConfiguration() == null || config.objectLockConfiguration().objectLockEnabled() != ObjectLockEnabled.ENABLED) {
            throw new IllegalStateException("bucket " + bucket + " does not have Object Lock enabled; evidence needs a bucket created with it");
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] bytes, Instant retainUntil) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + key)
                .ifNoneMatch("*")
                .contentType("application/json")
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(retainUntil)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .build();
        try {
            s3.putObject(put, RequestBody.fromBytes(bytes));
            return true;
        } catch (S3Exception e) {
            // 412: the key is taken; 409: another conditional write of the key is in flight
            if (e.statusCode() == 412 || e.statusCode() == 409) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> list(String keyPrefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix + keyPrefix).continuationToken(token).build());
            for (S3Object o : page.contents()) {
                keys.add(o.key().substring(prefix.length()));
            }
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        keys.sort(null);
        return keys;
    }

    @Override
    public List<String> folders(String keyPrefix) {
        String under = prefix + keyPrefix;
        List<String> names = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(under).delimiter("/").continuationToken(token).build());
            for (CommonPrefix cp : page.commonPrefixes()) {
                String name = cp.prefix().substring(under.length());
                names.add(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
            }
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
        names.sort(null);
        return names;
    }

    @Override
    public String describe() {
        return "s3://" + bucket + "/" + prefix + " (Object Lock, COMPLIANCE)";
    }
}
