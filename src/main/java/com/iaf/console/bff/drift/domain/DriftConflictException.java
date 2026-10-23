package com.iaf.console.bff.drift.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iaf.console.bff.common.errors.CodedConflictException;

/**
 * A 409 of the drift service, with its code kept: the drift service opens {@code error} with a
 * stable code ({@code finding.reverting: a revert job is already running}), and the web app tells
 * the refusals apart by it, so the BFF answers its usual coded 409 with that code as {@code error},
 * the sentence as {@code message} and the drift service's {@code detail} (a revert's job id...).
 */
public class DriftConflictException extends CodedConflictException {

    private static final long serialVersionUID = 1L;
    /** A code is dotted words ({@code finding.closed}, {@code revert.needsPerson}), then a colon and the sentence. */
    private static final Pattern CODED = Pattern.compile("^([a-z][A-Za-z0-9-]*(?:\\.[A-Za-z0-9-]+)+):\\s*(.*)$", Pattern.DOTALL);
    /** The code of a 409 whose text names none. */
    public static final String UNNAMED = "conflict";

    public DriftConflictException(String code, String message, Map<String, Object> detail) {
        super(code, message, detail);
    }

    /** The conflict an {@code error} text spells: its code and sentence when it opens with a code, else the whole text under {@value #UNNAMED}. */
    public static DriftConflictException of(String error, Map<String, Object> detail) {
        String text = error == null || error.isBlank() ? "the drift service refused the change" : error.strip();
        Matcher m = CODED.matcher(text);
        if (m.matches()) {
            String sentence = m.group(2).isBlank() ? m.group(1) : m.group(2).strip();
            return new DriftConflictException(m.group(1), sentence, detail);
        }
        return new DriftConflictException(UNNAMED, text, detail);
    }
}
