package com.iaf.console.fake.audit.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.audit.domain.EvidenceChain;
import com.iaf.console.fake.audit.domain.PdfWriter;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.kit.events.EventEnvelope;

/**
 * The evidence records (P-12): one hash chain per job, appended as the job runs and sealed when it
 * closes, listed with filters and scoped to the caller, exported one at a time or as a pack.
 */
public final class Audit {

    /** The filters of a list or a pack. */
    public record Filter(String workflow, Instant from, Instant to, String requester, String account, String wfrn) {

        public static Filter of(JsonNode body) {
            return new Filter(text(body, "workflow"), instant(text(body, "from"), false), instant(text(body, "to"), true), text(body, "requester"),
                    text(body, "account"), text(body, "wfrn"));
        }

        private static String text(JsonNode body, String field) {
            return body == null ? null : body.path(field).asText(null);
        }

        /** An ISO instant, or a date meaning the start of that day (the end of it when exclusive). */
        public static Instant instant(String value, boolean exclusiveEnd) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                try {
                    LocalDate date = LocalDate.parse(value);
                    return (exclusiveEnd ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException again) {
                    throw ApiException.badRequest("'" + value + "' is neither an ISO instant nor a date");
                }
            }
        }

        boolean matches(EvidenceChain chain) {
            if (workflow != null && !workflow.equals(chain.workflow())) {
                return false;
            }
            if (requester != null && !requester.equals(chain.requester())) {
                return false;
            }
            if (account != null && !account.equals(chain.accountId())) {
                return false;
            }
            if (wfrn != null && !wfrn.equals(chain.wfrn())) {
                return false;
            }
            if (from != null && chain.openedAt().isBefore(from)) {
                return false;
            }
            return to == null || chain.openedAt().isBefore(to);
        }
    }

    private final Map<String, EvidenceChain> chains = new ConcurrentHashMap<>();
    private final FakeClock clock;
    private final EventFeed events;

    public Audit(FakeClock clock, EventFeed events) {
        this.clock = clock;
        this.events = events;
    }

    /** Opens a job's record. */
    public EvidenceChain open(EvidenceChain chain) {
        chains.put(chain.jobId(), chain);
        return chain;
    }

    public Optional<EvidenceChain> chain(String jobId) {
        return Optional.ofNullable(chains.get(jobId));
    }

    /** Appends an entry to a job's record; nothing happens for a job without one. */
    public void append(String jobId, String kind, String actor, ObjectNode data) {
        EvidenceChain chain = chains.get(jobId);
        if (chain != null) {
            chain.append(kind, actor, data, clock.now());
        }
    }

    /** Seals a job's record and emits {@code audit.sealed}. */
    public void close(String jobId, String status, String actor, EventEnvelope.Context context, EventEnvelope.OnBehalfOf onBehalfOf) {
        EvidenceChain chain = chains.get(jobId);
        if (chain == null || chain.sealed()) {
            return;
        }
        Instant at = clock.now();
        ObjectNode closing = Json.object();
        closing.put("status", status);
        chain.append("closed", actor, closing, at);
        EvidenceChain.Seal seal = chain.close(status, at);
        ObjectNode data = Json.object();
        data.put("jobId", jobId);
        data.put("keyId", seal.keyId());
        events.emit("audit.sealed", "audit.sealed:" + jobId + ":" + seal.seq(), at, "audit", jobId, actor, data, context, onBehalfOf, null);
    }

    /** Whether a caller may read a record: admins and unbound accounts all, a console account its account's, a person their own or their team's. */
    public static boolean sees(Caller caller, EvidenceChain chain) {
        if (caller.seesEverything()) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return caller.consoleAccount().equals(chain.accountId());
        }
        return caller.id().equals(chain.requester()) || (chain.ownerTeam() != null && caller.teams().contains(chain.ownerTeam()))
                || (caller.accountId() != null && caller.accountId().equals(chain.accountId()));
    }

    /** The rows a filter selects that the caller may see, newest first. */
    public List<ObjectNode> rows(Filter filter, Caller caller) {
        List<ObjectNode> out = new ArrayList<>();
        for (EvidenceChain chain : selected(filter, caller)) {
            out.add(chain.row());
        }
        return out;
    }

    private List<EvidenceChain> selected(Filter filter, Caller caller) {
        return chains.values().stream()
                .filter(chain -> sees(caller, chain) && filter.matches(chain))
                .sorted(Comparator.comparing(EvidenceChain::openedAt).reversed().thenComparing(EvidenceChain::jobId))
                .toList();
    }

    /** A record the caller may see, else empty. */
    public Optional<EvidenceChain> visible(String jobId, Caller caller) {
        return chain(jobId).filter(chain -> sees(caller, chain));
    }

    /** The record as a file: JSON, or a small PDF. */
    public byte[] export(EvidenceChain chain, String format) {
        if ("pdf".equals(format)) {
            return PdfWriter.of(chain.lines());
        }
        return Json.write(chain.document()).getBytes(StandardCharsets.UTF_8);
    }

    /** A zip with one JSON per matching record, the platform's events about those jobs, and a signed manifest. */
    public byte[] pack(Filter filter, Caller caller) {
        List<EvidenceChain> selected = selected(filter, caller);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectNode manifest = Json.object();
        manifest.put("generatedAt", clock.now().toString());
        manifest.put("records", selected.size());
        ObjectNode files = manifest.putObject("files");
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (EvidenceChain chain : selected) {
                byte[] record = Json.write(chain.document()).getBytes(StandardCharsets.UTF_8);
                String name = "records/" + chain.jobId() + ".json";
                add(zip, name, record);
                files.put(name, Json.sha256(record));
            }
            List<ObjectNode> about = new ArrayList<>();
            List<String> jobIds = selected.stream().map(EvidenceChain::jobId).toList();
            for (EventEnvelope event : events.list(0, Integer.MAX_VALUE, List.of(), caller)) {
                if (jobIds.contains(event.aggregateId())) {
                    about.add(Json.mapper().valueToTree(event));
                }
            }
            byte[] eventsFile = Json.write(about).getBytes(StandardCharsets.UTF_8);
            add(zip, "events.json", eventsFile);
            files.put("events.json", Json.sha256(eventsFile));
            String digest = Json.hash(files);
            manifest.put("digest", digest);
            manifest.put("signature", Json.sha256("fake-seal|" + EvidenceChain.KEY_ID + "|" + digest));
            manifest.put("keyId", EvidenceChain.KEY_ID);
            add(zip, "manifest.json", Json.write(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    public int size() {
        return chains.size();
    }
}
