package com.iaf.console.bff.trust.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A console service asking for a token of its own (RFC 6749 §4.4, client credentials, with a JWT
 * client assertion as RFC 7523 §2.2 has it): the grant, the assertion and its type, the client id
 * when the caller names it, and the audiences asked for: exactly one is granted (hub decision 35), so
 * none or several are refused once the client is known.
 */
public record ServiceTokenRequest(String grantType, String clientAssertionType, String clientAssertion, String clientId, List<String> audiences) {

    public static final String CLIENT_CREDENTIALS = "client_credentials";
    public static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    public ServiceTokenRequest {
        // each audience field once per service, or several in one field separated by spaces
        LinkedHashSet<String> asked = new LinkedHashSet<>();
        if (audiences != null) {
            for (String value : audiences) {
                if (value == null) {
                    continue;
                }
                for (String one : value.trim().split("\\s+")) {
                    if (!one.isBlank()) {
                        asked.add(one);
                    }
                }
            }
        }
        audiences = List.copyOf(new ArrayList<>(asked));
    }
}
