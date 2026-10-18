package com.iaf.platform.audit.adapters.inbound;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.audit.application.AuditCommandHandler;
import com.iaf.platform.audit.application.AuditQueryHandler;
import com.iaf.platform.audit.domain.EvidenceRecord;
import com.iaf.platform.audit.ports.inbound.AuditCommands;
import com.iaf.platform.audit.ports.inbound.AuditQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@code /api/v1/audit}: GET reads evidence, POST seals, shares and packs it. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerAuditAdapter implements AuditCommands, AuditQueries {

    private final AuditCommandHandler commands;
    private final AuditQueryHandler queries;

    @Override
    @GetMapping("/audit")
    public Flux<AuditRow> list(@RequestParam(required = false) String workflow, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String requester) {
        return queries.list(workflow, from, to, requester);
    }

    @Override
    @GetMapping("/audit/platform")
    public Mono<PlatformStream> platform(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String aggregate, @RequestParam(required = false) String actor) {
        return queries.platform(from, to, aggregate, actor);
    }

    @Override
    @GetMapping({"/audit/{jobId}", "/jobs/{jobId}/evidence"})
    public Mono<EvidenceRecord> record(@PathVariable String jobId) {
        return queries.record(jobId);
    }

    @Override
    @GetMapping("/audit/{jobId}:verify")
    public Mono<Verification> verify(@PathVariable String jobId) {
        return queries.verify(jobId);
    }

    @Override
    public Mono<FileExport> export(String jobId, String format) {
        return queries.export(jobId, format);
    }

    @GetMapping("/audit/{jobId}/export")
    public Mono<ResponseEntity<byte[]>> exportFile(@PathVariable String jobId, @RequestParam(defaultValue = "json") String format) {
        return export(jobId, format).map(RestControllerAuditAdapter::download);
    }

    @Override
    public Mono<FileExport> exportPack(FilterSpec filter) {
        return queries.exportPack(filter);
    }

    @PostMapping("/audit:export-pack")
    public Mono<ResponseEntity<byte[]>> exportPackFile(@RequestBody(required = false) ExportPackRequest request) {
        return exportPack(request == null ? null : request.filter()).map(RestControllerAuditAdapter::download);
    }

    @Override
    @PostMapping("/audit/{jobId}:share")
    public Mono<ShareLink> share(@PathVariable String jobId) {
        return commands.share(jobId);
    }

    @Override
    @GetMapping("/audit/shared/{token}")
    public Mono<EvidenceRecord> shared(@PathVariable String token) {
        return queries.shared(token);
    }

    @Override
    @PostMapping("/audit:seal")
    public Mono<SealResult> seal() {
        return commands.seal();
    }

    private static ResponseEntity<byte[]> download(FileExport file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}
