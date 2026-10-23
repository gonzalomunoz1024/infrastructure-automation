package com.iaf.console.state.load;

import java.util.List;
import java.util.SplittableRandom;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.state.inventory.domain.Json;

/**
 * The load test's synthetic estate as the state service holds it (WP 6.2), laid out like the
 * search indexer's {@code Estate} so both tests work in the same accounts: account {@code k} is
 * {@code 1000000000000 + 7919k}, the largest holds {@code largestShare} of the resources (a million
 * of ten million) and the others share the rest by a Zipf law. Resource {@code i} is a pure function
 * of the size, the account count and the seed: five types, names like {@code payments-api-7k2qd},
 * the tags {@code env}, {@code team}, {@code cost-centre} and a unique {@code cmdb}.
 */
public final class StateEstate {

    public static final String REGION = "global";
    static final double ZIPF = 0.9;

    /** A type, its service, its share of the estate and the words its names use. */
    public record Kind(String type, String service, double share, List<String> roles) {
    }

    public static final List<Kind> KINDS = List.of(
            new Kind("vm", "compute", 0.40, List.of("web", "api", "worker", "batch", "cache")),
            new Kind("volume", "storage", 0.22, List.of("data", "logs", "scratch", "backup")),
            new Kind("database", "data", 0.14, List.of("db", "pg", "mongo", "replica")),
            new Kind("image", "compute", 0.14, List.of("base", "golden", "runtime")),
            new Kind("environment", "platform", 0.10, List.of("sandbox", "shared", "edge")));
    static final List<String> APPS = List.of("orders", "payments", "billing", "ledger", "catalog", "search", "checkout", "inventory",
            "pricing", "shipping", "identity", "profile", "reports", "analytics", "gateway", "notifications", "fraud", "risk", "claims",
            "quotes", "trading", "settlement", "clearing", "custody", "treasury", "compliance", "audit", "payroll", "crm", "marketing");
    static final List<String> TEAMS = List.of("core-banking", "payments", "cards", "lending", "wealth", "markets", "risk", "fraud",
            "compliance", "finance", "treasury", "web", "mobile", "data", "analytics", "platform", "sre", "security", "identity", "billing");
    static final List<String> ENVS = List.of("prod", "staging", "test", "dev");
    static final String CODE_ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    /** One resource of the estate: what an import names. */
    public record Item(long index, String accountId, Kind kind, String name, String registryId, ObjectNode attributes, String env, String team,
            String costCentre, String cmdb) {
    }

    private final long size;
    private final int accounts;
    private final long seed;
    private final long[] starts;

    public StateEstate(long size, int accounts, long seed, double largestShare) {
        if (size < 1 || accounts < 1 || accounts > size || largestShare <= 0 || largestShare >= 1) {
            throw new IllegalArgumentException("an estate has at least one resource per account");
        }
        this.size = size;
        this.accounts = accounts;
        this.seed = seed;
        this.starts = layout(size, accounts, largestShare);
    }

    public static StateEstate fromProperties() {
        return new StateEstate(Long.getLong("load.size", 1_000_000L), Integer.getInteger("load.accounts", 200), Long.getLong("load.seed", 7L),
                Double.parseDouble(System.getProperty("load.largestShare", "0.10")));
    }

    public long size() {
        return size;
    }

    public int accounts() {
        return accounts;
    }

    public static String accountId(int k) {
        return String.format("%012d", 100_000_000_000L + k * 7_919L);
    }

    public long accountStart(int k) {
        return starts[k];
    }

    public long accountSize(int k) {
        return starts[k + 1] - starts[k];
    }

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

    public Item item(long i) {
        SplittableRandom r = new SplittableRandom(mix(seed * 0x9E3779B97F4A7C15L + i));
        int account = accountOf(i);
        Kind kind = kind(r.nextDouble());
        String code = code(i);
        String name = APPS.get(r.nextInt(APPS.size())) + "-" + kind.roles().get(r.nextInt(kind.roles().size())) + "-" + code;
        String env = ENVS.get(r.nextInt(ENVS.size()));
        String team = TEAMS.get((account * 7 + r.nextInt(3)) % TEAMS.size());
        ObjectNode attributes = Json.object().put("name", name).put("environment", env);
        switch (kind.type()) {
            case "vm" -> attributes.put("cpu", 2 << r.nextInt(4)).put("memoryGb", 4 << r.nextInt(5)).put("size", "medium");
            case "database" -> attributes.put("engine", r.nextBoolean() ? "postgres" : "mongodb");
            case "volume" -> attributes.put("sizeGb", 50 << r.nextInt(6));
            case "image" -> attributes.put("os", List.of("ubuntu-24.04", "rhel-9", "windows-2022").get(r.nextInt(3)));
            default -> attributes.put("owner", team);
        }
        String cmdb = "ci" + String.format("%08d", i);
        return new Item(i, accountId(account), kind, name, kind.type() + "-" + code + "-" + seed, attributes, env, team, "cc-" + (1000 + account % 200),
                cmdb);
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
        long[] out = new long[accounts + 1];
        for (int k = 0; k < accounts; k++) {
            out[k + 1] = out[k] + sizes[k];
        }
        return out;
    }

    static String code(long i) {
        long v = (i * 0x5DEECE66DL + 0xB) & ((1L << 25) - 1);
        char[] c = new char[5];
        for (int k = 4; k >= 0; k--) {
            c[k] = CODE_ALPHABET.charAt((int) (v & 31));
            v >>>= 5;
        }
        return new String(c);
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

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
