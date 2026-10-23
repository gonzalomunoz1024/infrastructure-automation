package com.iaf.console.sdk;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Where {@link RefreshingCredentials} keep their tokens. A store that several processes share
 * overrides {@link #exclusive(Supplier)} so that only one of them refreshes at a time: the refresh
 * token rotates, and the BFF ends the whole API session when a rotated one comes back.
 */
public interface TokenStore {

    /** The tokens, or null when there are none (signed out). */
    TokenSet load();

    /** Keeps new tokens; called after each refresh, before the new access token is used. */
    void save(TokenSet tokens);

    /** Runs a refresh (a load, a refresh, a save) alone among everyone sharing the store. */
    default <T> T exclusive(Supplier<T> task) {
        return task.get();
    }

    /** Tokens kept in memory; {@code onRefresh} is told each new set (keep the new refresh token). */
    static TokenStore inMemory(TokenSet initial, Consumer<TokenSet> onRefresh) {
        return new TokenStore() {
            private volatile TokenSet tokens = initial;

            @Override
            public TokenSet load() {
                return tokens;
            }

            @Override
            public void save(TokenSet next) {
                tokens = next;
                if (onRefresh != null) onRefresh.accept(next);
            }
        };
    }
}
