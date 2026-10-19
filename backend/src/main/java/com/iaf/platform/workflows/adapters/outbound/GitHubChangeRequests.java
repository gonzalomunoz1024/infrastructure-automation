package com.iaf.platform.workflows.adapters.outbound;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import lombok.extern.slf4j.Slf4j;

/**
 * The {@code github} profile's change requests: pull requests on the content repository, kept in
 * the platform's own change request store as well (whichever store is active: in memory or the
 * database). {@link GitHubChangeRequestsConfig} wraps that store, so this works with any of them.
 *
 * <ul>
 * <li>A new open change request opens its pull request (branch, the file, one status per check)
 * before it is stored, and is stored with the pull request's address as {@code url}; when GitHub
 * cannot be reached the change request is not opened at all.</li>
 * <li>Saved again while open with checks that changed (the requires check judged again once a
 * provider change request it requires is done or closed): those checks are posted again as
 * commit statuses on the pull request's head commit.</li>
 * <li>Merged or closed on the platform: the pull request is merged or closed too, unless GitHub
 * already did (the webhook reports it), so a decision is never sent back where it came from. A
 * merge GitHub refuses (reviews or required checks missing) is a 409 and the change request stays
 * open.</li>
 * </ul>
 */
@Slf4j
public class GitHubChangeRequests implements ChangeRequestPort {

    private final ChangeRequestPort store;
    private final Supplier<GitHubPullRequests> github;

    public GitHubChangeRequests(ChangeRequestPort store, Supplier<GitHubPullRequests> github) {
        this.store = store;
        this.github = github;
    }

    @Override
    public List<ChangeRequest> all() {
        return store.all();
    }

    @Override
    public Optional<ChangeRequest> get(String id) {
        return store.get(id);
    }

    @Override
    public void save(ChangeRequest cr) {
        Optional<ChangeRequest> before = store.get(cr.id());
        String url = cr.url() != null ? cr.url() : before.map(ChangeRequest::url).orElse(null);
        ChangeRequest saved = url == null || url.equals(cr.url()) ? cr : cr.withUrl(url);
        boolean wasOpen = before.map(b -> b.status() == ChangeRequest.Status.OPEN).orElse(false);
        if (cr.status() == ChangeRequest.Status.OPEN && url == null) {
            saved = cr.withUrl(github.get().open(cr).url());
        } else if (cr.status() != ChangeRequest.Status.OPEN && wasOpen) {
            decide(cr, url);
        } else if (cr.status() == ChangeRequest.Status.OPEN && wasOpen) {
            restate(cr, before.get(), url);
        }
        store.save(saved);
    }

    /**
     * Posts the checks of an open change request that changed since it was last saved (the
     * requires check judged again) on its pull request. Best effort: the platform's own merge
     * judges the check again anyway, so a status GitHub does not take is logged, not fatal.
     */
    private void restate(ChangeRequest cr, ChangeRequest before, String url) {
        List<Check> changed = changed(before.checks(), cr.checks());
        int number = GitHubPullRequests.number(url);
        if (changed.isEmpty() || number < 0) {
            return;
        }
        try {
            github.get().statuses(number, changed);
        } catch (RuntimeException e) {
            log.warn("could not post the changed checks {} of change request {} on pull request #{}: {}", changed.stream().map(Check::id).toList(), cr.id(),
                    number, e.getMessage());
        }
    }

    /** The checks in {@code after} whose status or detail is not the one {@code before} has for their id. */
    static List<Check> changed(List<Check> before, List<Check> after) {
        List<Check> was = before == null ? List.of() : before;
        return (after == null ? List.<Check>of() : after).stream()
                .filter(c -> was.stream().noneMatch(b -> b.id().equals(c.id()) && Objects.equals(b.status(), c.status()) && Objects.equals(b.detail(), c.detail())))
                .toList();
    }

    /** Merges or closes the pull request, unless GitHub already did. */
    private void decide(ChangeRequest cr, String url) {
        int number = GitHubPullRequests.number(url);
        if (number < 0) {
            log.warn("change request {} has no pull request to {}", cr.id(), cr.status().json());
            return;
        }
        GitHubPullRequests.State state = github.get().state(number);
        if (cr.status() == ChangeRequest.Status.MERGED) {
            if (state.merged()) {
                return;
            }
            if (!state.open()) {
                throw new ConflictException("Pull request #" + number + " was closed on GitHub without merging; reopen it there or close the change request");
            }
            github.get().merge(number, cr.title());
        } else if (cr.status() == ChangeRequest.Status.CLOSED && state.open()) {
            github.get().close(number);
        }
    }
}
