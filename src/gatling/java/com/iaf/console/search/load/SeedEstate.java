package com.iaf.console.search.load;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.search.index.adapters.opensearch.OpenSearchIndex;
import com.iaf.console.search.index.domain.IndexedResource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fills OpenSearch with the {@link Estate} through the indexer's own adapter, so the documents are
 * exactly the ones the service writes: the alias and its mapping, routing by account, external
 * versions. While it loads, the index refreshes never and keeps no replica; both come back after.
 * With {@code load.mode=trickle} it then keeps changing the estate at {@code load.trickle} writes a
 * second for {@code load.trickleFor}, the creations and updates of a busy day, so a run's searches
 * meet refreshes as they will in production.
 *
 * <pre>
 *   ./gradlew seedEstate -Pload.size=10000000 -Pload.accounts=2000 -Pload.opensearch=http://localhost:9200
 *   ./gradlew seedEstate -Pload.mode=trickle -Pload.trickle=10 -Pload.trickleFor=PT15M
 * </pre>
 */
public final class SeedEstate {

    private SeedEstate() {
    }

    public static void main(String[] args) {
        Estate estate = Estate.fromProperties();
        String url = System.getProperty("load.opensearch", "http://localhost:9200");
        String alias = System.getProperty("load.alias", "console-resources");
        String mode = System.getProperty("load.mode", "seed");
        WebClient web = WebClient.builder().baseUrl(url)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                .build();
        OpenSearchIndex index = new OpenSearchIndex(web, alias, "false", Clock.systemUTC());
        index.ensureReady().block(Duration.ofMinutes(1));
        if ("trickle".equals(mode)) {
            trickle(estate, index, Integer.getInteger("load.trickle", 10), Duration.parse(System.getProperty("load.trickleFor", "PT10M")));
        } else {
            seed(estate, index, web, Integer.getInteger("load.batch", 2_000), Integer.getInteger("load.parallel", 4));
        }
    }

    static void seed(Estate estate, OpenSearchIndex index, WebClient web, int batch, int parallel) {
        System.out.printf("seeding %,d resources in %,d accounts into %s (the largest account holds %,d)%n", estate.size(), estate.accounts(),
                index.concreteIndex(), estate.accountSize(0));
        settings(web, index.concreteIndex(), "{\"index\":{\"refresh_interval\":\"-1\",\"number_of_replicas\":0}}");
        long started = System.nanoTime();
        AtomicLong written = new AtomicLong();
        AtomicLong reported = new AtomicLong(started);
        long batches = (estate.size() + batch - 1) / batch;
        Flux.range(0, (int) batches)
                .flatMap(b -> {
                    long from = b.longValue() * batch;
                    return Mono.fromCallable(() -> documents(estate, from, Math.min(estate.size(), from + batch))).flatMap(index::indexAll);
                }, parallel)
                .doOnNext(n -> {
                    long total = written.addAndGet(n);
                    long now = System.nanoTime();
                    long last = reported.get();
                    if (now - last > 10_000_000_000L && reported.compareAndSet(last, now)) {
                        double seconds = (now - started) / 1e9;
                        System.out.printf("  %,d written, %,.0f a second%n", total, total / seconds);
                    }
                })
                .blockLast();
        settings(web, index.concreteIndex(), "{\"index\":{\"refresh_interval\":\"1s\",\"number_of_replicas\":"
                + System.getProperty("load.replicas", "1") + "}}");
        web.post().uri("/{index}/_refresh", index.concreteIndex()).retrieve().toBodilessEntity().block(Duration.ofMinutes(10));
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf("seeded %,d documents in %.0f s (%,.0f a second); %,d in the index%n", written.get(), seconds, written.get() / seconds,
                index.count().block(Duration.ofMinutes(1)));
    }

    static List<IndexedResource> documents(Estate estate, long from, long to) {
        List<IndexedResource> documents = new ArrayList<>((int) (to - from));
        for (long i = from; i < to; i++) {
            documents.add(estate.resource(i));
        }
        return documents;
    }

    /** Rewrites random resources of the estate at the given rate, each at a newer version and updated now. */
    static void trickle(Estate estate, OpenSearchIndex index, int perSecond, Duration duration) {
        System.out.printf("changing %d resources a second for %s%n", perSecond, duration);
        SplittableRandom random = new SplittableRandom();
        AtomicLong written = new AtomicLong();
        Flux.interval(Duration.ofSeconds(1))
                .take(duration.toSeconds())
                .concatMap(tick -> {
                    List<IndexedResource> changed = new ArrayList<>(perSecond);
                    Instant now = Instant.now();
                    for (int k = 0; k < perSecond; k++) {
                        IndexedResource r = estate.resource(random.nextLong(estate.size()));
                        changed.add(r.toBuilder().version(1_000 + tick).updatedAt(now).build());
                    }
                    return index.indexAll(changed).doOnNext(written::addAndGet);
                })
                .blockLast();
        System.out.printf("changed %,d resources%n", written.get());
    }

    private static void settings(WebClient web, String index, String body) {
        web.put().uri("/{index}/_settings", index).contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().toBodilessEntity()
                .block(Duration.ofMinutes(1));
    }
}
