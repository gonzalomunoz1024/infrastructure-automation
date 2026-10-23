package com.iaf.console.search.load;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.forAll;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.regex;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.ResourceState;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

/**
 * The console's searches at the scale targets (WP 6.2), against a running indexer whose OpenSearch
 * holds the {@link Estate} ({@code ./gradlew seedEstate} with the same {@code load.*} settings).
 * Each virtual user is a person at the console, behind the BFF: they open lists (the counts for the
 * header and the state chips; the rows come from the state service), type in the header's search (a
 * suggestion per keystroke, then the search), use the filter bar (a page sorted newest first,
 * sometimes the next one), ask the copilot in plain English and look a resource up by its CMDB tag
 * or its wfrn, thinking 5 to 15 seconds between actions; a few are the executive home, the BFF
 * counting 20 accounts at once. One user in ten works in the
 * largest account (a million resources at ten million), the others in an account chosen by the
 * square root of its size.
 *
 * <p>Every name, tag and wfrn lookup checks that the resource looked for is the first hit: gate 6's
 * "search finds any resource by name, tag or wfrn". The assertions are the plan's targets: search
 * p95 under 500 ms, a list's counts under 300 ms (the list's target), a suggestion under 300 ms (each
 * keystroke), the executive home's counts over 20 accounts under 1 s, and under 1% failed requests,
 * a 429 of the query budget included.
 *
 * <pre>
 *   ./gradlew gatlingRun -Pload.users=1000 -Pload.duration=PT10M -Pload.baseUrl=http://localhost:18085
 * </pre>
 */
public final class SearchSimulation extends Simulation {

    private final Estate estate = Estate.fromProperties();
    private final String baseUrl = System.getProperty("load.baseUrl", "http://localhost:18085");
    private final int users = Integer.getInteger("load.users", 100);
    private final Duration ramp = Duration.parse(System.getProperty("load.ramp", "PT1M"));
    private final Duration duration = Duration.parse(System.getProperty("load.duration", "PT5M"));
    private final double hotShare = Double.parseDouble(System.getProperty("load.hotShare", "0.1"));
    private final Duration thinkMin = Duration.parse(System.getProperty("load.thinkMin", "PT5S"));
    private final Duration thinkMax = Duration.parse(System.getProperty("load.thinkMax", "PT15S"));
    private final long searchP95 = Long.getLong("load.searchP95", 500);
    private final long listP95 = Long.getLong("load.listP95", 300);
    private final long suggestP95 = Long.getLong("load.suggestP95", 300);
    private final long homeP95 = Long.getLong("load.homeP95", 1_000);
    private final double maxFailedPercent = Double.parseDouble(System.getProperty("load.maxFailedPercent", "1.0"));

    /** The cumulative square roots of the account sizes, to choose an account by them. */
    private final double[] weights = cumulativeWeights(estate);
    private final AtomicLong persons = new AtomicLong();

    private final HttpProtocolBuilder protocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json")
            // the BFF calls the indexer over a pool of its own, not a connection per person
            .shareConnections()
            .disableWarmUp()
            .header("X-Console-Region", Estate.REGION);

    /** One record per action: who acts (the account and the person) and the resource the action is about. */
    private final Iterator<Map<String, Object>> targets = new Iterator<>() {
        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            return target();
        }
    };

    private ChainBuilder asPerson(HttpRequestActionBuilder request) {
        return exec(request.header("X-Console-Account", "#{account}").header("X-Console-User", "#{user}").header("X-Console-Roles", "viewer"));
    }

    private final ChainBuilder openAList = asPerson(http("list counts").get("/v1/counts").queryParam("type", "#{type}").check(status().is(200)));

    private final ChainBuilder typeInTheHeader = repeat(session -> session.getInt("keystrokes"), "k").on(
            asPerson(http("suggest").get("/v1/suggest").queryParam("q", session -> session.getString("name").substring(0, 2 + session.getInt("k")))
                    .check(status().is(200)))
                    .pause(Duration.ofMillis(120), Duration.ofMillis(350)))
            .exec(asPerson(http("search by name").get("/v1/search").queryParam("q", "#{name}")
                    .check(status().is(200), jsonPath("$.items[0].wfrn").isEL("#{wfrn}"))));

    private final ChainBuilder useTheFilterBar = exec(asPerson(http("filter bar").get("/v1/search").queryParam("q", "#{filter}").queryParam("type", "#{type}")
            .queryParam("sort", "-created").queryParam("limit", "50")
            .check(status().is(200), regex("\"next\"\\s*:\\s*\"([^\"]+)\"").optional().saveAs("next"))))
            .doIf(session -> session.contains("next") && ThreadLocalRandom.current().nextDouble() < 0.3)
            .then(asPerson(http("filter bar next page").get("/v1/search").queryParam("q", "#{filter}").queryParam("type", "#{type}")
                    .queryParam("sort", "-created").queryParam("limit", "50").queryParam("after", "#{next}").check(status().is(200))))
            .exec(session -> session.remove("next"));

    private final ChainBuilder lookUpByTag = asPerson(http("search by tag").get("/v1/search").queryParam("q", "tag:cmdb=#{cmdb}")
            .check(status().is(200), jsonPath("$.items[0].wfrn").isEL("#{wfrn}")));

    private final ChainBuilder lookUpByWfrn = asPerson(http("search by wfrn").get("/v1/search").queryParam("q", "#{wfrn}")
            .check(status().is(200), jsonPath("$.items[0].wfrn").isEL("#{wfrn}")));

    private final ChainBuilder askTheCopilot = asPerson(http("interpret").get("/v1/search:interpret").queryParam("q", "#{plainEnglish}")
            .check(status().is(200)));

    private final ChainBuilder openTheExecutiveHome = exec(http("counts across accounts").get("/v1/counts").queryParam("groupBy", "type")
            .multivaluedQueryParam("accountId", session -> session.getList("accounts"))
            .header("X-Console-Service", "console-bff")
            .check(status().is(200)));

    private final ScenarioBuilder people = scenario("people at the console").during(duration).on(
            feed(targets),
            randomSwitch().on(
                    percent(35.0).then(openAList),
                    percent(25.0).then(typeInTheHeader),
                    percent(20.0).then(useTheFilterBar),
                    percent(5.0).then(lookUpByTag),
                    percent(5.0).then(lookUpByWfrn),
                    percent(5.0).then(askTheCopilot),
                    percent(5.0).then(openTheExecutiveHome)),
            exec(session -> session.remove("next")).pause(thinkMin, thinkMax));

    {
        List<Assertion> assertions = new ArrayList<>();
        for (String search : List.of("search by name", "search by tag", "search by wfrn", "filter bar", "filter bar next page", "interpret")) {
            assertions.add(details(search).responseTime().percentile(95.0).lt((int) searchP95));
        }
        assertions.add(details("list counts").responseTime().percentile(95.0).lt((int) listP95));
        assertions.add(details("suggest").responseTime().percentile(95.0).lt((int) suggestP95));
        assertions.add(details("counts across accounts").responseTime().percentile(95.0).lt((int) homeP95));
        assertions.add(global().failedRequests().percent().lt(maxFailedPercent));
        assertions.add(forAll().failedRequests().percent().lt(Math.max(maxFailedPercent, 1.0)));
        setUp(people.injectClosed(rampConcurrentUsers(0).to(users).during(ramp), constantConcurrentUsers(users).during(duration)))
                .protocols(protocol)
                .maxDuration(ramp.plus(duration).plus(thinkMax).plusSeconds(30))
                .assertions(assertions);
    }

    /** The person, the account and the resource of one action. */
    private Map<String, Object> target() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int account = random.nextDouble() < hotShare ? 0 : weighted(random.nextDouble() * weights[weights.length - 1]);
        IndexedResource resource;
        do {
            resource = estate.resource(estate.accountStart(account) + random.nextLong(estate.accountSize(account)));
        } while (resource.state() == ResourceState.DELETED && estate.accountSize(account) > 1);
        String type = resource.type();
        String env = resource.tags().get("env");
        String team = resource.tags().get("team");
        String app = resource.name().substring(0, resource.name().indexOf('-'));
        Map<String, Object> record = new HashMap<>();
        record.put("account", resource.accountId());
        record.put("user", "person-" + (persons.incrementAndGet() % 10_000));
        record.put("type", type);
        record.put("name", resource.name());
        record.put("wfrn", resource.wfrn());
        record.put("cmdb", resource.tags().get("cmdb"));
        record.put("keystrokes", 3 + random.nextInt(4));
        String anchorDay = Estate.anchorDay().toString();
        List<String> filters = List.of(
                "tag:env=" + env + " state:failed",
                "tag:team=" + team,
                "drift:true",
                app + " tag:env=" + env,
                "created:>=" + Estate.anchorDay().minusDays(30),
                "updated:<" + anchorDay + " state:active " + app,
                "state:pending");
        record.put("filter", filters.get(random.nextInt(filters.size())));
        String plural = switch (type) {
            case "vm" -> "virtual machines";
            case "database" -> "databases";
            case "volume" -> "volumes";
            case "image" -> "images";
            default -> "environments";
        };
        List<String> phrases = List.of(
                "failed " + plural + " in " + env,
                app + " " + plural + " tagged team " + team,
                plural + " changed outside the console",
                plural + " created between sep 1 and sep 30, newest first",
                "broken " + plural);
        record.put("plainEnglish", phrases.get(random.nextInt(phrases.size())));
        List<String> accounts = new ArrayList<>(20);
        for (int k = 0; k < Math.min(20, estate.accounts()); k++) {
            accounts.add(Estate.accountId((account + k) % estate.accounts()));
        }
        record.put("accounts", accounts);
        return record;
    }

    private int weighted(double u) {
        int lo = 0;
        int hi = weights.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (weights[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double[] cumulativeWeights(Estate estate) {
        double[] w = new double[estate.accounts()];
        double sum = 0;
        for (int k = 0; k < w.length; k++) {
            sum += Math.sqrt(estate.accountSize(k));
            w[k] = sum;
        }
        return w;
    }
}
