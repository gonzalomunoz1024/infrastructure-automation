package com.iaf.platform.providers.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Problem.Fix;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.common.schema.Schemas.Mismatch;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.ErrorClass;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

/**
 * The wizard's Review step: the automated checks that replace a manual certification, judged
 * from what the platform itself saw (the {@link ReviewEvidence}) rather than from the file alone.
 * Pure: no lookups, no clock.
 *
 * <ul>
 * <li>{@code connection}: the last Test connection to dev and to test passed, against the
 * endpoints and credential the file declares now (only the ones the provider has);</li>
 * <li>{@code tried}: every action answered at least once when tried against dev, at the endpoint and
 * with the credential the file declares now;</li>
 * <li>{@code errors}: every error class has a match rule, and no tried error went unclassified;</li>
 * <li>{@code preview}: every destructive action has a plan action or a declared effect detail;</li>
 * <li>{@code responses}: no action's real response still disagrees with its schema after the overrides;</li>
 * <li>{@code slo-suggested}: an SLO suggested from the tried calls' p95, shown and never enforced.</li>
 * </ul>
 * Connection and tries apply to the providers the platform calls over HTTP (openapi and http);
 * the other types run through their runner or worker and skip them. Evidence a store no longer
 * keeps (it keeps the newest entries of a provider) is counted, and the review warns about it.
 */
public final class ProviderReview {

    public static final String CONNECTION = "connection";
    public static final String TRIED = "tried";
    public static final String ERRORS = "errors";
    public static final String PREVIEW = "preview";
    public static final String RESPONSES = "responses";
    public static final String SLO_SUGGESTED = "slo-suggested";
    public static final List<String> CHECK_IDS = List.of(CONNECTION, TRIED, ERRORS, PREVIEW, RESPONSES, SLO_SUGGESTED);
    /** The environments a connection must have passed in, among those the provider declares. */
    public static final List<String> CONNECTION_ENVIRONMENTS = List.of("dev", "test");
    /** Where actions are tried: the provider's dev environment. */
    public static final String SANDBOX = "dev";
    static final double SLO_HEADROOM = 1.5;
    static final int SLO_STEP_MS = 50;
    static final int SLO_MIN_MS = 100;
    private static final Set<ProviderType> CALLED_OVER_HTTP = Set.of(ProviderType.OPENAPI, ProviderType.HTTP);

    private ProviderReview() {
    }

    /** {@code observedP95Ms} over {@code calls} successful tries; the suggestion leaves headroom over it. */
    public record SloSuggestion(double successRate, int p95Ms, int observedP95Ms, int calls, String detail) {
    }

    public record Result(List<Check> checks, List<Problem> problems, SloSuggestion slo) {
        /** Nothing blocks the publish; warnings may remain. */
        @com.fasterxml.jackson.annotation.JsonProperty("ready")
        public boolean ready() {
            return problems.stream().noneMatch(Problem::isError);
        }

        public List<Problem> errors() {
            return problems.stream().filter(Problem::isError).toList();
        }
    }

    public static Result review(ProviderDefinition def, ReviewEvidence evidence) {
        Reviewer r = new Reviewer(def, evidence == null ? ReviewEvidence.NONE : evidence);
        r.connection();
        r.tried();
        r.errors();
        r.preview();
        r.responses();
        r.slo();
        return r.result();
    }

    /** The environment actions are tried in: dev when the provider has it, else its first environment. */
    public static String sandbox(ProviderDefinition def) {
        List<String> environments = def.environments();
        if (environments.isEmpty() || environments.contains(SANDBOX)) {
            return SANDBOX;
        }
        return environments.get(0);
    }

    /** dev and test where the provider declares them; a provider with neither tests its first environment. */
    public static List<String> connectionEnvironments(ProviderDefinition def) {
        List<String> environments = def.environments();
        List<String> required = CONNECTION_ENVIRONMENTS.stream().filter(environments::contains).toList();
        if (required.isEmpty() && !environments.isEmpty()) {
            return List.of(environments.get(0));
        }
        return required;
    }

    /** Whether the review proves connection and tries for this provider (it is called over HTTP). */
    public static boolean calledOverHttp(ProviderDefinition def) {
        return CALLED_OVER_HTTP.contains(def.type());
    }

    private static final class Reviewer {
        final ProviderDefinition def;
        final ReviewEvidence evidence;
        final List<Problem> problems = new ArrayList<>();
        final Map<String, List<Problem>> byCheck = new LinkedHashMap<>();
        final Map<String, String> details = new LinkedHashMap<>();
        final Set<String> skipped = new HashSet<>();
        SloSuggestion slo;

        Reviewer(ProviderDefinition def, ReviewEvidence evidence) {
            this.def = def;
            this.evidence = evidence;
            CHECK_IDS.forEach(id -> byCheck.put(id, new ArrayList<>()));
        }

        // ------------------------------------------------------------ connection

        void connection() {
            Map<String, String> endpoints = def.spec().endpoints() == null ? Map.of() : def.spec().endpoints();
            if (!calledOverHttp(def) || endpoints.isEmpty()) {
                skip(CONNECTION, calledOverHttp(def) ? "No endpoints to test" : typeName() + " provider has no endpoints to test");
                return;
            }
            List<String> required = connectionEnvironments(def);
            String credential = blankToNull(def.spec().auth() == null ? null : def.spec().auth().credential());
            List<String> passed = new ArrayList<>();
            for (String env : required) {
                String endpoint = endpoints.get(env);
                String field = "spec.endpoints." + env;
                Optional<ConnectionTest> last = evidence.lastConnection(env);
                if (last.isEmpty()) {
                    error(CONNECTION, "connection.untested", null, field, "Test the connection to " + env + " before publishing");
                    continue;
                }
                ConnectionTest t = last.get();
                if (!sameUrl(t.endpoint(), endpoint)) {
                    error(CONNECTION, "connection.stale", null, field, env + " was tested at " + t.endpoint() + ", but the provider now points at "
                            + endpoint + "; test the connection again");
                    continue;
                }
                if (!Objects.equals(blankToNull(t.credential()), credential)) {
                    error(CONNECTION, "connection.stale", null, "spec.auth.credential", "The connection to " + env + " was tested with "
                            + (blankToNull(t.credential()) == null ? "no credential" : t.credential()) + ", but the provider now uses "
                            + (credential == null ? "none" : credential) + "; test the connection again");
                    continue;
                }
                if (!t.ok()) {
                    error(CONNECTION, "connection.failed", null, field, "The last connection test to " + env + " failed"
                            + (t.message() == null || t.message().isBlank() ? "" : ": " + t.message()));
                    continue;
                }
                passed.add(env + " in " + t.durationMs() + " ms");
            }
            if (!endpoints.containsKey("test")) {
                warning(CONNECTION, "connection.noTest", null, "spec.endpoints", "There is no test endpoint, so only " + String.join(" and ", required)
                        + " was tested; add one so the connection is proven outside " + String.join(" and ", required) + " too");
            }
            if (evidence.droppedConnections() > 0) {
                warning(CONNECTION, "evidence.dropped", null, "spec.endpoints", dropped(evidence.droppedConnections(), "connection test", "connection tests"));
            }
            details.put(CONNECTION, "Answered: " + String.join(", ", passed));
        }

        // ------------------------------------------------------------ tried

        void tried() {
            if (!calledOverHttp(def)) {
                skip(TRIED, typeName() + " provider runs through its " + (def.type() == ProviderType.SDK ? "worker" : "runner")
                        + "; Try it is for openapi and http providers");
                return;
            }
            List<Action> actions = def.actions();
            if (actions.isEmpty()) {
                skip(TRIED, "No actions");
                return;
            }
            String env = sandbox(def);
            String endpoint = def.spec().endpoints() == null ? null : def.spec().endpoints().get(env);
            String credential = blankToNull(def.spec().auth() == null ? null : def.spec().auth().credential());
            int answered = 0;
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                String field = "spec.actions[" + i + "]";
                List<ActionTry> all = evidence.triesOf(a.id(), env);
                // a try proves the endpoint and credential it used: one made against others does not count
                List<ActionTry> tries = all.stream().filter(t -> current(t, endpoint, credential)).toList();
                if (tries.isEmpty() && !all.isEmpty()) {
                    ActionTry last = all.get(all.size() - 1);
                    error(TRIED, "action.staleTry", a.id(), field, sameUrl(last.endpoint(), endpoint)
                            ? a.id() + " was tried with " + (blankToNull(last.credential()) == null ? "no credential" : last.credential())
                                    + ", but the provider now uses " + (credential == null ? "none" : credential) + "; try it again"
                            : a.id() + " was tried at " + last.endpoint() + ", but the provider now points at " + endpoint + "; try it again");
                    continue;
                }
                if (tries.isEmpty()) {
                    error(TRIED, "action.untried", a.id(), field, "Try " + a.id() + " against " + env + " before publishing");
                    continue;
                }
                Optional<ActionTry> ok = tries.stream().filter(ActionTry::ok).reduce((first, second) -> second);
                if (ok.isEmpty()) {
                    error(TRIED, "action.tryFailed", a.id(), field, a.id() + " failed every time it was tried against " + env + " (last: "
                            + describe(tries.get(tries.size() - 1)) + "); try it with an input that exists");
                    continue;
                }
                answered++;
                if (a.destructive() && Boolean.FALSE.equals(ok.get().verified())) {
                    String verify = a.verify() == null || a.verify().action() == null ? "the verify action" : a.verify().action();
                    warning(TRIED, "action.notVerified", a.id(), field + ".verify", a.id() + " answered, but " + verify
                            + " still found the thing afterwards; check that the test resource is really gone");
                }
            }
            if (evidence.droppedTries() > 0) {
                warning(TRIED, "evidence.dropped", null, "spec.actions", dropped(evidence.droppedTries(), "try", "tries"));
            }
            details.put(TRIED, answered + " of " + actions.size() + (actions.size() == 1 ? " action" : " actions") + " answered in " + env);
        }

        /** "An ansible", "A terraform": the provider's type with its article. */
        String typeName() {
            String key = def.type().key();
            return ("aeiou".indexOf(Character.toLowerCase(key.charAt(0))) >= 0 ? "An " : "A ") + key;
        }

        static String describe(ActionTry t) {
            String cls = t.errorClass() == null || t.errorClass().isBlank() ? null : t.errorClass();
            if (t.status() > 0) {
                return "HTTP " + t.status() + (cls == null ? "" : ", " + cls);
            }
            return cls == null ? "no answer" : cls;
        }

        // ------------------------------------------------------------ errors

        void errors() {
            List<ErrorClass> errors = def.spec().errors() == null ? List.of() : def.spec().errors();
            boolean http = calledOverHttp(def);
            if (errors.isEmpty()) {
                if (!http) {
                    skip(ERRORS, "No error classes");
                    return;
                }
                // a gate for a provider that changes things, a warning for one that only reads
                String message = "No error classes: failures fall back to the HTTP status (404 not_found, 429 rate_limited, 5xx unavailable); "
                        + "classify the errors this API answers";
                if (def.changesThings()) {
                    error(ERRORS, "errors.none", null, "spec.errors", message + " before a provider that creates, changes or deletes things is published");
                } else {
                    warning(ERRORS, "errors.none", null, "spec.errors", message);
                }
            }
            for (int i = 0; i < errors.size(); i++) {
                ErrorClass e = errors.get(i);
                JsonNode match = e.match();
                String field = "spec.errors[" + i + "].match";
                if (match == null || !match.isObject() || match.isEmpty()) {
                    error(ERRORS, "errors.noMatch", null, field, "Error class " + e.errorClass() + " has no match rule; give it match: { status } "
                            + "and, when the status is shared, the body text");
                } else if (http && !match.path("status").canConvertToInt()) {
                    error(ERRORS, "errors.noStatus", null, field + ".status", "The match rule of " + e.errorClass()
                            + " needs a status; calls are classified by status first, then by body text");
                }
            }
            if (http) {
                Set<String> reported = new LinkedHashSet<>();
                for (ActionTry t : evidence.tries()) {
                    if (t.ok() || t.status() <= 0 || def.action(t.action()).isEmpty() || classified(errors, t.status())) {
                        continue;
                    }
                    if (reported.add(t.action() + "@" + t.status())) {
                        warning(ERRORS, "errors.unclassified", t.action(), "spec.errors", t.action() + " answered HTTP " + t.status()
                                + " when it was tried, which no error class matches; add one so retries and alerts know what it means");
                    }
                }
            }
            details.put(ERRORS, errors.size() + (errors.size() == 1 ? " error class" : " error classes"));
        }

        static boolean classified(List<ErrorClass> errors, int status) {
            return errors.stream().anyMatch(e -> e.match() != null && e.match().path("status").canConvertToInt() && e.match().path("status").asInt() == status);
        }

        // ------------------------------------------------------------ preview

        void preview() {
            List<Action> actions = def.actions();
            int destructive = 0;
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                if (!a.destructive()) {
                    continue;
                }
                destructive++;
                boolean plan = a.plan() != null && !a.plan().isBlank() && def.action(a.plan()).isPresent();
                boolean detail = a.effectDetail() != null && !a.effectDetail().isBlank();
                if (!plan && !detail) {
                    add(PREVIEW, Problem.error("action.noPreview", a.id(), "spec.actions[" + i + "].plan", a.id() + " deletes something: give it a plan "
                            + "action that previews the effect, or effectDetail with one line on what it removes", readAlike(a).orElse(null)));
                }
            }
            if (destructive == 0) {
                skip(PREVIEW, "No destructive actions");
                return;
            }
            details.put(PREVIEW, destructive + (destructive == 1 ? " destructive action" : " destructive actions") + " previewed");
        }

        /** A read action on the same noun ({@code vm.get} for {@code vm.delete}) makes a good plan. */
        Optional<Fix> readAlike(Action a) {
            int dot = a.id().indexOf('.');
            String noun = dot > 0 ? a.id().substring(0, dot + 1) : null;
            return def.actions().stream()
                    .filter(x -> x.effect() == Effect.READ && noun != null && x.id().startsWith(noun))
                    .findFirst()
                    .map(x -> Fix.replace("Preview with " + x.id(), x.id()));
        }

        // ------------------------------------------------------------ responses

        void responses() {
            String env = sandbox(def);
            List<Action> actions = def.actions();
            int compared = 0;
            int matching = 0;
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                Optional<ActionTry> answer = evidence.lastAnswer(a.id(), env);
                if (answer.isEmpty()) {
                    continue;
                }
                compared++;
                List<Mismatch> mismatches = Schemas.mismatches(def.effectiveOutput(a), answer.get().response());
                if (mismatches.isEmpty()) {
                    matching++;
                    continue;
                }
                List<String> shown = mismatches.stream().limit(3).map(m -> m.field() + " (declared " + m.expected() + ", got " + m.actual() + ")").toList();
                error(RESPONSES, "action.responseMismatch", a.id(), "spec.actions[" + i + "].output", a.id() + "'s real response still disagrees with "
                        + "its schema in " + mismatches.size() + (mismatches.size() == 1 ? " field: " : " fields: ") + String.join(", ", shown)
                        + (mismatches.size() > 3 ? ", …" : "") + "; use the real response to override the schema");
            }
            if (compared == 0) {
                skip(RESPONSES, "Nothing tried yet");
                return;
            }
            details.put(RESPONSES, matching + " of " + compared + (compared == 1 ? " response matches" : " responses match"));
        }

        // ------------------------------------------------------------ slo

        void slo() {
            List<Long> durations = evidence.tries().stream()
                    .filter(t -> t.ok() && def.action(t.action()).isPresent())
                    .map(ActionTry::durationMs)
                    .filter(d -> d >= 0)
                    .sorted()
                    .toList();
            if (durations.isEmpty()) {
                skip(SLO_SUGGESTED, "Try the actions to get a suggestion from their timings");
                return;
            }
            int observed = (int) (long) durations.get(Math.max(0, (int) Math.ceil(0.95 * durations.size()) - 1));
            int suggested = Math.max(SLO_MIN_MS, (int) (Math.ceil(observed * SLO_HEADROOM / SLO_STEP_MS) * SLO_STEP_MS));
            double rate = def.slo().successRate();
            String detail = "p95 of " + durations.size() + (durations.size() == 1 ? " tried call was " : " tried calls was ") + observed
                    + " ms; suggested p95Ms " + suggested + " (1.5 times, rounded up to 50 ms) and successRate " + ProviderHealthRule.pct(rate);
            slo = new SloSuggestion(rate, suggested, observed, durations.size(), detail);
            if (def.spec().slo() != null && def.slo().p95Ms() < observed) {
                add(SLO_SUGGESTED, Problem.warning("slo.belowObserved", null, "spec.slo.p95Ms", "The SLO's p95 of " + def.slo().p95Ms()
                        + " ms is below what the tries took (p95 " + observed + " ms); " + suggested + " ms would fit",
                        Fix.replace("Use " + suggested + " ms", String.valueOf(suggested))));
            }
            details.put(SLO_SUGGESTED, detail);
        }

        // ------------------------------------------------------------ result

        Result result() {
            List<Check> checks = new ArrayList<>();
            for (String id : CHECK_IDS) {
                List<Problem> ps = byCheck.get(id);
                long errors = ps.stream().filter(Problem::isError).count();
                String status = errors > 0 ? "failed" : skipped.contains(id) ? "skipped" : "passed";
                String detail = errors > 0
                        ? errors + (errors == 1 ? " error: " : " errors: ") + ps.stream().filter(Problem::isError).findFirst().map(Problem::message).orElse("")
                        : details.get(id);
                long warnings = ps.size() - errors;
                if (errors == 0 && warnings > 0) {
                    detail = (detail == null ? "" : detail + "; ") + warnings + (warnings == 1 ? " warning" : " warnings");
                }
                checks.add(new Check(id, label(id), status, detail));
            }
            return new Result(checks, List.copyOf(problems), slo);
        }

        static String label(String id) {
            return switch (id) {
                case CONNECTION -> "Connection works";
                case TRIED -> "Every action tried";
                case ERRORS -> "Errors classified";
                case PREVIEW -> "Destructive actions previewed";
                case RESPONSES -> "Responses match the schema";
                case SLO_SUGGESTED -> "SLO suggested";
                default -> id;
            };
        }

        void skip(String check, String detail) {
            skipped.add(check);
            details.put(check, detail);
        }

        void error(String check, String code, String step, String field, String message) {
            add(check, Problem.error(code, step, field, message));
        }

        void warning(String check, String code, String step, String field, String message) {
            add(check, Problem.warning(code, step, field, message));
        }

        void add(String check, Problem p) {
            problems.add(p);
            byCheck.get(check).add(p);
        }
    }

    static boolean sameUrl(String a, String b) {
        return Objects.equals(trimUrl(a), trimUrl(b));
    }

    /**
     * Whether a try was made against the endpoint and credential the file declares now. A try
     * recorded before the endpoint was kept (it has none) is taken as it is.
     */
    static boolean current(ActionTry t, String endpoint, String credential) {
        if (t.endpoint() == null) {
            return true;
        }
        return sameUrl(t.endpoint(), endpoint) && Objects.equals(blankToNull(t.credential()), credential);
    }

    /** What the review says about evidence a store no longer keeps. */
    static String dropped(int count, String one, String many) {
        return count + " older " + (count == 1 ? one + " of this provider is" : many + " of this provider are")
                + " no longer kept (a store keeps the newest ones only); the review judges what is left";
    }

    private static String trimUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
