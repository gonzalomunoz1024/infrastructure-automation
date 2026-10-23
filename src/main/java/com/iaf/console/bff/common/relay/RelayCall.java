package com.iaf.console.bff.common.relay;

import java.util.List;
import java.util.Map;

/**
 * One call to a console service: the method, the path (its segments already encoded, as
 * {@link RelayClient#segment(String)} does), the query
 * parameters in order (a name may repeat, as {@code accountId} does), the headers to send and the
 * body, if any.
 */
public record RelayCall(String method, String path, List<Map.Entry<String, String>> query, Map<String, String> headers, byte[] body) {

    public RelayCall {
        query = query == null ? List.of() : List.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static RelayCall get(String path, List<Map.Entry<String, String>> query) {
        return new RelayCall("GET", path, query, Map.of(), null);
    }

    public static RelayCall send(String method, String path, List<Map.Entry<String, String>> query, Map<String, String> headers, byte[] body) {
        return new RelayCall(method, path, query, headers, body);
    }
}
