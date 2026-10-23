package com.iaf.console.bff.digests.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.iaf.console.bff.notifications.domain.Links;

/**
 * Puts a digest together from its facts and the text a writer made of them: each section's facts
 * as the web reads them ({@code available} first, then the numbers, or the {@code reason} there
 * are none), its highlights, and the writer's text, or the template's for a headline or a section
 * the writer left out.
 */
public final class DigestComposer {

    public static final int ACCESS_HIGHLIGHTS = 10;

    private DigestComposer() {
    }

    public static Digest compose(DigestFacts facts, DigestText text, String source, Instant generatedAt) {
        DigestText rules = DigestTemplate.write(facts);
        String headline = blank(text.headline()) ? rules.headline() : text.headline().strip();
        List<Digest.Section> sections = new ArrayList<>();
        for (String kind : Digest.KINDS) {
            String written = text.texts().get(kind);
            sections.add(new Digest.Section(kind, Digest.titleOf(kind), blank(written) ? rules.texts().get(kind) : written.strip(), factsOf(facts, kind),
                    highlightsOf(facts, kind)));
        }
        return new Digest(facts.accountId(), facts.weekStart(), facts.weekEnd(), generatedAt, source, headline, sections);
    }

    static Map<String, Object> factsOf(DigestFacts facts, String kind) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case Digest.CHANGES -> {
                DigestFacts.Changes c = facts.changes();
                if (unavailable(out, c.unavailable())) {
                    break;
                }
                out.put("requests", c.requests());
                out.put("selfServed", c.selfServed());
                if (c.selfServedShare() != null) {
                    out.put("selfServedShare", c.selfServedShare());
                }
                out.put("failed", c.failed());
                out.put("busiest", c.busiest().stream().map(b -> Map.<String, Object>of("workflow", b.workflow(), "requests", b.requests())).toList());
            }
            case Digest.DRIFT -> {
                DigestFacts.Drift d = facts.drift();
                if (unavailable(out, d.unavailable())) {
                    break;
                }
                out.put("found", d.found());
                out.put("fixed", d.fixed());
                out.put("open", d.open());
            }
            case Digest.COST -> {
                DigestFacts.Cost k = facts.cost();
                if (unavailable(out, k.unavailable())) {
                    break;
                }
                out.put("currency", k.currency());
                out.put("spend", k.spend().doubleValue());
                out.put("previous", k.previous().doubleValue());
                if (k.change() != null) {
                    out.put("change", k.change());
                }
                out.put("top", k.top().stream()
                        .map(s -> Map.<String, Object>of("service", s.service(), "label", s.label(), "spend", s.amount().doubleValue())).toList());
            }
            case Digest.ACCESS -> {
                DigestFacts.Access a = facts.access();
                if (unavailable(out, a.unavailable())) {
                    break;
                }
                if (a.since() != null) {
                    out.put("since", a.since().toString());
                }
                out.put("partial", a.partial(facts.weekStart()));
                out.put("membershipsGranted", a.count(AccessFact.MEMBERSHIP));
                out.put("rolesGranted", a.count(AccessFact.ROLE));
                out.put("accessKeysCreated", a.count(AccessFact.ACCESS_KEY));
                out.put("wildcardPolicies", a.count(AccessFact.WILDCARD_POLICY));
            }
            default -> throw new IllegalArgumentException("no digest section " + kind);
        }
        return out;
    }

    static List<Digest.Highlight> highlightsOf(DigestFacts facts, String kind) {
        return switch (kind) {
            case Digest.CHANGES -> facts.changes().failures().stream()
                    .map(f -> new Digest.Highlight((f.workflow() == null ? "A request" : f.workflow()) + " failed", Links.job(f.jobId()))).toList();
            case Digest.COST -> facts.cost().top().stream()
                    .map(s -> new Digest.Highlight(s.label() + ": " + DigestTemplate.money(s.amount(), facts.cost().currency()), null)).toList();
            case Digest.ACCESS -> facts.access().facts().stream().limit(ACCESS_HIGHLIGHTS).map(f -> new Digest.Highlight(f.text(), null)).toList();
            default -> List.of();
        };
    }

    private static boolean unavailable(Map<String, Object> out, String reason) {
        out.put("available", reason == null);
        if (reason != null) {
            out.put("reason", reason);
        }
        return reason != null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
