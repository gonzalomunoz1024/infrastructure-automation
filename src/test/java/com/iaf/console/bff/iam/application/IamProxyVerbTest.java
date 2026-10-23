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

    @Test
    void theActionAndResourceOfADeniedCallAreTheIdentityServicesNames() {
        assertEquals("iam:create", IamProxy.actionOf(HttpMethod.POST, "users"));
        assertEquals("iam:rotate", IamProxy.actionOf(HttpMethod.POST, "access-keys/ck_1:rotate"));
        assertEquals("quota:request-increase", IamProxy.actionOf(HttpMethod.POST, "quotas/resources.vm:request-increase"));
        assertEquals("quota:set", IamProxy.actionOf(HttpMethod.PUT, "quotas/access-keys"));
        assertEquals("quota:set", IamProxy.actionOf(HttpMethod.DELETE, "quota-requests/qreq_1"));
        assertEquals("quota:list", IamProxy.actionOf(HttpMethod.GET, "quotas"));
        assertEquals("access-request:decide", IamProxy.actionOf(HttpMethod.POST, "access-requests/areq_1:decide"));
        assertEquals("access-request:list", IamProxy.actionOf(HttpMethod.GET, "access-requests?status=pending"));
        assertEquals("wfrn:wf:iam::123456789012:user/*", IamProxy.resourceOf("123456789012", "users"));
        assertEquals("wfrn:wf:iam::123456789012:policy/managed", IamProxy.resourceOf("123456789012", "policies/managed"));
        assertEquals("wfrn:wf:iam::123456789012:access-key/ck_1", IamProxy.resourceOf("123456789012", "access-keys/ck_1:rotate"));
        assertEquals("wfrn:wf:iam::123456789012:quota/resources.vm", IamProxy.resourceOf("123456789012", "quotas/resources.vm:request-increase"));
        assertEquals("wfrn:wf:iam::123456789012:access-request/*", IamProxy.resourceOf("123456789012", "access-requests?status=pending"));
    }
}
