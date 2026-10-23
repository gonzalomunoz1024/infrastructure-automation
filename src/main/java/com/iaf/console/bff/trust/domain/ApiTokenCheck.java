package com.iaf.console.bff.trust.domain;

import java.util.Objects;

/**
 * A bearer token checked as the console API's access token: the token when it holds, else why it
 * does not. The reason is for the log; the caller only ever learns that the token is invalid.
 */
public record ApiTokenCheck(ApiAccessToken token, String refusal) {

    public static ApiTokenCheck valid(ApiAccessToken token) {
        return new ApiTokenCheck(Objects.requireNonNull(token, "token"), null);
    }

    public static ApiTokenCheck refused(String why) {
        return new ApiTokenCheck(null, Objects.requireNonNull(why, "why"));
    }

    public boolean isValid() {
        return token != null;
    }
}
