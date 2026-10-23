package com.iaf.console.search.load;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;

import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.wfrn.MintedIds;
import com.iaf.console.wfrn.Ulid;
import com.iaf.console.wfrn.Wfrn;

/**
 * A synthetic estate at the console's scale targets, the same every time for the same size, account
 * count and seed: resource {@code i} (0 to size - 1) is a pure function of them, so the seeder writes
 * it and the simulation finds it again with nothing passed between them. The largest account holds
 * a tenth of everything by default (a million resources of ten million, the target for one account;
 * {@code load.largestShare} changes it, so a smaller estate can still hold a million-resource
 * account); the others share the rest by a Zipf law, so most accounts are small and a few are large.
 * Every time is before a fixed anchor, so a run on any day sees the same estate.
 */
public final class Estate {

    /** The region every resource is in, the console's one region for now. */
    public static final String REGION = "global";
    /** The end of the estate's history: every resource was created in the 400 days before it. */
    public static final Instant ANCHOR = Instant.parse("2026-10-01T00:00:00Z");
    static final Duration HISTORY = Duration.ofDays(400);
    /** The share of the estate the largest account holds by default: a million of ten million. */
    public static final double LARGEST_SHARE = 0.10;
    static final double ZIPF = 0.9;

    /** A resource type of the sample content, its service, its share of the estate and the words its names use. */
    public record Kind(String type, String service, double share, List<String> roles) {
    }

    static final List<Kind> KINDS = List.of(
            new Kind("vm", "compute", 0.40, List.of("web", "api", "worker", "batch", "cache")),
            new Kind("volume", "storage", 0.22, List.of("data", "logs", "scratch", "backup")),
            new Kind("database", "data", 0.14, List.of("db", "pg", "mongo", "replica")),
            new Kind("image", "compute", 0.14, List.of("base", "golden", "runtime")),
            new Kind("environment", "platform", 0.10, List.of("sandbox", "shared", "edge")));
    static final List<String> APPS = List.of("orders", "payments", "billing", "ledger", "catalog", "search", "checkout", "inventory",
            "pricing", "shipping", "identity", "profile", "reports", "analytics", "gateway", "notifications", "fraud", "risk", "claims",
            "quotes", "trading", "settlement", "clearing", "custody", "treasury", "compliance", "audit", "payroll", "crm", "marketing",
            "content", "media", "streaming", "telemetry", "metrics", "logging", "tracing", "archive", "loans", "cards");
    static final List<String> TEAMS = List.of("core-banking", "payments", "cards", "lending", "wealth", "markets", "risk", "fraud",
            "compliance", "finance", "treasury", "hr-systems", "web", "mobile", "data", "analytics", "ml", "platform", "sre", "security",
            "identity", "messaging", "search", "integration", "crm", "marketing", "support", "billing", "ledger", "reporting");
    static final List<String> ENVS = List.of("prod", "staging", "test", "dev");
    static final double[] ENV_SHARES = {0.35, 0.15, 0.20, 0.30};
    static final String CODE_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    private final long size;
    private final int accounts;
    private final long seed;
    private final double largestShare;
    /** {@code starts[k]} is the first resource of account {@code k}; {@code starts[accounts]} is the size. */
    private final long[] starts;

    public Estate(long size, int accounts, long seed, double largestShare) {
        if (size < 1 || accounts < 1 || accounts > size || largestShare <= 0 || largestShare >= 1) {
            throw new IllegalArgumentException("an estate has at least one resource per account");
        }
        if (size > 33_554_432L) {
            throw new IllegalArgumentException("names carry five base-32 characters: at most 33,554,432 resources");
        }
        this.size = size;
        this.accounts = accounts;
        this.seed = seed;
        this.largestShare = largestShare;
        this.starts = layout(size, accounts, largestShare);
    }

    /** The estate the {@code load.*} system properties describe, with the defaults of a local run. */
    public static Estate fromProperties() {
        return new Estate(Long.getLong("load.size", 1_000_000L), Integer.getInteger("load.accounts", 200), Long.getLong("load.seed", 7L),
                Double.parseDouble(System.getProperty("load.largestShare", String.valueOf(LARGEST_SHARE))));
    }

    private static long[] layout(long size, int accounts, double largestShare) {
        long[] sizes = new long[accounts];
        if (accounts == 1) {
            sizes[0] = size;
        } else {
            sizes[0] = Math.max(1, Math.round(size * largestShare));
            long rest = size - sizes[0];
            double total = 0;
            for (int k = 1; k < accounts; k++) {
                total += Math.pow(k, -ZIPF);
            }
            long given = 0;
            for (int k = 1; k < accounts; k++) {
                sizes[k] = Math.max(1, (long) Math.floor(rest * Math.pow(k, -ZIPF) / total));
                given += sizes[k];
            }
            sizes[1] += rest - given;
        }
        long[] starts = new long[accounts + 1];
        for (int k = 0; k < accounts; k++) {
            starts[k + 1] = starts[k] + sizes[k];
        }
        return starts;
    }

    public long size() {
        return size;
    }

    public int accounts() {
        return accounts;
    }

    public double largestShare() {
        return largestShare;
    }

    /** The id of account {@code k}: twelve digits. */
    public static String accountId(int k) {
        return String.format("%012d", 100_000_000_000L + k * 7_919L);
    }

    /** How many resources account {@code k} holds. */
    public long accountSize(int k) {
        return starts[k + 1] - starts[k];
    }

    /** The first resource of account {@code k}. */
    public long accountStart(int k) {
        return starts[k];
    }

    /** The account resource {@code i} is in. */
    public int accountOf(long i) {
        int lo = 0;
        int hi = accounts - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= i) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Resource {@code i}, the same for the same estate. */
    public IndexedResource resource(long i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        SplittableRandom r = new SplittableRandom(mix(seed * 0x9E3779B97F4A7C15L + i));
        int account = accountOf(i);
        Kind kind = kind(r.nextDouble());
        String code = code(i);
        String app = APPS.get(r.nextInt(APPS.size()));
        String role = kind.roles().get(r.nextInt(kind.roles().size()));
        String env = ENVS.get(pick(ENV_SHARES, r.nextDouble()));
        Instant created = ANCHOR.minusSeconds(1 + r.nextLong(HISTORY.toSeconds()));
        Instant updated = created.plusSeconds(r.nextLong(Duration.between(created, ANCHOR).toSeconds() + 1));
        String wfrn = new Wfrn("wf", kind.service(), REGION, accountId(account), kind.type(), MintedIds.mint(kind.type(), Ulid.of(created, r)))
                .format();
        IndexedResource.Builder b = IndexedResource.builder()
                .wfrn(wfrn)
                .accountId(accountId(account))
                .region(REGION)
                .type(kind.type())
                .service(kind.service())
                .name(app + "-" + role + "-" + code)
                .version(1 + r.nextInt(12))
                .workflow("create-" + kind.type())
                .lastJobId("job-" + code)
                .createdAt(created)
                .updatedAt(updated)
                .tag("env", env)
                .tag("team", TEAMS.get((account * 7 + r.nextInt(3)) % TEAMS.size()))
                .tag("cost-centre", "cc-" + (1000 + account % 200))
                .tag("cmdb", cmdb(i))
                .field("name", app + "-" + role + "-" + code)
                .field("environment", env);
        switch (kind.type()) {
            case "vm" -> b.field("cpu", 2 << r.nextInt(4)).field("memory", (4 << r.nextInt(5)) + " GiB");
            case "database" -> b.field("engine", r.nextBoolean() ? "postgres" : "mongodb").field("tier", List.of("small", "medium", "large").get(r.nextInt(3)));
            case "volume" -> b.field("size", (50 << r.nextInt(6)) + " GiB");
            case "image" -> b.field("os", List.of("ubuntu-24.04", "rhel-9", "windows-2022").get(r.nextInt(3)));
            default -> b.field("owner", TEAMS.get(account % TEAMS.size()));
        }
        double s = r.nextDouble();
        ResourceState state = s < 0.04 ? ResourceState.DELETED
                : s < 0.055 ? ResourceState.FAILED
                : s < 0.07 ? ResourceState.PENDING
                : s < 0.08 ? ResourceState.UPDATING
                : s < 0.085 ? ResourceState.DELETING
                : ResourceState.ACTIVE;
        b.state(state);
        if (state == ResourceState.DELETED) {
            // no end, so the indexer's housekeeping leaves the estate as it was seeded
            b.deletedAt(updated).tombstoneUntil(null);
        }
        if (state == ResourceState.ACTIVE && r.nextDouble() < 0.03) {
            double severity = r.nextDouble();
            b.drift(true).driftFindingId("f-" + code).driftSeverity(severity < 0.2 ? "critical" : severity < 0.7 ? "warning" : "info");
        }
        return b.build();
    }

    /** The CMDB id every resource carries as a tag, unique in the estate. */
    public static String cmdb(long i) {
        return "ci" + String.format("%08d", i);
    }

    /** Five base-32 characters, a different code for every resource (a bijection on 0 to 32^5 - 1). */
    static String code(long i) {
        long v = (i * 0x5DEECE66DL + 0xB) & ((1L << 25) - 1);
        char[] c = new char[5];
        for (int k = 4; k >= 0; k--) {
            c[k] = CODE_ALPHABET.charAt((int) (v & 31));
            v >>>= 5;
        }
        return new String(c);
    }

    /** The estate's last day, as the {@code created:} and {@code updated:} terms write it. */
    public static LocalDate anchorDay() {
        return LocalDate.ofInstant(ANCHOR, ZoneOffset.UTC);
    }

    private static Kind kind(double u) {
        double sum = 0;
        for (Kind k : KINDS) {
            sum += k.share();
            if (u < sum) {
                return k;
            }
        }
        return KINDS.get(KINDS.size() - 1);
    }

    private static int pick(double[] shares, double u) {
        double sum = 0;
        for (int k = 0; k < shares.length; k++) {
            sum += shares[k];
            if (u < sum) {
                return k;
            }
        }
        return shares.length - 1;
    }

    /** MurmurHash3's finalizer: neighbouring indexes get unrelated random streams. */
    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
