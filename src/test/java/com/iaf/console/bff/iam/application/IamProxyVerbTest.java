package com.iaf.console.bff.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class IamProxyVerbTest {

    @Test
    void theVerbOfADeniedCallNamesWhatWasTried() {
        assertEquals("list", IamProxy.verbOf(HttpMethod.GET, "users"));
        assertEquals("get", IamProxy.verbOf(HttpMethod.GET, "users/user_mia"));
        assertEquals("create", IamProxy.verbOf(HttpMethod.POST, "users"));
        assertEquals("update", IamProxy.verbOf(HttpMethod.PATCH, "users/user_mia"));
        assertEquals("update", IamProxy.verbOf(HttpMethod.PUT, "quotas/vm.count"));
        assertEquals("delete", IamProxy.verbOf(HttpMethod.DELETE, "groups/g1"));
        assertEquals("rotate", IamProxy.verbOf(HttpMethod.POST, "access-keys/ck_1:rotate"));
        assertEquals("decide", IamProxy.verbOf(HttpMethod.POST, "access-requests/areq_1:decide"));
        assertEquals("update", IamProxy.verbOf(HttpMethod.POST, "quotas/vm.count:request-increase"));
    }
}
