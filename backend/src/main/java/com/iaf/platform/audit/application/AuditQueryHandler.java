package com.iaf.platform.audit.application;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidencePdf;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.ports.inbound.AuditQueries;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.NotFoundException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reads evidence records and turns them into rows, verifications, files and packs. Every store read
 * runs on boundedElastic: the stores are synchronous (JDBC, files, the S3 client), and only the
 * {@code postgres} profile moves whole requests off the event loop.
 */
@Primary
@Service
public class AuditQueryHandler implements AuditQueries {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryHandler.class);

    static final String MANIFEST = "manifest.json";
    static final String RECORDS_DIR = "records/";
    /** The whole platform stream, whatever the filter: a chain is only checkable whole. */
    static final String PLATFORM_FILE = "platform.json";
    /** Where packs are spooled, under the temp directory ({@code /tmp} in the chart, an emptyDir). */
    static final String SPOOL_DIR = "iaf-evidence-packs";
    static final String SPOOL_GLOB = "evidence-pack-*.zip";
    /** A pack nobody read (the client left before its body was sent) is deleted after this. */
    static final Duration LEFTOVER = Duration.ofHours(1);

    private final EvidenceStore store;
    private final AuditSigner signer;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Path spool;

    @Autowired
    public AuditQueryHandler(EvidenceStore store, AuditSigner signer, Clock clock, ObjectMapper mapper) {
        this(store, signer, clock, mapper, Path.of(System.getProperty("java.io.tmpdir"), SPOOL_DIR));
    }

    public AuditQueryHandler(EvidenceStore store, AuditSigner signer, Clock clock, ObjectMapper mapper, Path spool) {
        this.store = store;
        this.signer = signer;
        this.clock = clock;
        this.mapper = mapper;
        this.spool = spool;
    }

    @Override
    public Flux<AuditRow> list(String workflow, String from, String to, String requester) {
        return Flux.defer(() -> Flux.fromIterable(store.records(EvidenceFilter.parse(workflow, from, to, requester))))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(r -> !r.platform())
                .map(r -> new AuditRow(r.jobId(), r.workflow(), r.requester(), r.channel(), r.at(), r.entries().size(), r.status()));
    }

    @Override
    public Mono<EvidenceRecord> record(String jobId) {
        return Mono.defer(() -> Mono.justOrEmpty(store.record(jobId)))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(() -> new NotFoundException("evidence record", jobId)));
    }

    @Override
    public Mono<Verification> verify(String jobId) {
        return record(jobId).map(this::verification);
    }

    /**
     * The chain walk plus every signature: each checkpoint and the final seal must be signed by
     * this instance's key over the hash of the entry it names (a seal without seq covers the
     * latest hash), and that entry must still carry that hash.
     */
    Verification verification(EvidenceRecord r) {
        int broken = HashChain.verify(r.entries());
        List<SealCheck> seals = new ArrayList<>();
        for (EvidenceRecord.Seal c : r.checkpoints()) {
            seals.add(new SealCheck("checkpoint", c.at(), c.seq(), c.keyId(), sealStatus(r, c)));
        }
        String seal = null;
        if (r.sealed() != null) {
            seal = sealStatus(r, r.sealed());
            seals.add(new SealCheck("seal", r.sealed().at(), r.sealed().seq() == null ? r.entries().size() : r.sealed().seq(), r.sealed().keyId(), seal));
        }
        boolean valid = broken < 0 && seals.stream().noneMatch(s -> "mismatch".equals(s.status()));
        return new Verification(valid, r.entries().size(), broken < 0 ? null : broken, seal, seals);
    }

    private String sealStatus(EvidenceRecord r, EvidenceRecord.Seal s) {
        String covered = s.hash() == null ? r.latestHash() : s.hash();
        if (s.seq() != null) {
            int i = s.seq() - 1;
            if (i < 0 || i >= r.entries().size() || !covered.equals(r.entries().get(i).hash())) {
                return "mismatch";
            }
        }
        if (!signer.keyId().equals(s.keyId())) {
            return "unverifiable";
        }
        return signer.verify(covered, s.signature()) ? "verified" : "mismatch";
    }

    @Override
    public Mono<PlatformStream> platform(String from, String to, String aggregate, String actor) {
        return Mono.fromCallable(() -> {
            EvidenceFilter window = EvidenceFilter.parse(null, from, to, null);
            String agg = aggregate == null || aggregate.isBlank() ? null : aggregate.trim();
            String who = actor == null || actor.isBlank() ? null : actor.trim();
            return store.record(EvidenceRecord.PLATFORM)
                    .map(r -> {
                        List<EvidenceEntry> matching = r.entries().stream()
                                .filter(e -> window.from() == null || (e.at() != null && !e.at().isBefore(window.from())))
                                .filter(e -> window.to() == null || (e.at() != null && e.at().isBefore(window.to())))
                                .filter(e -> who == null || who.equals(e.actor()))
                                .filter(e -> agg == null || aggregateMatches(e, agg))
                                .toList();
                        return new PlatformStream(matching, r.entries().size(), r.latestHash(), verification(r));
                    })
                    .orElseGet(() -> new PlatformStream(List.of(), 0, HashChain.GENESIS, new Verification(true, 0, null, null)));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** {@code workflow}, {@code vm-restart} or {@code workflow:vm-restart}. */
    static boolean aggregateMatches(EvidenceEntry e, String aggregate) {
        String type = e.data() == null ? null : e.data().path("aggregate").asText(null);
        String id = e.data() == null ? null : e.data().path("aggregateId").asText(null);
        return aggregate.equals(type) || aggregate.equals(id) || aggregate.equals(type + ":" + id);
    }

    @Override
    public Mono<FileExport> export(String jobId, String format) {
        String f = format == null || format.isBlank() ? "json" : format.toLowerCase();
        if (!f.equals("json") && !f.equals("pdf")) {
            return Mono.error(new IllegalArgumentException("format must be json or pdf, not '" + format + "'"));
        }
        return record(jobId).map(r -> f.equals("pdf")
                ? new FileExport("evidence-" + fileName(jobId) + ".pdf", "application/pdf", EvidencePdf.render(r))
                : new FileExport("evidence-" + fileName(jobId) + ".json", "application/json", json(r)));
    }

    /**
     * {@code records/<jobId>.json} per matching record, {@code platform.json} (the whole platform
     * stream) and {@code manifest.json}: every file with its sha256, and an HMAC-SHA256 signature
     * (under the sealing key) of the canonical JSON of that file list, so a pack can be checked for
     * completeness and tampering. The zip is written to a temporary file as it is built (each
     * record serialised straight into it, its sha256 taken on the way), so a pack of any size costs
     * one record in memory, never the whole zip; the caller streams the file and discards it.
     */
    @Override
    public Mono<SpooledFile> exportPack(FilterSpec spec) {
        return Mono.fromCallable(() -> spool(spec)).subscribeOn(Schedulers.boundedElastic());
    }

    private SpooledFile spool(FilterSpec spec) throws IOException {
        EvidenceFilter filter = spec == null ? EvidenceFilter.ALL
                : EvidenceFilter.parse(spec.workflow(), spec.from(), spec.to(), spec.requester());
        List<EvidenceRecord> records = new ArrayList<>(store.records(filter).stream().filter(r -> !r.platform()).toList());
        store.record(EvidenceRecord.PLATFORM).ifPresent(records::add);
        Files.createDirectories(spool);
        sweep();
        Path path = Files.createTempFile(spool, "evidence-pack-", ".zip");
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter().without(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        try {
            List<Map<String, Object>> files = new ArrayList<>();
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 64 * 1024))) {
                for (EvidenceRecord r : records) {
                    String name = r.platform() ? PLATFORM_FILE : RECORDS_DIR + fileName(r.jobId()) + ".json";
                    zip.putNextEntry(new ZipEntry(name));
                    String sha256 = writeHashed(writer, r, zip);
                    zip.closeEntry();
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("name", name);
                    file.put("jobId", r.jobId());
                    file.put("sha256", sha256);
                    file.put("latestHash", r.latestHash());
                    file.put("sealed", r.sealed() != null);
                    files.add(file);
                }
                Map<String, Object> manifest = new LinkedHashMap<>();
                manifest.put("generatedAt", clock.instant().toString());
                manifest.put("filter", filterOf(filter));
                manifest.put("files", files);
                manifest.put("keyId", signer.keyId());
                manifest.put("algorithm", "HMAC-SHA256 over the canonical JSON (sorted keys, no whitespace) of files");
                manifest.put("signature", signer.sign(HashChain.canonical(files)));
                zip.putNextEntry(new ZipEntry(MANIFEST));
                writer.writeValue(zip, manifest);
                zip.closeEntry();
            }
            return new SpooledFile("evidence-pack-" + clock.instant().toString().replace(':', '-') + ".zip", "application/zip", path, Files.size(path));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    /** Writes {@code value} as JSON to {@code out} (left open) and answers the sha256 of what was written. */
    private static String writeHashed(ObjectWriter writer, Object value, OutputStream out) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
        writer.writeValue(new DigestOutputStream(out, sha), value);
        return HexFormat.of().formatHex(sha.digest());
    }

    /**
     * Deletes the packs of the spool older than {@link #LEFTOVER}: one is left behind only when its
     * client went away between the spooling and the sending of the body.
     */
    private void sweep() {
        FileTime cutoff = FileTime.from(clock.instant().minus(LEFTOVER));
        try (DirectoryStream<Path> packs = Files.newDirectoryStream(spool, SPOOL_GLOB)) {
            for (Path pack : packs) {
                try {
                    if (Files.getLastModifiedTime(pack).compareTo(cutoff) < 0) {
                        Files.deleteIfExists(pack);
                        log.info("deleted the evidence pack {} left unsent for over {}", pack.getFileName(), LEFTOVER);
                    }
                } catch (IOException e) {
                    log.warn("cannot delete the leftover evidence pack {}: {}", pack, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("cannot sweep the evidence pack spool {}: {}", spool, e.getMessage());
        }
    }

    @Override
    public Mono<EvidenceRecord> shared(String token) {
        return Mono.defer(() -> {
            ShareToken link = store.findShareToken(token).orElseThrow(() -> new NotFoundException("share link", token));
            if (link.expired(clock.instant())) {
                throw new ResponseStatusException(HttpStatus.GONE, "share link expired on " + link.expiresAt());
            }
            return record(link.jobId());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, Object> filterOf(EvidenceFilter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workflow", f.workflow());
        m.put("from", f.from() == null ? null : f.from().toString());
        m.put("to", f.to() == null ? null : f.to().toString());
        m.put("requester", f.requester());
        return m;
    }

    private byte[] json(EvidenceRecord record) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise evidence record " + record.jobId(), e);
        }
    }

    /** {@code release:rel_x} becomes {@code release-rel_x}: safe in a file name and a header. */
    static String fileName(String jobId) {
        return jobId == null ? "record" : jobId.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
