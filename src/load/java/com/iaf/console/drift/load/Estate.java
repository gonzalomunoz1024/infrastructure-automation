package com.iaf.console.drift.load;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A synthetic estate at the console's scale targets, the same every time for the same size, account
 * count and seed: resource {@code i} (0 to size - 1) is a pure function of them, so the watch list and
 * the platform agree on it with nothing passed between them and nothing of it held as JSON. The
 * largest account holds {@code load.largestShare} of it (a tenth by default: a million of ten
 * million) and the others share the rest by a Zipf law. Inside an account the resources lie in blocks
 * by type and environment, so the list {@code :observe-list} walks for an account, type and
 * environment is a range of indexes and any page of it is computed from its cursor.
 *
 * <p>Tiers follow from what the tier rules read: everything in {@code prod} is hot, a share of the
 * rest changed in the last day (hot), a share was untouched for more than 30 days (cold), the rest is
 * warm. A share of the resources is imported from discovery ({@code source: discover}), read through
 * the type's {@code :discover} pages, which list every account's objects of the type.
 */
public final class Estate {

    /** The region every resource is in, the console's one region for now. */
    public static final String REGION = "global";
    /** The provider every type is read through: one observe bucket for all of it, the case the plan's numbers are about. */
    public static final String PROVIDER = "vsphere";
    static final double ZIPF = 0.9;

    /** A resource type: its service, its share of each account and the attributes every one of its resources has. */
    public record Kind(String type, String service, double share, Map<String, Object> attributes) {
    }

    static final List<Kind> KINDS = List.of(
            new Kind("vm", "compute", 0.45, Map.of("cpu", 4, "memoryGb", 16, "powerState", "on")),
            new Kind("volume", "storage", 0.30, Map.of("sizeGb", 200, "tier", "ssd")),
            new Kind("database", "data", 0.15, Map.of("engine", "postgres", "tier", "M30")),
            new Kind("network", "network", 0.10, Map.of("cidr", "10.0.0.0/24", "vlan", 120)));
    /** The environments, prod last: its block is what the tier rules make hot. */
    static final List<String> ENVS = List.of("dev", "staging", "prod");

    /** One account's resources of one type in one environment: what one {@code :observe-list} walk reads. */
    public record Group(int index, int account, int kind, int env, long start, long size) {
        public long end() {
            return start + size;
        }
    }

    /** What a resource is as of the estate's start; {@code updatedAt} decides its tier. */
    public record Resource(long index, Group group, Instant updatedAt, boolean imported) {
    }

    private final long size;
    private final int accounts;
    private final long seed;
    private final double largestShare;
    private final double prod;
    private final double recent;
    private final double cold;
    private final double imported;
    private final Instant start;
    private final Group[] groups;
    private final long[] groupStarts;
    private final Map<String, Group> byKey = new HashMap<>();
    /** Per kind and environment, every account's group in order: the {@code :discover} list of the type there. */
    private final Map<String, List<Group>> discoverLists = new HashMap<>();

    public Estate(long size, int accounts, long seed, double largestShare, double prod, double recent, double cold, double imported, Instant start) {
        if (size < 1 || accounts < 1 || accounts > size || largestShare <= 0 || largestShare >= 1) {
            throw new IllegalArgumentException("an estate has at least one resource per account");
        }
        if (size > 30_000_000L) {
            throw new IllegalArgumentException("a resource's index takes 25 bits in the watch list's due queues: at most 30,000,000 resources");
        }
        this.size = size;
        this.accounts = accounts;
        this.seed = seed;
        this.largestShare = largestShare;
        this.prod = prod;
        this.recent = recent;
        this.cold = cold;
        this.imported = imported;
        this.start = start;
        List<Group> out = new ArrayList<>();
        long at = 0;
        for (long n : accountSizes(size, accounts, largestShare)) {
            int account = out.isEmpty() ? 0 : out.get(out.size() - 1).account() + 1;
            at = layoutAccount(out, account, at, n);
        }
        this.groups = out.toArray(Group[]::new);
        this.groupStarts = new long[groups.length];
        for (Group g : groups) {
            groupStarts[g.index()] = g.start();
            byKey.put(key(accountId(g.account()), KINDS.get(g.kind()).type(), ENVS.get(g.env())), g);
            discoverLists.computeIfAbsent(KINDS.get(g.kind()).type() + "|" + ENVS.get(g.env()), k -> new ArrayList<>()).add(g);
        }
    }

    /** The estate the {@code load.*} system properties describe. */
    public static Estate fromProperties(Instant start) {
        return new Estate(Long.getLong("load.size", 1_000_000L), Integer.getInteger("load.accounts", 2000), Long.getLong("load.seed", 7L),
                Double.parseDouble(System.getProperty("load.largestShare", "0.10")), Double.parseDouble(System.getProperty("load.prod", "0.04")),
                Double.parseDouble(System.getProperty("load.recent", "0.0105")), Double.parseDouble(System.getProperty("load.cold", "0.208")),
                Double.parseDouble(System.getProperty("load.imported", "0.001")), start);
    }

    private static long[] accountSizes(long size, int accounts, double largestShare) {
        long[] sizes = new long[accounts];
        if (accounts == 1) {
            sizes[0] = size;
            return sizes;
        }
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
        return sizes;
    }

    /** An account's blocks: per type its share, per environment prod's share, staging 40% of the rest, dev the remainder. */
    private long layoutAccount(List<Group> out, int account, long at, long n) {
        long left = n;
        for (int kind = 0; kind < KINDS.size(); kind++) {
            long ofKind = kind == KINDS.size() - 1 ? left : Math.round(n * KINDS.get(kind).share());
            ofKind = Math.min(ofKind, left);
            left -= ofKind;
            long inProd = Math.round(ofKind * prod);
            long inStaging = Math.round((ofKind - inProd) * 0.4);
            long[] perEnv = {ofKind - inProd - inStaging, inStaging, inProd};
            for (int env = 0; env < ENVS.size(); env++) {
                if (perEnv[env] > 0) {
                    out.add(new Group(out.size(), account, kind, env, at, perEnv[env]));
                    at += perEnv[env];
                }
            }
        }
        return at;
    }

    public long size() {
        return size;
    }

    public int accounts() {
        return accounts;
    }

    public Instant start() {
        return start;
    }

    public List<Group> groups() {
        return List.of(groups);
    }

    public double largestShare() {
        return largestShare;
    }

    /** The id of account {@code k}: twelve digits. */
    public static String accountId(int k) {
        return String.format("%012d", 100_000_000_000L + k * 7_919L);
    }

    static String key(String accountId, String type, String environment) {
        return accountId + "|" + type + "|" + environment;
    }

    /** The group an {@code :observe-list} call names; null when the account has none of the type there. */
    public Group group(String accountId, String type, String environment) {
        return byKey.get(key(accountId, type, environment));
    }

    /** Every account's groups of a type in an environment, in the order the type's discover pages list them. */
    public List<Group> discoverList(String type, String environment) {
        return discoverLists.getOrDefault(type + "|" + environment, List.of());
    }

    /** The group resource {@code i} is in. */
    public Group groupOf(long i) {
        int lo = 0;
        int hi = groups.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (groupStarts[mid] <= i) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return groups[lo];
    }

    public Kind kind(Group g) {
        return KINDS.get(g.kind());
    }

    public String environment(Group g) {
        return ENVS.get(g.env());
    }

    /** Resource {@code i}, the same for the same estate. */
    public Resource resource(long i) {
        Group g = groupOf(i);
        SplittableRandom r = random(i);
        double tierDraw = r.nextDouble();
        Instant updated;
        if (tierDraw < recent) {
            // changed in the last 20 hours: hot for the first four hours of a run at least
            updated = start.minusSeconds(60 + r.nextLong(Duration.ofHours(20).toSeconds()));
        } else if (tierDraw < recent + cold) {
            updated = start.minus(Duration.ofDays(31)).minusSeconds(r.nextLong(Duration.ofDays(370).toSeconds()));
        } else {
            updated = start.minus(Duration.ofDays(2)).minusSeconds(r.nextLong(Duration.ofDays(27).toSeconds()));
        }
        return new Resource(i, g, updated, r.nextDouble() < imported);
    }

    /** A number in [0, 1) that is the same for the same resource and purpose: where a resource's times fall at the start. */
    public double draw(long i, int purpose) {
        return new SplittableRandom(mix(seed * 0x9E3779B97F4A7C15L + i * 31 + purpose + 1_000_003L)).nextDouble();
    }

    public String wfrn(long i) {
        Group g = groupOf(i);
        Kind k = KINDS.get(g.kind());
        return "wfrn:wf:" + k.service() + ":" + REGION + ":" + accountId(g.account()) + ":" + k.type() + "/" + k.type() + "-" + Long.toString(i, 36);
    }

    /** The resource a wfrn of this estate names; -1 for any other. */
    public static long indexOf(String wfrn) {
        int dash = wfrn.lastIndexOf('-');
        if (dash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(wfrn.substring(dash + 1), 36);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The provider's id of resource {@code i}: what a discover page answers and an imported watch matches by. */
    public String providerId(long i) {
        return KINDS.get(groupOf(i).kind()).type() + "-p" + Long.toString(i, 36);
    }

    public static long indexOfProviderId(String providerId) {
        int at = providerId.lastIndexOf("-p");
        if (at < 0) {
            return -1;
        }
        try {
            return Long.parseLong(providerId.substring(at + 2), 36);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private SplittableRandom random(long i) {
        return new SplittableRandom(mix(seed * 0x9E3779B97F4A7C15L + i));
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
