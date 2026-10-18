package com.iaf.platform.audit.application;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceFilter;
import com.iaf.platform.audit.domain.EvidencePdf;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.domain.HashChain;
import com.iaf.platform.audit.domain.ShareToken;
import com.iaf.platform.audit.domain.Signatures;
import com.iaf.platform.audit.ports.inbound.AuditQueries;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.domain.NotFoundException;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reads evidence records and turns them into rows, verifications, files and packs. */
@Primary
@Service
@RequiredArgsConstructor
public class AuditQueryHandler implements AuditQueries {

    static final String MANIFEST = "manifest.json";
    static final String RECORDS_DIR = "records/";
    /** The whole platform stream, whatever the filter: a chain is only checkable whole. */
    static final String PLATFORM_FILE = "platform.json";

    private final EvidenceStore store;
    private final AuditSigner signer;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Override
    public Flux<AuditRow> list(String workflow, String from, String to, String requester) {
        return Flux.defer(() -> Flux.fromIterable(store.records(EvidenceFilter.parse(workflow, from, to, requester))))
                .filter(r -> !r.platform())
                .map(r -> new AuditRow(r.jobId(), r.workflow(), r.requester(), r.channel(), r.at(), r.entries().size(), r.status()));
    }

    @Override
    public Mono<EvidenceRecord> record(String jobId) {
        return Mono.defer(() -> Mono.justOrEmpty(store.record(jobId)))
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
        });
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
     * completeness and tampering.
     */
    @Override
    public Mono<FileExport> exportPack(FilterSpec spec) {
        return Mono.fromCallable(() -> {
            EvidenceFilter filter = spec == null ? EvidenceFilter.ALL
                    : EvidenceFilter.parse(spec.workflow(), spec.from(), spec.to(), spec.requester());
            List<EvidenceRecord> records = new ArrayList<>(store.records(filter).stream().filter(r -> !r.platform()).toList());
            store.record(EvidenceRecord.PLATFORM).ifPresent(records::add);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            List<Map<String, Object>> files = new ArrayList<>();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (EvidenceRecord r : records) {
                    byte[] content = json(r);
                    String name = r.platform() ? PLATFORM_FILE : RECORDS_DIR + fileName(r.jobId()) + ".json";
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(content);
                    zip.closeEntry();
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("name", name);
                    file.put("jobId", r.jobId());
                    file.put("sha256", Signatures.sha256Hex(content));
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
                zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zip.closeEntry();
            }
            return new FileExport("evidence-pack-" + clock.instant().toString().replace(':', '-') + ".zip", "application/zip", bytes.toByteArray());
        });
    }

    @Override
    public Mono<EvidenceRecord> shared(String token) {
        return Mono.defer(() -> {
            ShareToken link = store.findShareToken(token).orElseThrow(() -> new NotFoundException("share link", token));
            if (link.expired(clock.instant())) {
                throw new ResponseStatusException(HttpStatus.GONE, "share link expired on " + link.expiresAt());
            }
            return record(link.jobId());
        });
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
