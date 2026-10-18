package com.iaf.platform.providers.adapters.outbound;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.ErrorClass;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;
import com.iaf.platform.providers.domain.ProviderHealthRule;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;

import lombok.extern.slf4j.Slf4j;

/**
 * Demo telemetry ({@code platform.demo.seed-history}): the background call volume of the last two
 * weeks that the sixty seeded demo jobs alone cannot produce. Real providers see hundreds of
 * calls a day from every team's jobs, so the health bands are judged over a sample of that size.
 *
 * <p>Nothing here names a provider: every provider the registry has gets a profile drawn from its
 * declared SLO and a hash of its name. The calls per day (a few for a runner such as Ansible or
 * Terraform, hundreds for an API), a success rate between the SLO and 100%, and latencies well
 * inside the SLO; an action with an SLO of its own gets latencies in proportion to it, and no
 * successful call takes longer than its SLO's p95, so a quiet provider stays healthy whatever the
 * draw. Three providers carry the story the admin view needs, each the first in the order of the
 * hashes that can tell it: one has a failing hour (a burst of failed calls of one error class in
 * the last hour, which puts it under the failing floor), one is slow (its p95 twice the SLO's) and
 * one fails more calls than its SLO allows without reaching the floor. The last two go to providers
 * judged as a whole (no action SLO of their own) and called often enough for the band to hold.
 * Every sample carries the provider's current version, so a provider release's gates have a
 * baseline. The draw is deterministic (fixed seed, offsets relative to now), so the same picture
 * appears on every start, and an instance seeds once.</p>
 */
@Slf4j
@Component
public class DemoTelemetrySeeder {

    public static final long SEED = 20261017L;
    public static final int DAYS = 14;
    /** A story needs this many calls a day, so the last day judges it whatever the draw. */
    static final int STORY_MIN_CALLS = 40;
    /** The failed calls of the failing hour, as a share of the provider's calls per day. */
    static final double FAILING_HOUR_SHARE = 0.08;
    /** The p95 of the slow provider, as a multiple of its SLO's. */
    static final double SLOW_FACTOR = 2.0;
    private static final String[] ENVIRONMENTS = {"prod", "prod", "prod", "prod", "prod", "prod", "prod", "dev", "dev", "test"};

    /** What a provider's background traffic shows. */
    public enum Story {
        /** Inside its SLO: healthy. */
        HEALTHY,
        /** A burst of failed calls in the last hour: failing. */
        FAILING_HOUR,
        /** The p95 above the SLO's, below three times it: degraded. */
        SLOW,
        /** The success rate between the failing floor and the SLO's: degraded. */
        FLAKY
    }

    /** One provider's background traffic: calls per day, success rate in percent, p50 and p95 in ms, and its story. */
    public record Profile(int callsPerDay, double successRate, int p50Ms, int p95Ms, Story story) {
    }

    private final PlatformProperties props;
    private final ProviderQueries providers;
    private final TelemetryPort telemetry;
    private final Clock clock;
    private final AtomicBoolean seeded = new AtomicBoolean();

    public DemoTelemetrySeeder(PlatformProperties props, ProviderQueries providers, TelemetryPort telemetry, Clock clock) {
        this.props = props;
        this.providers = providers;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /** Runs before the job seeder (which adds the jobs' own calls on top), under the same switch. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    public void onReady() {
        if (props.getDemo() == null || !props.getDemo().isSeedHistory()) {
            return;
        }
        try {
            int calls = seed();
            log.info("demo telemetry seeded: {} background provider calls over {} days", calls, DAYS);
        } catch (RuntimeException e) {
            log.warn("demo telemetry could not be seeded: {}", e.toString(), e);
        }
    }

    /**
     * Record the background samples of every registered provider, each once; returns how many were
     * recorded. An instance seeds once: a later call records nothing and returns 0.
     */
    public int seed() {
        List<ProviderDefinition> all = providers.all().collectList().block(Duration.ofSeconds(15));
        if (all == null || all.isEmpty() || !seeded.compareAndSet(false, true)) {
            return 0;
        }
        Instant now = clock.instant();
        Map<String, Profile> profiles = profiles(all);
        int total = 0;
        for (ProviderDefinition def : all) {
            for (CallSample s : samples(def, profiles.get(def.name()), now, random(def))) {
                telemetry.record(s);
                total++;
            }
        }
        return total;
    }

    /** One stream per provider, so adding a provider never reshuffles the others. */
    public static Random random(ProviderDefinition def) {
        return new Random(SEED ^ hash(def.name()));
    }

    /**
     * Every provider's profile, by name. Each story goes to the first provider, in the order of the
     * name hashes, that can tell it and has none yet; every other provider is healthy.
     */
    public static Map<String, Profile> profiles(List<ProviderDefinition> all) {
        List<ProviderDefinition> byHash = all.stream()
                .sorted(Comparator.comparingLong((ProviderDefinition d) -> hash(d.name())).thenComparing(ProviderDefinition::name))
                .toList();
        Map<String, Story> stories = new HashMap<>();
        for (Story story : List.of(Story.FAILING_HOUR, Story.SLOW, Story.FLAKY)) {
            byHash.stream().filter(d -> !stories.containsKey(d.name()) && fits(d, story)).findFirst()
                    .ifPresent(d -> stories.put(d.name(), story));
        }
        Map<String, Profile> out = new LinkedHashMap<>();
        for (ProviderDefinition d : all) {
            out.put(d.name(), profile(d, stories.getOrDefault(d.name(), Story.HEALTHY)));
        }
        return out;
    }

    /**
     * Whether a provider tells a story whatever the draw: called often enough; for the two degraded
     * ones also judged as a whole (no action SLO of its own); and for the success rate, with at
     * least two of its daily calls between the failing floor and its SLO.
     */
    static boolean fits(ProviderDefinition d, Story story) {
        int calls = callsPerDay(d);
        if (calls < STORY_MIN_CALLS) {
            return false;
        }
        boolean whole = d.actions().stream().noneMatch(a -> a.slo() != null);
        return switch (story) {
            case HEALTHY, FAILING_HOUR -> true;
            case SLOW -> whole;
            case FLAKY -> whole && calls * (d.slo().successRate() - ProviderHealthRule.FAILING_RATE) / 100.0 >= 2;
        };
    }

    /** A provider's profile for a story: inside its SLO by a margin its name decides, unless the story says otherwise. */
    static Profile profile(ProviderDefinition d, Story story) {
        long h = hash(d.name());
        Slo slo = d.slo();
        double floor = Math.max(slo.successRate(), ProviderHealthRule.FAILING_RATE);
        double rate = story == Story.FLAKY
                ? (ProviderHealthRule.FAILING_RATE + slo.successRate()) / 2
                : floor + (100.0 - floor) * (0.6 + 0.3 * fraction(h, 1));
        double share = story == Story.SLOW ? SLOW_FACTOR : 0.45 + 0.3 * fraction(h, 2);
        int p95 = (int) Math.max(20, Math.round(slo.p95Ms() * share));
        int p50 = Math.max(5, (int) Math.round(p95 / (2.5 + fraction(h, 3))));
        return new Profile(callsPerDay(d), Math.round(rate * 1000) / 1000.0, p50, p95, story);
    }

    /** A runner (Ansible, Terraform) takes minutes a call and is called a few times a day; an API hundreds of times. */
    static int callsPerDay(ProviderDefinition d) {
        double f = fraction(hash(d.name()), 0);
        ProviderType type = d.type();
        if (type == ProviderType.ANSIBLE || type == ProviderType.TERRAFORM) {
            return 8 + (int) Math.round(22 * f);
        }
        if (type == ProviderType.IDP) {
            return 10 + (int) Math.round(30 * f);
        }
        return STORY_MIN_CALLS + (int) Math.round(360 * f);
    }

    /** The failed calls of the failing hour, on top of the last day's: none for another story. */
    static int burst(Profile profile) {
        return profile.story() == Story.FAILING_HOUR
                ? Math.max(ProviderHealthRule.MIN_CALLS, (int) Math.ceil(profile.callsPerDay() * FAILING_HOUR_SHARE))
                : 0;
    }

    /**
     * The background samples of one provider, oldest first: a fixed count per day with the profile's
     * outcomes. How many calls fail each day is designed, not drawn (the rate's fractional part is
     * carried from the newest day backwards), so a low-volume provider never shows a random bad day
     * that would flip its band; which calls fail, and the latencies, are drawn. A failing hour adds
     * its burst on top of the last day.
     */
    public static List<CallSample> samples(ProviderDefinition def, Profile profile, Instant now, Random rnd) {
        List<Action> weighted = weightedActions(def);
        List<String> errorClasses = errorClasses(def);
        String version = def.metadata() == null ? null : def.metadata().version();
        double sigma = Math.log((double) profile.p95Ms() / profile.p50Ms()) / 1.645; // log-normal spread hitting the designed p95
        int[] failuresPerDay = failuresPerDay(profile);
        List<CallSample> out = new ArrayList<>();
        for (int day = DAYS - 1; day >= 0; day--) {
            Instant dayEnd = now.minus(Duration.ofDays(day));
            Set<Integer> failing = new HashSet<>();
            while (failing.size() < Math.min(failuresPerDay[day], profile.callsPerDay())) {
                failing.add(rnd.nextInt(profile.callsPerDay()));
            }
            for (int i = 0; i < profile.callsPerDay(); i++) {
                // calls spread over the day, denser in working hours
                int minutesBack = rnd.nextInt(100) < 70 ? 8 * 60 + rnd.nextInt(10 * 60) : rnd.nextInt(24 * 60);
                Instant at = dayEnd.minus(Duration.ofMinutes(minutesBack)).minusSeconds(rnd.nextInt(60));
                Action action = weighted.get(rnd.nextInt(weighted.size()));
                boolean ok = !failing.contains(i);
                long duration = Math.round(profile.p50Ms() * scale(def, action) * Math.exp(rnd.nextGaussian() * sigma));
                if (ok) {
                    duration = Math.min(duration, ceiling(def, profile, action));
                } else {
                    duration = Math.round(duration * (1.5 + rnd.nextDouble())); // failures tend to be slow
                }
                String errorClass = ok ? null : errorClasses.get(rnd.nextInt(errorClasses.size()));
                out.add(new CallSample(at, def.name(), action.id(), ENVIRONMENTS[rnd.nextInt(ENVIRONMENTS.length)], ok,
                        Math.max(1, duration), errorClass, null, null, null, version));
            }
        }
        int burst = burst(profile);
        if (burst > 0) {
            String incident = incidentClass(def);
            for (int i = 0; i < burst; i++) {
                Instant at = now.minusSeconds(1 + rnd.nextInt(3599));
                Action action = weighted.get(rnd.nextInt(weighted.size()));
                long duration = Math.round(profile.p95Ms() * scale(def, action) * (1.5 + rnd.nextDouble())); // calls that time out or wait for retries
                out.add(new CallSample(at, def.name(), action.id(), "prod", false, Math.max(1, duration), incident, null, null, null, version));
            }
        }
        out.sort(Comparator.comparing(CallSample::at));
        return out;
    }

    /** An action with an SLO of its own is as much slower or faster as its p95 says. */
    static double scale(ProviderDefinition def, Action action) {
        return (double) Math.max(1, def.sloOf(action).p95Ms()) / Math.max(1, def.slo().p95Ms());
    }

    /** The longest a successful call takes: the SLO's p95 of its action, for the slow story just under the failing factor. */
    static long ceiling(ProviderDefinition def, Profile profile, Action action) {
        int p95 = Math.max(1, def.sloOf(action).p95Ms());
        return profile.story() == Story.SLOW ? Math.round(p95 * (ProviderHealthRule.FAILING_P95_FACTOR - 0.2)) : p95;
    }

    /** Failed calls per day (index 0 = today): the expected count, with the remainder carried to older days. */
    static int[] failuresPerDay(Profile profile) {
        double expected = profile.callsPerDay() * (100.0 - profile.successRate()) / 100.0;
        int[] out = new int[DAYS];
        double carry = 0.0;
        for (int day = 0; day < DAYS; day++) {
            carry += expected;
            out[day] = (int) Math.floor(carry);
            carry -= out[day];
        }
        return out;
    }

    /** Read actions are called four times as often as the ones that change something. */
    static List<Action> weightedActions(ProviderDefinition def) {
        List<Action> weighted = new ArrayList<>();
        for (Action a : def.actions()) {
            int weight = a.effect() == Effect.READ || a.effect() == null ? 4 : 1;
            for (int i = 0; i < weight; i++) {
                weighted.add(a);
            }
        }
        if (weighted.isEmpty()) {
            weighted.add(new Action("call", "Call", null, null, null, Effect.READ, true, false, null, null, null, null, null, null, null, null, null));
        }
        return weighted;
    }

    /** The provider's own error classes but not_found, else the two every API has. */
    static List<String> errorClasses(ProviderDefinition def) {
        List<String> classes = new ArrayList<>();
        if (def.spec() != null && def.spec().errors() != null) {
            for (ErrorClass e : def.spec().errors()) {
                if (e.errorClass() != null && !"not_found".equals(e.errorClass())) {
                    classes.add(e.errorClass());
                }
            }
        }
        if (classes.isEmpty()) {
            classes.add("unavailable");
            classes.add("timeout");
        }
        return classes;
    }

    /** The class an outage shows: the provider's first retryable error class, else its first, else unavailable. */
    static String incidentClass(ProviderDefinition def) {
        if (def.spec() != null && def.spec().errors() != null) {
            for (ErrorClass e : def.spec().errors()) {
                if (e.retry() && e.errorClass() != null && !"not_found".equals(e.errorClass())) {
                    return e.errorClass();
                }
            }
        }
        return errorClasses(def).get(0);
    }

    /** FNV-1a over the name's UTF-8 bytes: the same on every JVM and every start. */
    static long hash(String name) {
        long h = 0xcbf29ce484222325L;
        for (byte b : (name == null ? "" : name).getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** One of four independent fractions in [0, 1] a hash holds. */
    static double fraction(long hash, int which) {
        return ((hash >>> (16 * which)) & 0xFFFF) / 65535.0;
    }
}
