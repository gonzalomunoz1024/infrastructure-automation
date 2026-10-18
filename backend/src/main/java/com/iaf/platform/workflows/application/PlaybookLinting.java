package com.iaf.platform.workflows.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort;
import com.iaf.platform.playbooks.ports.outbound.PlaybookLintPort.LintResult;
import com.iaf.platform.workflows.domain.PlaybookPins;
import com.iaf.platform.workflows.domain.PlaybookPins.Lint;
import com.iaf.platform.workflows.domain.PlaybookPins.Source;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs the playbook linter ({@code platform.playbooks.lint-command}, the one uploads go through)
 * at publish on every git playbook the version pins: the repository at the pinned commit is
 * checked out, copied to a directory of its own (the checkout cache is shared and a linter may
 * write next to the files; links are not copied), and the linter runs there with the playbook's
 * path. A non-zero exit, or a playbook that cannot be fetched, is a finding of the playbooks
 * check. Nothing is fetched when no linter is configured; within one publish a repository is
 * checked out once per commit and a playbook linted once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaybookLinting {

    private record Result(Source source, boolean linted, String finding) {
    }

    private final GitRemotePort git;
    private final PlaybookLintPort lint;
    private final PlaybooksProperties props;

    /** Whether a linter is configured; blank {@code platform.playbooks.lint-command} lints nothing. */
    public boolean configured() {
        String command = props.getLintCommand();
        return command != null && !command.isBlank();
    }

    public Mono<Lint> lint(WorkflowDefinition def) {
        List<Source> sources = PlaybookPins.pinnedSources(def);
        if (sources.isEmpty() || !configured()) {
            return Mono.just(Lint.NONE);
        }
        Map<String, Mono<Path>> checkouts = new HashMap<>();
        Map<String, Mono<Optional<String>>> asked = new HashMap<>();
        return Flux.fromIterable(sources)
                .concatMap(s -> asked.computeIfAbsent(s.repo() + "\n" + s.commit() + "\n" + s.credential() + "\n" + s.path(), k -> one(s, checkouts).cache())
                        .map(output -> new Result(s, true, output.map(o -> "step " + s.step() + " (" + s.describe() + "): " + o).orElse(null)))
                        .onErrorResume(e -> Mono.just(new Result(s, false, "step " + s.step() + ": could not lint " + s.describe() + " (" + message(e) + ")"))))
                .collectList()
                .map(results -> {
                    List<String> linted = new ArrayList<>();
                    List<String> findings = new ArrayList<>();
                    for (Result r : results) {
                        if (r.linted()) {
                            linted.add(r.source().step());
                        }
                        if (r.finding() != null) {
                            findings.add(r.finding());
                        }
                    }
                    return new Lint(linted, findings);
                });
    }

    /** What the linter printed when it refused the playbook, empty when it passed; an error when it could not run on it. */
    private Mono<Optional<String>> one(Source s, Map<String, Mono<Path>> checkouts) {
        if (!PlaybookPins.insideRepository(s.path())) {
            return Mono.error(new IllegalArgumentException("the playbook path " + s.path() + " leaves the repository"));
        }
        return checkouts.computeIfAbsent(s.repo() + "\n" + s.commit() + "\n" + s.credential(),
                        k -> Mono.defer(() -> git.checkout(s.repo(), s.commit(), s.credential())).cache())
                .flatMap(tree -> Mono.fromCallable(() -> lintCopy(tree, s.path())).subscribeOn(Schedulers.boundedElastic()));
    }

    private Optional<String> lintCopy(Path tree, String playbook) throws IOException {
        if (!Files.isRegularFile(tree.resolve(playbook), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(playbook + " is not a file at that commit");
        }
        Path work = Files.createTempDirectory("iaf-playbook-lint-");
        try {
            copy(tree, work);
            Optional<LintResult> r = lint.lint(work, playbook);
            if (r.isEmpty() || r.get().passed()) {
                return Optional.empty();
            }
            // the linter names files by the copy's absolute path; the path in the repository reads better
            String output = r.get().output() == null ? "" : r.get().output().replace(work.toString() + "/", "").strip();
            log.debug("the linter refused {} in {}", playbook, work);
            return Optional.of(output.isEmpty() ? "the linter refused it" : output);
        } finally {
            delete(work);
        }
    }

    /** Directories and regular files of {@code from} into {@code to}; links and anything else stay behind. */
    static void copy(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("could not delete {}: {}", dir, e.getMessage());
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
