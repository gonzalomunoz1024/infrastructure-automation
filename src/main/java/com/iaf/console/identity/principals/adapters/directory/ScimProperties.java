package com.iaf.console.identity.principals.adapters.directory;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code scim} profile's directory, under {@code console.identity.directory.scim}: the SCIM 2.0
 * base URL (the one {@code /Groups} and {@code /Users} hang off), the bearer token, the page size
 * of user lists and the timeout of each call. The token is a secret: it stays in memory, and this
 * record's {@code toString} leaves it out.
 */
@ConfigurationProperties(prefix = "console.identity.directory.scim")
public record ScimProperties(String baseUrl, String token, Integer pageSize, Duration timeout) {

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 1000;

    public ScimProperties {
        pageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }

    @Override
    public String toString() {
        return "ScimProperties[baseUrl=" + baseUrl + ", token=" + (token == null || token.isBlank() ? "(none)" : "(set)") + ", pageSize=" + pageSize
                + ", timeout=" + timeout + "]";
    }
}
