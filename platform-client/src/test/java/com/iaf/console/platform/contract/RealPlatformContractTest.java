package com.iaf.console.platform.contract;

import java.text.ParseException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.platform.client.PlatformClient;
import com.nimbusds.jose.jwk.ECKey;

/**
 * The contract test against a real platform, switched on by CONSOLE_TEST_PLATFORM_URL; the other variables name the
 * identities it runs as.
 */
@EnabledIfEnvironmentVariable(named = "CONSOLE_TEST_PLATFORM_URL", matches = ".+")
class RealPlatformContractTest extends PlatformContractTest {

    @Override
    protected Platform connect() {
        String base = required("CONSOLE_TEST_PLATFORM_URL");
        String adminToken = System.getenv("CONSOLE_TEST_PLATFORM_ADMIN_TOKEN");
        PlatformClient.Builder adminBuilder = PlatformClient.builder().baseUrl(base);
        PlatformClient admin = (adminToken != null && !adminToken.isBlank() ? adminBuilder.bearerToken(adminToken)
                : adminBuilder.devUser(orDefault("CONSOLE_TEST_PLATFORM_USER", "gonzalo"))).build();
        PlatformClient console = PlatformClient.builder().baseUrl(base).bearerToken(required("CONSOLE_TEST_PLATFORM_CONSOLE_TOKEN")).build();
        String accountId = required("CONSOLE_TEST_PLATFORM_ACCOUNT");

        String otherToken = System.getenv("CONSOLE_TEST_PLATFORM_OTHER_TOKEN");
        String otherAccount = System.getenv("CONSOLE_TEST_PLATFORM_OTHER_ACCOUNT");
        PlatformClient other = otherToken == null || otherToken.isBlank() || otherAccount == null || otherAccount.isBlank() ? null
                : PlatformClient.builder().baseUrl(base).bearerToken(otherToken).build();

        String internalToken = System.getenv("CONSOLE_TEST_PLATFORM_INTERNAL_TOKEN");
        PlatformClient internal = internalToken == null || internalToken.isBlank() ? null
                : PlatformClient.builder().baseUrl(base).bearerToken(internalToken).build();

        OnBehalfOfAssertions.Signer signer = null;
        OnBehalfOfAssertions.User user = null;
        String key = System.getenv("CONSOLE_TEST_PLATFORM_ASSERTION_KEY");
        if (key != null && !key.isBlank()) {
            signer = new OnBehalfOfAssertions.Signer(parse(key), orDefault("CONSOLE_TEST_PLATFORM_ASSERTION_ISSUER", "console-bff"),
                    orDefault("CONSOLE_TEST_PLATFORM_ASSERTION_AUDIENCE", "platform"));
            String subject = orDefault("CONSOLE_TEST_PLATFORM_DELEGATE", "dana");
            List<String> groups = Arrays.stream(orDefault("CONSOLE_TEST_PLATFORM_DELEGATE_GROUPS", "team-compute,developer").split(","))
                    .map(String::trim).filter(g -> !g.isEmpty()).toList();
            user = new OnBehalfOfAssertions.User(subject, subject, subject + "@example.com", groups);
        }
        Duration timeout = Duration.ofSeconds(Long.parseLong(orDefault("CONSOLE_TEST_PLATFORM_JOB_TIMEOUT_SECONDS", "300")));
        return new Platform(base, admin, console, accountId, other, otherAccount, internal, signer, user, timeout);
    }

    /** The private EC key as a JWK document, the one whose public half the platform trusts. */
    private static ECKey parse(String jwk) {
        try {
            return ECKey.parse(jwk);
        } catch (ParseException e) {
            throw new IllegalArgumentException("CONSOLE_TEST_PLATFORM_ASSERTION_KEY is not an EC JWK", e);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when CONSOLE_TEST_PLATFORM_URL is set");
        }
        return value.trim();
    }

    private static String orDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
