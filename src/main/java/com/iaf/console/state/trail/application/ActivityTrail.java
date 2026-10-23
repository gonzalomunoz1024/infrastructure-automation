package com.iaf.console.state.trail.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.application.ResourceEventPublisher;
import com.iaf.console.state.inventory.application.SensitiveData;
import com.iaf.console.state.inventory.domain.ActivityQuery;
import com.iaf.console.state.inventory.domain.EventChain;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.trail.domain.Digest;
import com.iaf.console.state.trail.domain.DigestRoot;
import com.iaf.console.state.trail.domain.DigestSigner;
import com.iaf.console.state.trail.domain.EvidencePack;
import com.iaf.console.state.trail.ports.outbound.TrailDigestPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reads of the trail: an account's activity newest first (each event with its chain links),
 * a resource's chain checked, the digests, the public keys, and the evidence pack of a period.
 */
public final class ActivityTrail {

    /** The longest window one evidence pack covers. */
    public static final Duration MAX_PACK_SPAN = Duration.ofDays(31);
    /** The most events one evidence pack carries. */
    public static final int MAX_PACK_EVENTS = 50_000;
    /** The longest log a chain check reads. */
    static final int MAX_LOG = 100_000;

    private final ResourceEventStorePort events;
    private final TrailDigestPort digests;
    private final TrailDigester digester;
    private final DigestSigner signer;
    private final Clock clock;

    public ActivityTrail(ResourceEventStorePort events, TrailDigestPort digests, TrailDigester digester, DigestSigner signer, Clock clock) {
        this.events = events;
        this.digests = digests;
        this.digester = digester;
        this.signer = signer;
        this.clock = clock;
    }

    /** One page of the account's events, newest first, masked, each as its envelope with {@code chain: {previous, hash}}. */
    public Mono<Page<ObjectNode>> activity(AccountScope scope, ActivityQuery query) {
        return events.findActivity(scope, query).collectList().map(fetched -> {
            Page<ResourceEvent> page = Page.from(fetched, query.limit(), e -> new Cursor(Mongo.nanos(e.at()), ActivityQuery.idOf(e)));
            return page.map(ActivityTrail::entry);
        });
    }

    static ObjectNode entry(ResourceEvent e) {
        ObjectNode entry = Json.MAPPER.valueToTree(ResourceEventPublisher.envelope(SensitiveData.mask(e), null));
        ObjectNode chain = entry.putObject("chain");
        Json.putIf(chain, "previous", e.previous());
        Json.putIf(chain, "hash", e.hash());
        return entry;
    }

    /** A resource's whole log checked link by link; 404 when the wfrn has no log in the scope. */
    public Mono<EventChain.Check> verify(AccountScope scope, String wfrn) {
        ResourceIdentity id = ResourceIdentity.of(wfrn);
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        return events.findEvents(scope, wfrn, 0, MAX_LOG).collectList().flatMap(log -> log.isEmpty()
                ? Mono.error(NotFoundException.of("resource", wfrn))
                : Mono.just(EventChain.verify(log)));
    }

    public Flux<Digest> digests(AccountScope scope, Instant from, Instant to) {
        return digests.listDigests(scope, from, to);
    }

    /** The public halves of the key that signs now and of every key that signed a digest, as a JWK set. */
    public Mono<Map<String, Object>> keys() {
        return digests.listKeys().collectMap(k -> String.valueOf(k.get("kid")), k -> k).map(kept -> {
            Map<String, Map<String, Object>> all = new java.util.TreeMap<>(kept);
            all.putIfAbsent(signer.keyId(), signer.publicJwk());
            return Map.<String, Object>of("keys", List.copyOf(all.values()));
        });
    }

    /**
     * The evidence pack of a window, widened to whole periods so every digest in it can be checked:
     * the stored events, the digests, the keys, the verification and the jobs' evidence links.
     */
    public Mono<EvidencePack> evidence(AccountScope scope, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw invalid("/from", "from and to name a window, from first");
        }
        Instant start = digester.periodOf(from);
        Instant end = digester.periodOf(to).equals(to) ? to : digester.periodOf(to).plus(digester.period());
        if (Duration.between(start, end).compareTo(MAX_PACK_SPAN) > 0) {
            throw invalid("/to", "a pack covers at most " + MAX_PACK_SPAN.toDays() + " days");
        }
        Mono<List<ResourceEvent>> window = TrailDigester.window(events, scope, start, end, MAX_PACK_EVENTS + 1).collectList().flatMap(list -> list.size() > MAX_PACK_EVENTS
                ? Mono.error(invalid("/to", "the window holds more than " + MAX_PACK_EVENTS + " events; ask for a shorter one"))
                : Mono.just(list));
        Mono<List<Digest>> sealed = digests.listDigests(scope, start, end).collectList();
        Mono<Map<String, JsonNode>> keys = digests.listKeys().collectMap(k -> String.valueOf(k.get("kid")), k -> Json.MAPPER.<JsonNode>valueToTree(k));
        return Mono.zip(window, sealed, keys).flatMap(t -> {
            List<ResourceEvent> log = t.getT1();
            List<Digest> found = t.getT2();
            Mono<String> before = found.isEmpty() ? Mono.just("")
                    : digests.findLatestBefore(scope, found.get(0).from()).map(d -> Objects.toString(d.hash(), "")).defaultIfEmpty("");
            return before.map(previous -> pack(scope, start, end, log, found, t.getT3(), previous.isEmpty() ? null : previous));
        });
    }

    private EvidencePack pack(AccountScope scope, Instant from, Instant to, List<ResourceEvent> log, List<Digest> found, Map<String, JsonNode> keys,
            String previousDigest) {
        List<EvidencePack.Finding> problems = new ArrayList<>();
        Map<String, String> hashOf = new HashMap<>();
        for (ResourceEvent e : log) {
            if (e.hash() != null) {
                if (!EventChain.hash(e).equals(e.hash())) {
                    problems.add(new EvidencePack.Finding(e.key(), "the hash does not match the event"));
                }
                hashOf.put(e.wfrn() + "#" + e.version(), e.hash());
            }
        }
        for (ResourceEvent e : log) {
            String prior = hashOf.get(e.wfrn() + "#" + (e.version() - 1));
            if (e.hash() != null && prior != null && !prior.equals(e.previous())) {
                problems.add(new EvidencePack.Finding(e.key(), "previous is not the hash of the event before"));
            }
        }
        Set<String> covered = new LinkedHashSet<>();
        String previous = previousDigest;
        for (Digest d : found) {
            DigestRoot root = new DigestRoot();
            log.stream().filter(e -> !e.at().isBefore(d.from()) && e.at().isBefore(d.to())).forEach(e -> {
                root.add(e);
                covered.add(e.key());
            });
            if (root.count() != d.count() || !root.root().equals(d.root())) {
                problems.add(new EvidencePack.Finding(d.id(), "the events of the period do not add up to the digest's root"));
            }
            if (!d.computeHash().equals(d.hash())) {
                problems.add(new EvidencePack.Finding(d.id(), "the digest's hash does not match its fields"));
            }
            JsonNode key = keys.get(d.keyId());
            if (key == null || !DigestSigner.verify(key, d.hash(), d.signature())) {
                problems.add(new EvidencePack.Finding(d.id(), key == null ? "no public key " + d.keyId() + " is kept" : "the signature does not verify"));
            }
            if (!Objects.equals(previous, d.previous())) {
                problems.add(new EvidencePack.Finding(d.id(), "previous is not the hash of the account's digest before"));
            }
            previous = d.hash();
        }
        Set<String> jobs = new LinkedHashSet<>();
        log.stream().map(ResourceEvent::jobId).filter(Objects::nonNull).forEach(jobs::add);
        List<EvidencePack.JobEvidence> evidence = jobs.stream().map(j -> new EvidencePack.JobEvidence(j, "/api/v1/audit/" + j)).toList();
        int unsealed = (int) log.stream().filter(e -> !covered.contains(e.key())).count();
        EvidencePack.Verification verification = new EvidencePack.Verification(problems.isEmpty(), log.size(), found.size(), unsealed, problems);
        List<Map<String, Object>> keyList = keys.values().stream().map(k -> Json.MAPPER.convertValue(k, Json.MAP)).toList();
        return new EvidencePack(scope.accountId(), scope.region(), from, to, clock.instant(), EvidencePack.CANONICALIZATION,
                log.stream().map(EventChain::stored).toList(), found, keyList, verification, evidence);
    }

    private static InvalidInputException invalid(String pointer, String message) {
        return new InvalidInputException(message, List.of(Problem.error("range", pointer, message)));
    }
}
