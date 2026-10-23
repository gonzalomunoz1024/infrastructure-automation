package com.iaf.console.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One operation of the contract: its id, method and path, and whether it takes an Idempotency-Key. */
final class Operation {
    private static final Pattern PARAMETER = Pattern.compile("\\{[^}]+}");

    final String operationId;
    final String method;
    final String path;
    /** The BFF answers such a write once per key, so it may be sent again after a 429 or 503. */
    final boolean idempotencyKey;
    private final Pattern pattern;
    private final int literals;

    Operation(String operationId, String method, String path, boolean idempotencyKey) {
        this.operationId = operationId;
        this.method = method.toUpperCase(Locale.ROOT);
        this.path = path;
        this.idempotencyKey = idempotencyKey;
        // A path parameter goes out percent-encoded, its slashes and colons too, so it holds neither:
        // `{wfrn}:{verb}` never matches `{wfrn}:{verb}:preview`.
        StringBuilder regex = new StringBuilder("^");
        Matcher m = PARAMETER.matcher(path);
        int at = 0;
        int literal = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(at, m.start()))).append("[^/:]+");
            literal += m.start() - at;
            at = m.end();
        }
        regex.append(Pattern.quote(path.substring(at))).append('$');
        this.literals = literal + path.length() - at;
        this.pattern = Pattern.compile(regex.toString());
    }

    /**
     * The operation a request is for: of those with its method whose path matches, the most
     * specific one (the most literal characters), as a router picks; null when none matches.
     */
    static Operation find(String method, String rawPath) {
        Operation best = null;
        for (Operation op : ContractInfo.OPERATIONS) {
            if (op.method.equalsIgnoreCase(method) && op.pattern.matcher(rawPath).matches()) {
                if (best == null || op.literals > best.literals) best = op;
            }
        }
        return best;
    }

    static Operation byId(String operationId) {
        for (Operation op : ContractInfo.OPERATIONS) {
            if (op.operationId.equals(operationId)) return op;
        }
        throw new IllegalArgumentException("the contract has no operation " + operationId);
    }

    static List<Operation> all() {
        return new ArrayList<>(ContractInfo.OPERATIONS);
    }

    @Override
    public String toString() {
        return operationId + " (" + method + " " + path + ")";
    }
}
