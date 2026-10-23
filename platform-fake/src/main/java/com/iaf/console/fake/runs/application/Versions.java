package com.iaf.console.fake.runs.application;

/** Whether a published version satisfies a pin: an exact version, a caret range ({@code ^1}, {@code ^1.2}) or no pin at all. */
final class Versions {

    private Versions() {
    }

    static boolean matches(String pin, String version) {
        if (pin == null || pin.isBlank() || pin.equals("*") || pin.equals("latest")) {
            return true;
        }
        String wanted = pin.trim();
        if (wanted.startsWith("^")) {
            int[] want = parts(wanted.substring(1));
            int[] have = parts(version);
            if (want[0] != have[0]) {
                return false;
            }
            if (have[1] != want[1]) {
                return have[1] > want[1];
            }
            return have[2] >= want[2];
        }
        if (wanted.startsWith("~")) {
            int[] want = parts(wanted.substring(1));
            int[] have = parts(version);
            return want[0] == have[0] && want[1] == have[1] && have[2] >= want[2];
        }
        return wanted.equals(version);
    }

    private static int[] parts(String version) {
        int[] out = new int[3];
        String[] pieces = version.trim().split("\\.");
        for (int i = 0; i < 3 && i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
