package com.iaf.console.bff.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;

/** Review finding 4: the IAM proxy forwards only plain segments, encoded again. */
class IamPathTest {

    @Test
    void plainSegmentsPassEncodedAgain() {
        assertEquals("users/user_mia", IamProxy.encodedPath("users/user_mia"));
        assertEquals("access-keys/ck_1:rotate", IamProxy.encodedPath("access-keys/ck_1:rotate"));
        assertEquals("quotas/resources.vm:request-increase", IamProxy.encodedPath("quotas/resources.vm:request-increase"));
        assertEquals("users/a%20b", IamProxy.encodedPath("users/a%20b"));
        assertEquals("users/a%3Fb", IamProxy.encodedPath("users/a%3fb"), "an encoded ? stays inside its segment");
        assertEquals("users/a%25252e", IamProxy.encodedPath("users/a%25252e"), "a doubly encoded dot is only text");
    }

    @Test
    void dotSegmentsEncodedSeparatorsAndEmptySegmentsAre400() {
        for (String rest : List.of("users/../../222222222222/users", "users/./x", "users/..", "..", "users//x", "users/", "",
                "users/%2e%2e/x", "users/%2E%2E/x", "users/.%2e", "users/a%2fb", "users/a%2Fb", "users/a\\b", "users/a%5cb", "users/a%5Cb")) {
            assertThrows(InvalidInputException.class, () -> IamProxy.encodedPath(rest), rest);
        }
    }
}
