package com.iaf.console.bff.digests.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.insights.domain.DriftNumbers;
import com.iaf.console.bff.insights.domain.RequestRecord;

/**
 * What a weekly digest says, before anyone writes it up (decision 32.4): an account's week and,
 * per section, either the numbers or why they could not be had ({@code unavailable}).
 */
public record DigestFacts(String accountId, String accountName, LocalDate weekStart, Changes changes, Drift drift, Cost cost, Access access) {

    public static final int BUSIEST = 3;
    public static final int FAILURES = 5;
    public static final int TOP_SPEND = 3;

    public DigestFacts {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(weekStart, "weekStart");
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(drift, "drift");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(access, "access");
    }

    public LocalDate weekEnd() {
        return DigestWeeks.lastDay(weekStart);
    }

    /** The account as people know it: its name, else its id. */
    public String accountLabel() {
        return accountName == null || accountName.isBlank() ? accountId : accountName;
    }

    // ---- changes

    /** The week's console requests: how many, how many self-served and failed, the busiest workflows and the latest failures. */
    public record Changes(String unavailable, long requests, long selfServed, long failed, List<Busiest> busiest, List<Failure> failures) {

        public Changes {
            busiest = busiest == null ? List.of() : List.copyOf(busiest);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public static Changes unavailable(String reason) {
            return new Changes(reason, 0, 0, 0, List.of(), List.of());
        }

        /** The numbers of the requests of a week, as the executive home counts them. */
        public static Changes of(List<RequestRecord> records) {
            long selfServed = records.stream().filter(RequestRecord::selfServed).count();
            long failed = records.stream().filter(r -> r.outcome() == RequestRecord.Outcome.FAILED).count();
            Map<String, Long> byWorkflow = new LinkedHashMap<>();
            records.forEach(r -> byWorkflow.merge(r.workflow() == null ? "unknown" : r.workflow(), 1L, Long::sum));
            List<Busiest> busiest = byWorkflow.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(BUSIEST).map(e -> new Busiest(e.getKey(), e.getValue())).toList();
            List<Failure> failures = records.stream().filter(r -> r.outcome() == RequestRecord.Outcome.FAILED)
                    .sorted(Comparator.comparing(RequestRecord::startedAt).reversed()).limit(FAILURES)
                    .map(r -> new Failure(r.jobId(), r.workflow())).toList();
            return new Changes(null, records.size(), selfServed, failed, busiest, failures);
        }

        public boolean available() {
            return unavailable == null;
        }

        /** The self-served requests' share of all, 0 to 1 to two places; null without requests. */
        public Double selfServedShare() {
            return requests == 0 ? null : BigDecimal.valueOf(selfServed).divide(BigDecimal.valueOf(requests), 2, RoundingMode.HALF_UP).doubleValue();
        }
    }

    public record Busiest(String workflow, long requests) {
    }

    public record Failure(String jobId, String workflow) {
    }

    // ---- drift

    /** The drift service's findings of the week: found and fixed in it, open at its end. */
    public record Drift(String unavailable, long found, long fixed, long open) {

        public static Drift unavailable(String reason) {
            return new Drift(reason, 0, 0, 0);
        }

        public static Drift of(DriftNumbers numbers) {
            return new Drift(null, numbers.found(), numbers.fixed(), numbers.open());
        }

        public boolean available() {
            return unavailable == null;
        }
    }

    // ---- cost

    /** The week's spend against the week before's, in the cost service's currency, and the services most of it went to. */
    public record Cost(String unavailable, String currency, BigDecimal spend, BigDecimal previous, List<Spend> top) {

        public Cost {
            top = top == null ? List.of() : List.copyOf(top);
        }

        public static Cost unavailable(String reason) {
            return new Cost(reason, null, null, null, List.of());
        }

        /**
         * The facts of two cost reports ({@code GET /v1/cost}): the week's, grouped by service, and
         * the week before's; a report without a {@code total} makes the section unavailable.
         */
        public static Cost of(JsonNode week, JsonNode weekBefore) {
            if (week == null || !week.path("total").isNumber() || weekBefore == null || !weekBefore.path("total").isNumber()) {
                return unavailable("the cost service's answer had no total");
            }
            List<Spend> top = new ArrayList<>();
            for (JsonNode group : week.path("groups")) {
                String key = group.path("key").asText("");
                if (key.isBlank() || key.startsWith("__") || !group.path("total").isNumber() || group.path("total").decimalValue().signum() <= 0) {
                    continue;
                }
                top.add(new Spend(key, group.path("label").asText(key), money(group.path("total").decimalValue())));
            }
            top.sort(Comparator.comparing(Spend::amount).reversed().thenComparing(Spend::service));
            String currency = week.path("currency").asText("USD");
            return new Cost(null, currency, money(week.get("total").decimalValue()), money(weekBefore.get("total").decimalValue()),
                    top.subList(0, Math.min(TOP_SPEND, top.size())));
        }

        public boolean available() {
            return unavailable == null;
        }

        /** The change on the week before, as a fraction to three places; null when the week before cost nothing. */
        public Double change() {
            if (spend == null || previous == null || previous.signum() == 0) {
                return null;
            }
            return spend.subtract(previous).divide(previous, 3, RoundingMode.HALF_UP).doubleValue();
        }

        private static BigDecimal money(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record Spend(String service, String label, BigDecimal amount) {
    }

    // ---- access

    /**
     * The grants the identity events of the week showed, once each, oldest first, and since when
     * the BFF has followed those events (a week it began following in is {@code partial}).
     */
    public record Access(String unavailable, Instant since, List<AccessFact> facts) {

        public Access {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        public static Access unavailable(String reason) {
            return new Access(reason, null, List.of());
        }

        /** The facts of a week, a grant seen twice (a group's change and its member's) kept once. */
        public static Access of(Instant since, List<AccessFact> recorded) {
            List<AccessFact> once = new ArrayList<>();
            recorded.stream().sorted(Comparator.comparing(AccessFact::at)).forEach(f -> {
                if (once.stream().noneMatch(f::sameGrant)) {
                    once.add(f);
                }
            });
            return new Access(null, since, once);
        }

        public boolean available() {
            return unavailable == null;
        }

        public boolean partial(LocalDate weekStart) {
            return since != null && since.isAfter(DigestWeeks.start(weekStart));
        }

        public long count(String kind) {
            return facts.stream().filter(f -> f.kind().equals(kind)).count();
        }
    }
}
