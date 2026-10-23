package com.iaf.console.bff.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.kit.errors.Errors;

/** The step-up refusal's shape (the code, the sentence, the sign-in path) and the timeout's 504. */
class BffExceptionHandlerTest {

    private final BffExceptionHandler handler = new BffExceptionHandler();

    @Test
    void aStepUpRefusalCarriesItsCodeTheSentenceAndTheSignInPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/console/v1/r/vm/x")
                .header(HttpHeaders.REFERER, "http://localhost:3000/console/vms?view=all"));
        ResponseEntity<BffExceptionHandler.StepUpBody> answer = handler.stepUp(new StepUpRequiredException("compute:delete"), exchange).block();
        assertEquals(403, answer.getStatusCode().value());
        BffExceptionHandler.StepUpBody body = answer.getBody();
        assertEquals("step-up.required", body.error());
        assertTrue(body.message().contains("compute:delete"), body.message());
        assertEquals(true, body.detail().get("stepUp"));
        assertEquals("compute:delete", body.detail().get("action"));
        assertEquals("/auth/login?stepUp=true&returnTo=%2Fconsole%2Fvms%3Fview%3Dall", body.detail().get("login"));
    }

    @Test
    void theSignInPathLeavesReturnToToAConsoleWithHashRoutes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/console/v1/r/vm/x")
                .header(HttpHeaders.REFERER, "http://localhost:3000/"));
        BffExceptionHandler.StepUpBody body = handler.stepUp(new StepUpRequiredException("compute:delete"), exchange).block().getBody();
        assertEquals("/auth/login?stepUp=true", body.detail().get("login"), "the console appends its own returnTo, its hash route");
        MockServerWebExchange none = MockServerWebExchange.from(MockServerHttpRequest.delete("/console/v1/r/vm/x"));
        assertEquals("/auth/login?stepUp=true", handler.stepUp(new StepUpRequiredException("compute:delete"), none).block().getBody().detail().get("login"));
    }

    @Test
    void onlyASafeConsolePathBecomesTheReturnPath() {
        assertEquals("/console/vms", BffExceptionHandler.returnPath("http://localhost:3000/console/vms"));
        assertEquals("/#/console/vms", BffExceptionHandler.returnPath("http://localhost:3000/#/console/vms"));
        assertNull(BffExceptionHandler.returnPath("http://localhost:3000"));
        assertNull(BffExceptionHandler.returnPath("http://evil.example//evil.example/x"));
        assertNull(BffExceptionHandler.returnPath("not a url at all"));
        assertNull(BffExceptionHandler.returnPath(null));
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeIs504() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/console/v1/search?q=x"));
        ResponseEntity<Errors.Body> answer = handler.timedOut(new TimeoutException("Did not observe any item within 5000ms"), exchange).block();
        assertEquals(504, answer.getStatusCode().value());
        assertEquals("upstream service timed out", answer.getBody().error());
    }
}
