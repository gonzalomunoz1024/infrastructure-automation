package com.iaf.console.bff.assistant.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantAnswer.FailureReading;
import com.iaf.console.bff.assistant.domain.AssistantProposal;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.assistant.domain.AssistantRequest.Subject;
import com.iaf.console.bff.assistant.domain.AssistantRequest.TypeInfo;
import com.iaf.console.bff.assistant.domain.AssistantRequest.VerbInfo;
import com.iaf.console.bff.assistant.domain.ConsolePaths;
import com.iaf.console.bff.assistant.domain.CostClaims;
import com.iaf.console.bff.assistant.domain.InputForm;
import com.iaf.console.bff.assistant.domain.PlainText;
import com.iaf.console.bff.assistant.domain.ResourceExplanation;
import com.iaf.console.bff.assistant.domain.SearchFilter;
import com.iaf.console.bff.jobs.domain.FailureAdvice;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Every answer passes here before it is stored: what an assistant may propose is decided by the
 * person's own permissions, never by the assistant. At most five proposals; a kind the console
 * knows; a {@code filter} over a type the person may list, reduced to the list's own fields; a
 * {@code create} of a type the person may create ({@code <service>:create}) with its input
 * reduced to the form; an {@code action} on a resource of the session's account and region with a
 * verb the person may run on that very wfrn; an {@code open} of a console route. Text loses its
 * markup, and a sentence stating a cost the cost service did not answer is dropped. What fails is
 * dropped and logged with the reason.
 */
public class AnswerReview {

    private static final Logger log = LoggerFactory.getLogger(AnswerReview.class);

    static final int MAX_TEXT = 4000;
    static final int MAX_LABEL = 200;
    static final int MAX_SUMMARY = 1000;
    static final int MAX_TITLE = 120;
    static final int MAX_SECTION = 2000;
    static final int MAX_SECTIONS = 10;
    static final int MAX_FIX = 1000;
    /** The most proposals of one answer the review reads (each action is a policy decision and a read); five of them are kept at most. */
    static final int REVIEWED_AT_MOST = 20;
    static final List<String> CONFIDENCES = List.of(FailureAdvice.HIGH, FailureAdvice.MEDIUM, FailureAdvice.LOW);

    private final Access access;
    private final Resources resources;

    public AnswerReview(Access access, Resources resources) {
        this.access = access;
        this.resources = resources;
    }

    /** What of an answer the person sees: its text, the proposals that passed and, for {@code explain}, the explanation with the BFF's facts. */
    public record Reviewed(String text, List<AssistantProposal> proposals, ResourceExplanation explanation) {

        public Reviewed {
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
        }

        /** Nothing the console can show. */
        public boolean isEmpty() {
            return text == null && proposals.isEmpty() && explanation == null;
        }
    }

    /** The answer as the person may see it; each part dropped is logged with why. */
    public Mono<Reviewed> review(Session session, AssistantRequest request, AssistantAnswer answer) {
        List<String> notes = new ArrayList<>();
        BigDecimal cost = costOf(request.subject());
        String text = CostClaims.only(PlainText.of(answer.text(), MAX_TEXT), cost, notes);
        ResourceExplanation explanation = null;
        if (request.explains() && request.subject() != null) {
            explanation = explanation(request.subject(), answer.explanation(), text, cost, notes);
        } else if (answer.explanation() != null) {
            notes.add("an explanation in a turn that explains nothing");
        }
        ResourceExplanation explained = explanation;
        List<AssistantProposal> offered = answer.proposals();
        if (offered.size() > REVIEWED_AT_MOST) {
            notes.add((offered.size() - REVIEWED_AT_MOST) + " proposals past the " + REVIEWED_AT_MOST + " an answer is reviewed with");
            offered = offered.subList(0, REVIEWED_AT_MOST);
        }
        return Flux.fromIterable(offered)
                .concatMap(p -> proposal(session, request, p, cost, notes).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collectList()
                .map(passed -> {
                    List<AssistantProposal> kept = passed;
                    if (passed.size() > AssistantProposal.MAX) {
                        notes.add((passed.size() - AssistantProposal.MAX) + " proposals over the five an answer carries");
                        kept = passed.subList(0, AssistantProposal.MAX);
                    }
                    for (String note : notes) {
                        log.info("copilot turn {} of {}: dropped {}", request.turnId(), request.account() == null ? "?" : request.account().accountId(), note);
                    }
                    return new Reviewed(text, kept, explained);
                });
    }

    /** One proposal as the person may open it; empty when it does not pass. */
    Mono<AssistantProposal> proposal(Session session, AssistantRequest request, AssistantProposal p, BigDecimal cost, List<String> notes) {
        if (p.kind() == null || !AssistantProposal.KINDS.contains(p.kind())) {
            notes.add("a proposal of kind '" + p.kind() + "', which the console does not open");
            return Mono.empty();
        }
        String label = CostClaims.only(PlainText.line(p.label(), MAX_LABEL), cost, notes);
        if (label == null) {
            notes.add("a " + p.kind() + " proposal without a label");
            return Mono.empty();
        }
        return switch (p.kind()) {
            case AssistantProposal.FILTER -> Mono.justOrEmpty(filter(request, p, label, notes));
            case AssistantProposal.CREATE -> Mono.justOrEmpty(create(request, p, label, notes));
            case AssistantProposal.ACTION -> action(session, request, p, label, notes);
            default -> Mono.justOrEmpty(open(p, label, notes));
        };
    }

    /** A filter opens a type's list, so it names a type the person may list; its filter is reduced to the list's own fields. */
    static AssistantProposal filter(AssistantRequest request, AssistantProposal p, String label, List<String> notes) {
        if (p.type() == null) {
            notes.add("a filter of no type, which opens no list");
            return null;
        }
        TypeInfo type = request.type(p.type()).filter(TypeInfo::list).orElse(null);
        if (type == null) {
            notes.add("a filter over '" + p.type() + "', which the person may not list");
            return null;
        }
        SearchFilter filter = p.filter() == null ? null : p.filter().reduced(notes);
        String sort = null;
        if (p.sort() != null) {
            if (AssistantProposal.SORTS.contains(p.sort())) {
                sort = p.sort();
            } else {
                notes.add("the sort '" + p.sort() + "', which the list does not take");
            }
        }
        return AssistantProposal.filter(label, type.service(), type.type(), filter, sort);
    }

    static AssistantProposal create(AssistantRequest request, AssistantProposal p, String label, List<String> notes) {
        TypeInfo type = request.type(p.type()).filter(TypeInfo::create).orElse(null);
        if (type == null) {
            notes.add("a create of '" + p.type() + "', which the person may not create");
            return null;
        }
        InputForm form = InputForm.of(type.createInput());
        Map<String, Object> input = form.fill(p.input(), notes);
        return AssistantProposal.create(label, type.service(), type.type(), input, form.missing(input));
    }

    /** An action on a resource of the session's account and region, with a verb the person may run on that wfrn, on a resource that is there. */
    Mono<AssistantProposal> action(Session session, AssistantRequest request, AssistantProposal p, String label, List<String> notes) {
        Wfrn name;
        try {
            name = Wfrn.parse(p.wfrn());
        } catch (WfrnException e) {
            notes.add("an action on '" + p.wfrn() + "', which is no wfrn");
            return Mono.empty();
        }
        if (!name.in(session.accountId(), session.region())) {
            notes.add("an action on " + name.format() + ", of another account or region");
            return Mono.empty();
        }
        TypeInfo type = request.type(name.type()).orElse(null);
        VerbInfo verb = type == null || p.verb() == null ? null : type.verb(p.verb()).orElse(null);
        if (verb == null) {
            notes.add("the action '" + p.verb() + "' on " + name.format() + ", which the person may not run on its type");
            return Mono.empty();
        }
        InputForm form = InputForm.of(verb.input());
        Map<String, Object> input = form.fill(p.input(), notes);
        AssistantProposal checked = AssistantProposal.action(label, type.service(), type.type(), name.format(), verb.verb(), input, form.missing(input));
        return access.decide(session, List.of(new AccessCheck(AccessCheck.action(type.service(), verb.verb()), name.format())))
                .flatMap(decisions -> {
                    if (!decisions.get(0).allowed()) {
                        notes.add("the action '" + verb.verb() + "' on " + name.format() + ", which the person may not run there");
                        return Mono.<AssistantProposal>empty();
                    }
                    return resources.get(session, type.type(), name.format(), null).map(found -> checked);
                })
                .onErrorResume(e -> {
                    notes.add("the action '" + verb.verb() + "' on " + name.format() + ", which the person cannot read (" + e.getMessage() + ")");
                    return Mono.empty();
                });
    }

    static AssistantProposal open(AssistantProposal p, String label, List<String> notes) {
        if (!ConsolePaths.isConsolePath(p.path())) {
            notes.add("an open of '" + p.path() + "', which is no console route");
            return null;
        }
        return AssistantProposal.open(label, p.path());
    }

    /**
     * The explanation of the subject: the assistant's summary and sections in plain words (the
     * answer's first paragraph when it wrote no summary, a sentence from the facts when it wrote
     * nothing), with the wfrn and the facts the BFF read itself.
     */
    static ResourceExplanation explanation(Subject subject, ResourceExplanation given, String text, BigDecimal cost, List<String> notes) {
        String summary = given == null ? null : CostClaims.only(PlainText.of(given.summary(), MAX_SUMMARY), cost, notes);
        if (summary == null && text != null) {
            summary = PlainText.of(text.split("\n\n", 2)[0], MAX_SUMMARY);
        }
        if (summary == null) {
            summary = summaryOf(subject);
        }
        List<ResourceExplanation.Section> sections = new ArrayList<>();
        if (given != null && given.sections() != null) {
            for (ResourceExplanation.Section section : given.sections()) {
                String title = PlainText.line(section.title(), MAX_TITLE);
                String body = CostClaims.only(PlainText.of(section.text(), MAX_SECTION), cost, notes);
                if (title == null || body == null) {
                    notes.add("a section without a title or a text");
                } else if (sections.size() >= MAX_SECTIONS) {
                    notes.add("sections over the " + MAX_SECTIONS + " an explanation carries");
                    break;
                } else {
                    sections.add(new ResourceExplanation.Section(title, body));
                }
            }
        }
        return new ResourceExplanation(subject.wfrn(), summary, sections, subject.facts());
    }

    /** One sentence from the facts alone. */
    static String summaryOf(Subject subject) {
        String name = subject.name() != null ? subject.name() : subject.wfrn();
        return name + " is a " + subject.type() + " in " + subject.region() + ", " + (subject.state() == null ? "of unknown state" : subject.state()) + ".";
    }

    /**
     * The copilot's reading of a failed job as the failure explainer shows it: a title and a fix
     * in plain words, the platform's own message as the detail (never the assistant's), an input
     * reduced to the workflow's form without the subject or a secret field, and a confidence of
     * high, medium or low (low when the assistant said none of them). Empty when the reading has
     * no title or no fix.
     */
    public Optional<FailureAdvice> failure(AssistantRequest request, AssistantAnswer answer) {
        List<String> notes = new ArrayList<>();
        FailureReading reading = answer.failure();
        Optional<FailureAdvice> advice = Optional.empty();
        if (reading == null || request.failure() == null) {
            notes.add("an answer to a failed job without a reading of it");
        } else {
            String title = CostClaims.only(PlainText.line(reading.title(), MAX_TITLE), null, notes);
            String fix = CostClaims.only(PlainText.of(reading.fix(), MAX_FIX), null, notes);
            if (title == null || fix == null) {
                notes.add("a reading of a failed job without a title or a fix");
            } else {
                String confidence = reading.confidence() != null && CONFIDENCES.contains(reading.confidence()) ? reading.confidence() : FailureAdvice.LOW;
                Map<String, Object> input = InputForm.of(request.failure().inputForm()).fill(reading.input(), notes);
                advice = Optional.of(new FailureAdvice(title, request.failure().message(), fix, input, confidence, List.of()));
            }
        }
        for (String note : notes) {
            log.info("copilot reading of job {}: dropped {}", request.failure() == null ? "?" : request.failure().jobId(), note);
        }
        return advice;
    }

    private static BigDecimal costOf(Subject subject) {
        return subject == null || subject.monthlyCost() == null ? null : BigDecimal.valueOf(subject.monthlyCost());
    }
}
