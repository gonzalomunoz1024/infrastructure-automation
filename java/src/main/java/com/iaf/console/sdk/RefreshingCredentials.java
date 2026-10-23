package com.iaf.console.sdk;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Credentials that renew themselves: shortly before the access token expires, and once after a
 * 401 {@code invalid_token}. Refreshes are single-flight: calls that need one at the same time
 * wait for the first, and use what it got.
 *
 * <pre>{@code
 * TokenSet tokens = auth.deviceLogin(code -> System.out.println(code.verificationUriComplete()));
 * TokenProvider credentials = RefreshingCredentials.builder()
 *         .auth(auth)
 *         .tokens(tokens)
 *         .onRefresh(next -> save(next)) // the refresh token rotates: keep the new one
 *         .build();
 * }</pre>
 */
public final class RefreshingCredentials implements TokenProvider {
    private final ConsoleAuth auth;
    private final TokenStore store;
    private final Duration refreshBefore;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private RefreshingCredentials(Builder b) {
        this.auth = b.auth != null ? b.auth : ConsoleAuth.builder(Objects.requireNonNull(b.endpoint, "endpoint or auth")).clock(b.clock).build();
        this.store = b.store != null ? b.store : TokenStore.inMemory(b.tokens, b.onRefresh);
        this.refreshBefore = b.refreshBefore;
        this.clock = b.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String token() {
        TokenSet current = store.load();
        if (current == null) return null;
        if (!expiring(current) || current.refreshToken() == null) return current.accessToken();
        try {
            return renew(null);
        } catch (RuntimeException e) {
            // the token still works for a moment: use it, and let a 401 refresh again
            if (current.expiresAt() != null && clock.instant().isBefore(current.expiresAt())) return current.accessToken();
            throw e;
        }
    }

    @Override
    public String refresh(String rejected) {
        return renew(Objects.requireNonNull(rejected, "rejected"));
    }

    /** The tokens in use now (null when signed out). */
    public TokenSet current() {
        return store.load();
    }

    private boolean expiring(TokenSet t) {
        return t.expiresAt() != null && !clock.instant().isBefore(t.expiresAt().minus(refreshBefore));
    }

    private String renew(String rejected) {
        lock.lock();
        try {
            return store.exclusive(() -> {
                TokenSet current = store.load();
                if (current == null) return null;
                // another call, or another process sharing the store, has refreshed already
                boolean renewed = rejected != null ? !current.accessToken().equals(rejected) : !expiring(current);
                if (renewed) return current.accessToken();
                if (current.refreshToken() == null) return null;
                TokenSet next = auth.refresh(current.refreshToken(), current.clientId());
                store.save(next);
                return next.accessToken();
            });
        } finally {
            lock.unlock();
        }
    }

    public static final class Builder {
        private String endpoint;
        private ConsoleAuth auth;
        private TokenSet tokens;
        private Consumer<TokenSet> onRefresh;
        private TokenStore store;
        private Duration refreshBefore = Duration.ofSeconds(60);
        private Clock clock = Clock.systemUTC();

        private Builder() {}

        /** The BFF's address; or give {@link #auth(ConsoleAuth)}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** The sign-in to refresh with (its client id names tokens that name none). */
        public Builder auth(ConsoleAuth auth) {
            this.auth = auth;
            return this;
        }

        /** The tokens to start from, kept in memory; or give {@link #store(TokenStore)}. */
        public Builder tokens(TokenSet tokens) {
            this.tokens = tokens;
            return this;
        }

        /** Told the new tokens after each refresh, before they are used: keep the new refresh token. */
        public Builder onRefresh(Consumer<TokenSet> onRefresh) {
            this.onRefresh = onRefresh;
            return this;
        }

        public Builder store(TokenStore store) {
            this.store = store;
            return this;
        }

        /** Refresh this long before the access token expires (60 seconds). */
        public Builder refreshBefore(Duration refreshBefore) {
            this.refreshBefore = Objects.requireNonNull(refreshBefore, "refreshBefore");
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public RefreshingCredentials build() {
            return new RefreshingCredentials(this);
        }
    }
}
