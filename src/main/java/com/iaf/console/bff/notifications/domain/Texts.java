package com.iaf.console.bff.notifications.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.databind.JsonNode;

/** Small helpers the notification texts share: reading event data and writing numbers, durations and names for people. */
final class Texts {

    private Texts() {
    }

    /** A text field, or null when absent or blank. */
    static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    /** The last segment of a wfrn's id ({@code vm/web-01} is web-01), or null. */
    static String resourceName(String wfrn) {
        if (wfrn == null || wfrn.isBlank()) {
            return null;
        }
        int slash = wfrn.lastIndexOf('/');
        return slash < 0 ? wfrn : wfrn.substring(slash + 1);
    }

    /** The type segment of a wfrn ({@code wfrn:wf:compute:global:123456789012:vm/web-01} is vm), or null. */
    static String typeOf(String wfrn) {
        if (wfrn == null) {
            return null;
        }
        int lastColon = wfrn.lastIndexOf(':');
        int slash = wfrn.indexOf('/', lastColon + 1);
        return lastColon < 0 || slash < 0 ? null : wfrn.substring(lastColon + 1, slash);
    }

    /** 3 s, 2 min 5 s, 1 h 4 min. */
    static String duration(long millis) {
        long seconds = Math.max(0, Math.round(millis / 1000d));
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s");
        }
        return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
    }

    /** An amount of money with two decimals: 1234.5 is 1234.50. */
    static String money(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** A share as a whole percentage: 0.834 is 83. */
    static long percent(double share) {
        return Math.round(share * 100);
    }

    /** The first letter in capitals, for a sentence the console writes itself ("the admin role" starts one as "The admin role"). */
    static String capitalizedFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Text as a sentence: ending in a full stop unless it ends in one already (its case is left as written: vCenter stays vCenter). */
    static String sentence(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        String t = s.strip();
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' ? t : t + ".";
    }

    /** Text cut to a length people can read in a list, with an ellipsis when cut. */
    static String shortened(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }
}
