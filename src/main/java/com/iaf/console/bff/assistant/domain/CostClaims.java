package com.iaf.console.bff.assistant.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps an assistant from stating a cost the cost service did not answer: a sentence naming an
 * amount of money (a currency sign or code, or dollars, euros, pounds) is dropped unless the
 * amount is the resource's monthly cost as the cost service returned it.
 */
public final class CostClaims {

    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:[$€£¥]\\s?(\\d[\\d,]*(?:\\.\\d+)?))|(?:\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s?(?:usd|eur|gbp|jpy|chf|cad|aud|dollars?|euros?|pounds?)\\b)");
    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?])\\s+");
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private CostClaims() {
    }

    /** The text without the sentences that state an amount other than {@code allowed} (none when null); each dropped one is said in {@code notes}. */
    public static String only(String text, BigDecimal allowed, List<String> notes) {
        if (text == null || !AMOUNT.matcher(text).find()) {
            return text;
        }
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.split("\n\n", -1)) {
            List<String> lines = new ArrayList<>();
            for (String line : paragraph.split("\n", -1)) {
                List<String> kept = new ArrayList<>();
                for (String sentence : SENTENCES.split(line)) {
                    if (states(sentence, allowed)) {
                        notes.add("a sentence states a cost the cost service did not answer: " + sentence);
                    } else {
                        kept.add(sentence);
                    }
                }
                if (!kept.isEmpty()) {
                    lines.add(String.join(" ", kept));
                }
            }
            if (!lines.isEmpty()) {
                paragraphs.add(String.join("\n", lines));
            }
        }
        String joined = String.join("\n\n", paragraphs).strip();
        return joined.isEmpty() ? null : joined;
    }

    /** Whether a sentence names an amount of money other than {@code allowed}. */
    static boolean states(String sentence, BigDecimal allowed) {
        Matcher m = AMOUNT.matcher(sentence);
        while (m.find()) {
            String number = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
            BigDecimal amount;
            try {
                amount = new BigDecimal(number);
            } catch (NumberFormatException e) {
                return true;
            }
            if (allowed == null || amount.subtract(allowed).abs().compareTo(CENT) >= 0) {
                return true;
            }
        }
        return false;
    }
}
