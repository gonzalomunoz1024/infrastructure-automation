package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

/** Change requests as pull requests: what reaches GitHub, and what the platform keeps when it does not. */
class GitHubChangeRequestsTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final String URL = "https://github.com/acme/content/pull/42";

    final InMemoryChangeRequests store = new InMemoryChangeRequests();
    final GitHubPullRequests github = mock(GitHubPullRequests.class);
    final GitHubChangeRequests changeRequests = new GitHubChangeRequests(store, () -> github);

    static ChangeRequest open() {
        return new ChangeRequest("cr_9", ChangeRequest.Kind.WORKFLOW, "vm-restart", "1.3.0", "change/vm-restart-1.3.0", "Update Restart a VM 1.3.0", "ana",
                ChangeRequest.Status.OPEN, List.of(), "canary", "kind: Workflow", "", NOW, null, null, null);
    }

    void opened() {
        when(github.open(any())).thenReturn(new GitHubPullRequests.PullRequest(42, URL, "c0ffee1"));
        changeRequests.save(open());
    }

    @Test
    void anOpenChangeRequestOpensItsPullRequestAndKeepsItsAddress() {
        opened();

        assertThat(changeRequests.get("cr_9").orElseThrow().url()).isEqualTo(URL);
        assertThat(store.get("cr_9").orElseThrow().url()).isEqualTo(URL);
        assertThat(changeRequests.all()).hasSize(1);
    }

    @Test
    void aChangeRequestGitHubCannotOpenIsNotKept() {
        when(github.open(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not open the pull request"));

        assertThatThrownBy(() -> changeRequests.save(open())).isInstanceOf(ResponseStatusException.class);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void savingAnOpenChangeRequestAgainKeepsItsPullRequest() {
        opened();
        changeRequests.save(open());

        verify(github).open(any());
        assertThat(store.get("cr_9").orElseThrow().url()).isEqualTo(URL);
    }

    @Test
    void mergingOnThePlatformMergesThePullRequest() {
        opened();
        when(github.state(42)).thenReturn(new GitHubPullRequests.State(true, false));

        changeRequests.save(open().withStatus(ChangeRequest.Status.MERGED, NOW, "maya"));

        verify(github).merge(42, "Update Restart a VM 1.3.0");
        ChangeRequest merged = store.get("cr_9").orElseThrow();
        assertThat(merged.status()).isEqualTo(ChangeRequest.Status.MERGED);
        assertThat(merged.url()).isEqualTo(URL);
    }

    @Test
    void aDecisionGitHubAlreadyTookIsNotSentBack() {
        opened();
        when(github.state(42)).thenReturn(new GitHubPullRequests.State(false, true));
        changeRequests.save(open().withStatus(ChangeRequest.Status.MERGED, NOW, "github:octocat"));
        verify(github, never()).merge(anyInt(), any());

        ChangeRequest second = new ChangeRequest("cr_10", ChangeRequest.Kind.WORKFLOW, "vm-restart", "1.4.0", "change/vm-restart-1.4.0", "t", "ana",
                ChangeRequest.Status.OPEN, List.of(), "canary", "kind: Workflow", "", NOW, null, null, URL.replace("42", "43"));
        store.save(second);
        when(github.state(43)).thenReturn(new GitHubPullRequests.State(false, false));
        changeRequests.save(second.withStatus(ChangeRequest.Status.CLOSED, NOW, "github:octocat"));
        verify(github, never()).close(anyInt());
        assertThat(store.get("cr_10").orElseThrow().status()).isEqualTo(ChangeRequest.Status.CLOSED);
    }

    @Test
    void closingOnThePlatformClosesTheOpenPullRequest() {
        opened();
        when(github.state(42)).thenReturn(new GitHubPullRequests.State(true, false));

        changeRequests.save(open().withStatus(ChangeRequest.Status.CLOSED, NOW, "ana"));

        verify(github).close(42);
        assertThat(store.get("cr_9").orElseThrow().status()).isEqualTo(ChangeRequest.Status.CLOSED);
    }

    @Test
    void aMergeGitHubRefusesLeavesTheChangeRequestOpen() {
        opened();
        when(github.state(42)).thenReturn(new GitHubPullRequests.State(true, false));
        org.mockito.Mockito.doThrow(new ConflictException("GitHub did not merge pull request #42: Review required")).when(github).merge(42, "Update Restart a VM 1.3.0");

        assertThatThrownBy(() -> changeRequests.save(open().withStatus(ChangeRequest.Status.MERGED, NOW, "maya")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Review required");
        assertThat(store.get("cr_9").orElseThrow().status()).isEqualTo(ChangeRequest.Status.OPEN);
    }

    @Test
    void aPullRequestClosedOnGitHubCannotBeMergedFromThePlatform() {
        opened();
        when(github.state(42)).thenReturn(new GitHubPullRequests.State(false, false));

        assertThatThrownBy(() -> changeRequests.save(open().withStatus(ChangeRequest.Status.MERGED, NOW, "maya")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("closed on GitHub");
        assertThat(store.get("cr_9").orElseThrow().status()).isEqualTo(ChangeRequest.Status.OPEN);
    }

    @Test
    void aChangeRequestOpenedBeforeGitHubWasOnIsDecidedOnThePlatformOnly() {
        store.save(open().withUrl(null));
        changeRequests.save(open().withStatus(ChangeRequest.Status.CLOSED, NOW, "ana"));
        verify(github, never()).state(anyInt());
        assertThat(store.get("cr_9").orElseThrow().status()).isEqualTo(ChangeRequest.Status.CLOSED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theProfileWrapsWhicheverStoreKeepsTheChangeRequests() throws Exception {
        ObjectProvider<GitHubPullRequests> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(github);
        BeanPostProcessor wrap = GitHubChangeRequestsConfig.gitHubChangeRequests(provider);

        Object wrapped = wrap.postProcessAfterInitialization(new InMemoryChangeRequests(), "inMemoryChangeRequests");
        assertThat(wrapped).isInstanceOf(GitHubChangeRequests.class).isInstanceOf(ChangeRequestPort.class);
        assertThat(wrap.postProcessAfterInitialization(wrapped, "again")).as("never twice").isSameAs(wrapped);
        Object other = new Object();
        assertThat(wrap.postProcessAfterInitialization(other, "other")).isSameAs(other);
        verify(provider, never()).getObject();
    }
}
