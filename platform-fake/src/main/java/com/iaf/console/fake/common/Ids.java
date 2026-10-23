package com.iaf.console.fake.common;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;

import com.iaf.console.wfrn.Ulid;

/** The ids the fake mints: a prefix and a ULID, from a seeded generator so a demo is the same every run. */
public final class Ids {

    private final Ulid ulids;

    public Ids(Clock clock, RandomGenerator random) {
        this.ulids = new Ulid(clock, random);
    }

    /** Deterministic ids for the demo seed. */
    public static Ids seeded(Clock clock, long seed) {
        return new Ids(clock, new Random(seed));
    }

    public String ulid() {
        return ulids.generate();
    }

    public String job() {
        return "job_" + ulid();
    }

    public String event() {
        return "evt_" + ulid();
    }

    public String approval() {
        return "appr_" + ulid();
    }

    public String serviceAccount() {
        return "sa_" + ulid();
    }

    public String token() {
        return "iaf_" + ulid() + ulid().substring(10);
    }
}
