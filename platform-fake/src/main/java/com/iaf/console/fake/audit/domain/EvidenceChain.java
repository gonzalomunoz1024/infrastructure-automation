package com.iaf.console.fake.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.runs.domain.Fingerprints;

/**
 * A job's evidence record: a hash chain of entries, each hashing the previous hash and its own
 * canonical content, sealed when the job closes. The seal is a hash over the latest hash under a
 * named key, which stands in for the platform's signature here.
 */
public final class EvidenceChain {

    /** How long a record is kept. */
    public static final Duration RETENTION = Duration.ofDays(400);
    public static final String KEY_ID = "platform-fake";
    private static final String GENESIS = "genesis";

    /** One hashed entry. */
    public record Entry(long seq, Instant at, String kind, String actor, ObjectNode data, String hash, String previous) {

        public ObjectNode document() {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("seq", seq);
            out.put("at", at.toString());
            out.put("kind", kind);
            if (actor != null) {
                out.put("actor", actor);
            }
            out.set("data", data.deepCopy());
            out.put("hash", hash);
            out.put("previous", previous);
            return out;
        }
    }

    /** A seal over the chain at a point. */
    public record Seal(Instant at, String signature, String keyId, long seq, String hash) {

        public ObjectNode document() {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            out.put("at", at.toString());
            out.put("signature", signature);
            out.put("keyId", keyId);
            out.put("seq", seq);
            out.put("hash", hash);
            return out;
        }
    }

    private final String jobId;
    private final String workflow;
    private final String version;
    private final String requester;
    private final String channel;
    private final String ownerTeam;
    private final String accountId;
    private final String region;
    private final String wfrn;
    private final String actor;
    private final ObjectNode onBehalfOf;
    private final String changeId;
    private final Instant openedAt;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Seal> checkpoints = new ArrayList<>();
    private String latestHash = GENESIS;
    private Seal sealed;
    private String status = "open";

    public EvidenceChain(String jobId, String workflow, String version, String requester, String channel, String ownerTeam, String accountId,
            String region, String wfrn, String actor, ObjectNode onBehalfOf, String changeId, Instant openedAt) {
        this.jobId = jobId;
        this.workflow = workflow;
        this.version = version;
        this.requester = requester;
        this.channel = channel;
        this.ownerTeam = ownerTeam;
        this.accountId = accountId;
        this.region = region;
        this.wfrn = wfrn;
        this.actor = actor;
        this.onBehalfOf = onBehalfOf;
        this.changeId = changeId;
        this.openedAt = openedAt;
    }

    public String jobId() {
        return jobId;
    }

    public String workflow() {
        return workflow;
    }

    public String requester() {
        return requester;
    }

    public String ownerTeam() {
        return ownerTeam;
    }

    public String accountId() {
        return accountId;
    }

    public String wfrn() {
        return wfrn;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public synchronized String status() {
        return status;
    }

    public synchronized boolean sealed() {
        return sealed != null;
    }

    public synchronized String latestHash() {
        return latestHash;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Appends an entry; the console context (P-12) is added to its data when the job has one. */
    public synchronized Entry append(String kind, String by, ObjectNode data, Instant at) {
        ObjectNode content = data == null ? JsonNodeFactory.instance.objectNode() : data.deepCopy();
        if (onBehalfOf != null && !content.has("onBehalfOf")) {
            content.set("onBehalfOf", onBehalfOf.deepCopy());
        }
        if (actor != null && !content.has("actor")) {
            content.put("actor", actor);
        }
        if (accountId != null && !content.has("accountId")) {
            content.put("accountId", accountId);
        }
        if (region != null && !content.has("region")) {
            content.put("region", region);
        }
        if (wfrn != null && !content.has("wfrn")) {
            content.put("wfrn", wfrn);
        }
        long seq = entries.size() + 1L;
        String hash = hashOf(latestHash, seq, at, kind, by, content);
        Entry entry = new Entry(seq, at, kind, by, content, hash, latestHash);
        entries.add(entry);
        latestHash = hash;
        return entry;
    }

    /** Seals the chain when the job closes. */
    public synchronized Seal close(String finalStatus, Instant at) {
        this.status = finalStatus;
        this.sealed = new Seal(at, signature(KEY_ID, latestHash), KEY_ID, entries.size(), latestHash);
        return sealed;
    }

    /** Adds a checkpoint seal without closing. */
    public synchronized Seal checkpoint(Instant at) {
        Seal seal = new Seal(at, signature(KEY_ID, latestHash), KEY_ID, entries.size(), latestHash);
        checkpoints.add(seal);
        return seal;
    }

    /** Recomputes the chain and checks the seals. */
    public synchronized ObjectNode verify() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        String previous = GENESIS;
        Integer brokenAt = null;
        for (Entry entry : entries) {
            String expected = hashOf(previous, entry.seq(), entry.at(), entry.kind(), entry.actor(), entry.data());
            if (!expected.equals(entry.hash()) || !previous.equals(entry.previous())) {
                brokenAt = (int) entry.seq();
                break;
            }
            previous = entry.hash();
        }
        boolean chainHolds = brokenAt == null && previous.equals(latestHash);
        out.put("valid", chainHolds && (sealed == null || sealHolds(sealed)));
        out.put("entries", entries.size());
        if (brokenAt != null) {
            out.put("brokenAt", brokenAt);
        }
        out.put("seal", sealed == null ? "unverifiable" : sealHolds(sealed) ? "verified" : "mismatch");
        var seals = out.putArray("seals");
        for (Seal checkpoint : checkpoints) {
            seals.add(sealCheck("checkpoint", checkpoint));
        }
        if (sealed != null) {
            seals.add(sealCheck("seal", sealed));
        }
        return out;
    }

    private ObjectNode sealCheck(String kind, Seal seal) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("kind", kind);
        out.put("at", seal.at().toString());
        out.put("seq", (int) seal.seq());
        out.put("keyId", seal.keyId());
        out.put("status", sealHolds(seal) ? "verified" : "mismatch");
        return out;
    }

    private boolean sealHolds(Seal seal) {
        if (seal.seq() > entries.size()) {
            return false;
        }
        String hashAtSeq = seal.seq() == 0 ? GENESIS : entries.get((int) seal.seq() - 1).hash();
        return hashAtSeq.equals(seal.hash()) && signature(seal.keyId(), seal.hash()).equals(seal.signature());
    }

    /** The EvidenceRecord document. */
    public synchronized ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("jobId", jobId);
        out.put("workflow", workflow);
        out.put("version", version);
        out.put("requester", requester);
        out.put("channel", channel);
        var entriesNode = out.putArray("entries");
        entries.forEach(e -> entriesNode.add(e.document()));
        out.put("latestHash", latestHash);
        if (sealed != null) {
            out.set("sealed", sealed.document());
        }
        out.putObject("retention").put("until", openedAt.plus(RETENTION).toString());
        if (changeId != null) {
            ObjectNode change = out.putObject("change");
            change.put("id", changeId);
            change.put("url", "https://changes.example.com/" + changeId);
        }
        var checkpointsNode = out.putArray("checkpoints");
        checkpoints.forEach(c -> checkpointsNode.add(c.document()));
        return out;
    }

    /** The AuditRow document. */
    public synchronized ObjectNode row() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("jobId", jobId);
        out.put("workflow", workflow);
        out.put("requester", requester);
        out.put("channel", channel);
        out.put("at", openedAt.toString());
        out.put("entries", entries.size());
        out.put("status", status);
        if (accountId != null) {
            out.put("accountId", accountId);
        }
        if (wfrn != null) {
            out.put("wfrn", wfrn);
        }
        return out;
    }

    /** The lines of the printed record. */
    public synchronized List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Evidence record " + jobId);
        lines.add("Workflow " + workflow + " " + version + ", requested by " + requester + " through " + channel);
        if (accountId != null) {
            lines.add("Account " + accountId + (region == null ? "" : ", region " + region) + (wfrn == null ? "" : ", resource " + wfrn));
        }
        lines.add("Status " + status + ", " + entries.size() + " entries, latest hash " + latestHash);
        for (Entry entry : entries) {
            lines.add(entry.seq() + ". " + entry.at() + " " + entry.kind() + (entry.actor() == null ? "" : " by " + entry.actor()));
            lines.add("    " + abbreviate(Fingerprints.canonical(entry.data()), 110));
            lines.add("    hash " + entry.hash());
        }
        if (sealed != null) {
            lines.add("Sealed " + sealed.at() + " with " + sealed.keyId() + ": " + sealed.signature());
        }
        return lines;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    static String hashOf(String previous, long seq, Instant at, String kind, String actor, JsonNode data) {
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        content.put("seq", seq);
        content.put("at", at.toString());
        content.put("kind", kind);
        if (actor != null) {
            content.put("actor", actor);
        }
        content.set("data", data);
        return sha256(previous + "|" + Fingerprints.canonical(content));
    }

    static String signature(String keyId, String hash) {
        return sha256("fake-seal|" + keyId + "|" + hash);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
