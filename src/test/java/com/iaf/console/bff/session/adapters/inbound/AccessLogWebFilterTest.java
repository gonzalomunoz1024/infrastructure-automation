package com.iaf.console.bff.session.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The access log writes no device grant's user code. */
class AccessLogWebFilterTest {

    @Test
    void aUserCodeInAPathIsLeftOut() {
        assertEquals("/console/v1/device-grants/{userCode}", AccessLogWebFilter.loggable("/console/v1/device-grants/BCDF-GHJK"));
        assertEquals("/console/v1/device-grants/{userCode}:approve", AccessLogWebFilter.loggable("/console/v1/device-grants/bcdfghjk:approve"));
        assertEquals("/console/v1/device-grants/{userCode}:deny", AccessLogWebFilter.loggable("/console/v1/device-grants/BCDF%2DGHJK:deny"));
        assertEquals("/console/v1/r/vm", AccessLogWebFilter.loggable("/console/v1/r/vm"));
        assertEquals("/console/v1/api-sessions/aps_01jabcdefghjkmnpqrstvwxyz0", AccessLogWebFilter.loggable("/console/v1/api-sessions/aps_01jabcdefghjkmnpqrstvwxyz0"));
    }
}
