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
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@Service
@RequiredArgsConstructor
public class AuditQueryHandler implements AuditQueries {

    static final String MANIFEST = "manifest.json";
    static final String RECORDS_DIR = "records/";

    private final EvidenceStore store;
    private final AuditSigner signer;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Override
    public Flux<AuditRow> list(String workflow, String from, String to, String requester) {
        return Flux.defer(() -> Flux.fromIterable(store.records(EvidenceFilter.parse(workflow, from, to, requester))))
                .map(r -> new AuditRow(r.jobId(), r.workflow(), r.requester(), r.channel(), r.at(), r.entries().size(), r.status()));
    }

    @Override
    public Mono<EvidenceRecord> record(String jobId) {
        return Mono.defer(() -> Mono.justOrEmpty(store.record(jobId)))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("evidence record", jobId)));
    }

    @Override
    public Mono<Verification> verify(String jobId) {
        return record(jobId).map(r -> {
            int broken = HashChain.verify(r.entries());
            String seal = null;
            if (r.sealed() != null) {
                if (!signer.keyId().equals(r.sealed().keyId())) {
                    seal = "unverifiable";
                } else {
                    seal = signer.verify(r.latestHash(), r.sealed().signature()) ? "verified" : "mismatch";
                }
            }
            boolean valid = broken < 0 && !"mismatch".equals(seal);
            return new Verification(valid, r.entries().size(), broken < 0 ? null : broken, seal);
        });
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
     * {@code records/<jobId>.json} per matching record plus {@code manifest.json}: every file with
     * its sha256, and an HMAC-SHA256 signature (under the sealing key) of the canonical JSON of
     * that file list, so a pack can be checked for completeness and tampering.
     */
    @Override
    public Mono<FileExport> exportPack(FilterSpec spec) {
        return Mono.fromCallable(() -> {
            EvidenceFilter filter = spec == null ? EvidenceFilter.ALL
                    : EvidenceFilter.parse(spec.workflow(), spec.from(), spec.to(), spec.requester());
            List<EvidenceRecord> records = store.records(filter);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            List<Map<String, Object>> files = new ArrayList<>();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (EvidenceRecord r : records) {
                    byte[] content = json(r);
                    String name = RECORDS_DIR + fileName(r.jobId()) + ".json";
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
