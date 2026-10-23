package com.iaf.console.bff.assistant.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Why a turn ends {@code failed}: one of the contract's three codes and a sentence the person may
 * read. An {@code AssistantPort} errors with one of these; the turn shows its code and message.
 */
public class AssistantException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The assistant could not be reached, or answered an error. */
    public static final String UNAVAILABLE = "assistant.unavailable";
    /** The assistant did not answer within the turn's time. */
    public static final String TIMEOUT = "assistant.timeout";
    /** The assistant would not answer, or answered nothing the console can read or show. */
    public static final String REFUSED = "assistant.refused";

    private final String code;

    public AssistantException(String code, String message) {
        this(code, message, null);
    }

    public AssistantException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public static AssistantException unavailable(String message) {
        return new AssistantException(UNAVAILABLE, message);
    }

    public static AssistantException timeout(String message) {
        return new AssistantException(TIMEOUT, message);
    }

    public static AssistantException refused(String message) {
        return new AssistantException(REFUSED, message);
    }

    /** The turn's time ran out: "the assistant did not answer within 5 minutes". */
    public static AssistantException notWithin(Duration within) {
        return timeout("the assistant did not answer within " + spoken(within));
    }

    /** A duration as a sentence says it: "5 minutes", "1 minute", "90 seconds", "300 milliseconds". */
    static String spoken(Duration d) {
        if (d.toMillis() < 1000) {
            return d.toMillis() + " milliseconds";
        }
        if (d.toSeconds() < 60 || d.toSecondsPart() != 0) {
            return d.toSeconds() + (d.toSeconds() == 1 ? " second" : " seconds");
        }
        return d.toMinutes() + (d.toMinutes() == 1 ? " minute" : " minutes");
    }

    public String code() {
        return code;
    }
}
