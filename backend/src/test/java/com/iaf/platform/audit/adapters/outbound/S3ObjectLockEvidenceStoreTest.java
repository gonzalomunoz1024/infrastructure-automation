package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ObjectLockEvidenceStoreTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();

    final FakeS3 s3 = new FakeS3();

    S3ObjectLockEvidenceStore store() {
        WormProperties.S3 config = new WormProperties.S3();
        config.setBucket("evidence-bucket");
        config.setPrefix("iaf");
        return new S3ObjectLockEvidenceStore(s3, config, 7);
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static EvidenceEntry requested(Instant at) {
        return EvidenceEntry.draft(at, EvidenceKind.REQUESTED, "ana", json("{\"workflow\":\"vm-create\",\"version\":\"1.2.0\",\"channel\":\"api\"}"));
    }

    static EvidenceEntry step(String name) {
        return EvidenceEntry.draft(AT.plusSeconds(5), EvidenceKind.STEP, "platform", json("{\"step\":\"" + name + "\"}"));
    }

    @Test
    void putsEveryEntryOnlyIfAbsentAndLockedInComplianceModeForTheRetention() {
        store().append("job_1", requested(AT));

        PutObjectRequest put = s3.puts.get(0);
        assertThat(put.bucket()).isEqualTo("evidence-bucket");
        assertThat(put.key()).isEqualTo("iaf/evidence/job_1/entries/0000000001.json");
        assertThat(put.ifNoneMatch()).isEqualTo("*");
        assertThat(put.objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(put.objectLockRetainUntilDate()).isEqualTo(Instant.parse("2033-10-17T10:00:00Z"));
        assertThat(put.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
        assertThat(put.contentType()).isEqualTo("application/json");
    }

    @Test
    void anEntryAnotherInstanceWroteFirstIsChainedAfterNotReplaced() {
        S3ObjectLockEvidenceStore a = store();
        S3ObjectLockEvidenceStore b = store();
        a.append("job_1", requested(AT));

        EvidenceEntry fromB = b.append("job_1", step("from b"));
        EvidenceEntry fromA = a.append("job_1", step("from a"));

        assertThat(fromA.seq()).isEqualTo(3);
        assertThat(fromA.previous()).isEqualTo(fromB.hash());
        assertThat(s3.rejected).containsExactly("iaf/evidence/job_1/entries/0000000002.json");
        EvidenceRecord r = store().record("job_1").orElseThrow();
        assertThat(HashChain.verify(r.entries())).isEqualTo(-1);
        assertThat(r.entries()).hasSize(3);
    }

    @Test
    void aRestartedStoreRebuildsRecordsFromPagedListings() {
        S3ObjectLockEvidenceStore store = store();
        store.append("job_1", requested(AT));
        store.append("job_1", step("one"));
        EvidenceEntry head = store.append("job_1", step("two"));
        EvidenceRecord.Seal checkpoint = new EvidenceRecord.Seal(AT.plusSeconds(60), "sig-c", "key-1", head.seq(), head.hash());
        store.checkpoint("job_1", checkpoint);
        EvidenceEntry last = store.append("job_1", EvidenceEntry.draft(AT.plusSeconds(90), EvidenceKind.CLOSED, "platform",
                json("{\"status\":\"failed\",\"retention\":{\"until\":\"2033-10-17T10:01:30Z\"}}")));
        EvidenceRecord.Seal seal = new EvidenceRecord.Seal(AT.plusSeconds(3600), "sig-s", "key-1", last.seq(), last.hash());
        store.seal("job_1", seal);
        store.append("job_2", requested(AT.plusSeconds(600)));
        s3.pageSize = 2;

        S3ObjectLockEvidenceStore restarted = store();

        assertThat(restarted.records(EvidenceFilter.ALL)).extracting(EvidenceRecord::jobId).containsExactly("job_2", "job_1");
        EvidenceRecord r = restarted.record("job_1").orElseThrow();
        assertThat(r.entries()).hasSize(4);
        assertThat(HashChain.verify(r.entries())).isEqualTo(-1);
        assertThat(r.checkpoints()).containsExactly(checkpoint);
        assertThat(r.sealed()).isEqualTo(seal);
        assertThat(r.status()).isEqualTo("failed");
        assertThat(s3.listCalls).isGreaterThan(2);
    }

    @Test
    void refusesToStartOnABucketWithoutObjectLock() {
        s3.objectLock = false;

        assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class).hasMessageContaining("Object Lock");
        assertThat(s3.puts).isEmpty();
    }

    @Test
    void aBucketIsRequired() {
        WormProperties.S3 config = new WormProperties.S3();

        assertThatThrownBy(() -> new S3ObjectLockEvidenceStore(s3, config, 7)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.audit.worm.s3.bucket");
    }

    @Test
    void otherS3ErrorsAreNotTakenForATakenKey() {
        S3ObjectLockEvidenceStore store = store();
        s3.failWith = 503;

        assertThatThrownBy(() -> store.append("job_1", requested(AT))).isInstanceOf(S3Exception.class);
        assertThat(store.record("job_1")).isEmpty();
    }

    @Test
    void shareTokensAreLockedUntilTheyExpireAndUnknownOnesFindNothing() {
        Instant expires = AT.plus(Duration.ofDays(7));
        ShareToken token = store().shareToken("job_1", expires);

        assertThat(store().findShareToken(token.token())).contains(token);
        PutObjectRequest put = s3.puts.get(s3.puts.size() - 1);
        assertThat(put.key()).as("kept under the hash of the token, never the token")
                .isEqualTo("iaf/share-tokens/" + com.iaf.platform.common.domain.Hashes.sha256(token.token()) + ".json");
        assertThat(new String(s3.objects.get(put.key()), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(token.token());
        assertThat(put.objectLockRetainUntilDate()).isEqualTo(expires);
        assertThat(store().findShareToken("no-such-token-0123456789")).isEmpty();
    }

    /** An in-memory bucket with conditional puts, Object Lock and paged listings. */
    static final class FakeS3 implements S3Client {

        final Map<String, byte[]> objects = new TreeMap<>();
        final List<PutObjectRequest> puts = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();
        boolean objectLock = true;
        int pageSize = 1000;
        int listCalls;
        Integer failWith;

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }

        @Override
        public GetObjectLockConfigurationResponse getObjectLockConfiguration(GetObjectLockConfigurationRequest request) {
            if (!objectLock) {
                throw S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("ObjectLockConfigurationNotFoundError").build()).build();
            }
            return GetObjectLockConfigurationResponse.builder()
                    .objectLockConfiguration(ObjectLockConfiguration.builder().objectLockEnabled(ObjectLockEnabled.ENABLED).build())
                    .build();
        }

        @Override
        public synchronized PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            puts.add(request);
            if (failWith != null) {
                throw S3Exception.builder().statusCode(failWith).message("unavailable").build();
            }
            if ("*".equals(request.ifNoneMatch()) && objects.containsKey(request.key())) {
                rejected.add(request.key());
                throw S3Exception.builder().statusCode(412)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("PreconditionFailed").build()).build();
            }
            try (InputStream in = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return PutObjectResponse.builder().build();
        }

        @Override
        public synchronized ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
            byte[] bytes = objects.get(request.key());
            if (bytes == null) {
                throw NoSuchKeyException.builder().statusCode(404).build();
            }
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes);
        }

        @Override
        public synchronized ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            listCalls++;
            String prefix = request.prefix() == null ? "" : request.prefix();
            List<String> items = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String key : objects.keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                if (request.delimiter() != null) {
                    int cut = key.indexOf(request.delimiter(), prefix.length());
                    if (cut >= 0) {
                        String common = key.substring(0, cut + 1);
                        if (seen.add(common)) {
                            items.add(common);
                        }
                        continue;
                    }
                }
                items.add(key);
            }
            int from = request.continuationToken() == null ? 0 : Integer.parseInt(request.continuationToken());
            int to = Math.min(items.size(), from + pageSize);
            List<S3Object> contents = new ArrayList<>();
            List<CommonPrefix> prefixes = new ArrayList<>();
            for (String item : items.subList(from, to)) {
                if (request.delimiter() != null && item.endsWith(request.delimiter())) {
                    prefixes.add(CommonPrefix.builder().prefix(item).build());
                } else {
                    contents.add(S3Object.builder().key(item).build());
                }
            }
            boolean truncated = to < items.size();
            return ListObjectsV2Response.builder()
                    .contents(contents)
                    .commonPrefixes(prefixes)
                    .isTruncated(truncated)
                    .nextContinuationToken(truncated ? String.valueOf(to) : null)
                    .build();
        }
    }
}
