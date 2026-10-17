package com.iaf.platform.providers.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import com.iaf.platform.providers.ports.inbound.ProviderQueries;

import lombok.extern.slf4j.Slf4j;

/**
 * Demo telemetry ({@code platform.demo.seed-history}): the background call volume of the last two
 * weeks that the sixty seeded demo jobs alone cannot produce. Real providers see hundreds of
 * calls a day from every team's jobs, so the health bands are judged over a sample of that size.
 * Each provider gets a designed profile (calls per day, success rate, p50 and p95 latency) so the
 * admin view tells a story: vSphere and Vault healthy, the backup service degraded on success
 * rate, MongoDB Atlas degraded on latency, the rest comfortably inside their SLOs. The draw is
 * deterministic (fixed seed, offsets relative to now), so the same picture appears on every start.
 */
@Slf4j
@Component
public class DemoTelemetrySeeder {

    public static final long SEED = 20261017L;
    public static final int DAYS = 14;
    private static final String[] ENVIRONMENTS = {"prod", "prod", "prod", "prod", "prod", "prod", "prod", "dev", "dev", "test"};

    /** One provider's background traffic: calls per day, success rate in percent, p50 and p95 in ms. */
    public record Profile(int callsPerDay, double successRate, int p50Ms, int p95Ms) {
    }

    /** The designed profiles; a provider not listed here gets {@link #DEFAULT}. */
    public static final Map<String, Profile> PROFILES = Map.of(
            "vsphere", new Profile(420, 99.85, 180, 620),
            "vault", new Profile(900, 99.98, 25, 110),
            "backup", new Profile(60, 98.3, 350, 1050),
            "mongodb", new Profile(96, 99.6, 400, 2400),
            "image-builder", new Profile(14, 99.2, 700, 1300),
            "ops-playbooks", new Profile(22, 98.6, 60_000, 120_000),
            "terraform-modules", new Profile(12, 98.9, 45_000, 75_000),
            "idp-tiles", new Profile(9, 97.5, 1_200, 2_600));
    public static final Profile DEFAULT = new Profile(30, 99.5, 200, 700);

    private final PlatformProperties props;
    private final ProviderQueries providers;
    private final TelemetryPort telemetry;
    private final Clock clock;

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

    /** Record the background samples for every registered provider; returns how many were recorded. */
    public int seed() {
        List<ProviderDefinition> all = providers.all().collectList().block(Duration.ofSeconds(15));
        if (all == null || all.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        int total = 0;
        for (ProviderDefinition def : all) {
            Profile profile = PROFILES.getOrDefault(def.name(), DEFAULT);
            // one stream per provider so adding a provider never reshuffles the others
            Random rnd = new Random(SEED ^ def.name().hashCode());
            for (CallSample s : samples(def, profile, now, rnd)) {
                telemetry.record(s);
                total++;
            }
        }
        return total;
    }

    /**
     * The background samples of one provider, oldest first: a fixed count per day with the profile's
     * outcomes. How many calls fail each day is designed, not drawn (the rate's fractional part is
     * carried from the newest day backwards), so a low-volume provider never shows a random bad day
     * that would flip its band; which calls fail, and the latencies, are drawn.
     */
    public static List<CallSample> samples(ProviderDefinition def, Profile profile, Instant now, Random rnd) {
        List<Action> weighted = weightedActions(def);
        List<String> errorClasses = errorClasses(def);
        double sigma = Math.log((double) profile.p95Ms() / profile.p50Ms()) / 1.645; // log-normal spread hitting the designed p95
        int[] failuresPerDay = failuresPerDay(profile);
        List<CallSample> out = new ArrayList<>();
        for (int day = DAYS - 1; day >= 0; day--) {
            Instant dayEnd = now.minus(Duration.ofDays(day));
            java.util.Set<Integer> failing = new java.util.HashSet<>();
            while (failing.size() < Math.min(failuresPerDay[day], profile.callsPerDay())) {
                failing.add(rnd.nextInt(profile.callsPerDay()));
            }
            for (int i = 0; i < profile.callsPerDay(); i++) {
                // calls spread over the day, denser in working hours
                int minutesBack = rnd.nextInt(100) < 70 ? 8 * 60 + rnd.nextInt(10 * 60) : rnd.nextInt(24 * 60);
                Instant at = dayEnd.minus(Duration.ofMinutes(minutesBack)).minusSeconds(rnd.nextInt(60));
                Action action = weighted.get(rnd.nextInt(weighted.size()));
                boolean ok = !failing.contains(i);
                long duration = Math.round(profile.p50Ms() * Math.exp(rnd.nextGaussian() * sigma));
                if (!ok) {
                    duration = Math.round(duration * (1.5 + rnd.nextDouble())); // failures tend to be slow
                }
                String errorClass = ok ? null : errorClasses.get(rnd.nextInt(errorClasses.size()));
                out.add(new CallSample(at, def.name(), action.id(), ENVIRONMENTS[rnd.nextInt(ENVIRONMENTS.length)], ok,
                        Math.max(1, duration), errorClass, null, null, null));
            }
        }
        out.sort(java.util.Comparator.comparing(CallSample::at));
        return out;
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

    static List<String> errorClasses(ProviderDefinition def) {
        List<String> classes = new ArrayList<>();
        if (def.spec() != null && def.spec().errors() != null) {
            for (ProviderDefinition.ErrorClass e : def.spec().errors()) {
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
}
