package com.iaf.console.bff.demo.idp.adapters.inbound;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.HtmlUtils;

import com.iaf.console.bff.demo.directory.domain.DemoPerson;
import com.iaf.console.bff.demo.idp.application.DemoIdp;

import reactor.core.publisher.Mono;

/** The demo provider's endpoints under {@code /demo-idp}: discovery, authorize (with a picker), token, JWKS, end-session. */
@RestController
@Profile("demo")
public class DemoIdpController {

    private final DemoIdp idp;

    public DemoIdpController(DemoIdp idp) {
        this.idp = idp;
    }

    @GetMapping(path = "/demo-idp/.well-known/openid-configuration", produces = "application/json")
    public Map<String, Object> discovery() {
        return idp.discovery();
    }

    @GetMapping(path = "/demo-idp/jwks", produces = "application/json")
    public Map<String, Object> jwks() {
        return idp.jwks().toJSONObject(true);
    }

    @GetMapping(path = "/demo-idp/authorize")
    public ResponseEntity<String> authorize(
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
            @RequestParam(name = "response_type", required = false) String responseType,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "nonce", required = false) String nonce,
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(name = "login_hint", required = false) String loginHint,
            @RequestParam(name = "user", required = false) String user,
            ServerWebExchange exchange) {
        DemoIdp.Authorization answer = idp.authorize(clientId, redirectUri, responseType, state, nonce, codeChallenge, codeChallengeMethod,
                user == null || user.isBlank() ? loginHint : user);
        if (answer.error() != null) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                    .body("<!doctype html><title>Demo sign-in</title><p>" + HtmlUtils.htmlEscape(answer.error()) + "</p>");
        }
        if (answer.redirectTo() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(answer.redirectTo())).header("Cache-Control", "no-store").build();
        }
        String query = exchange.getRequest().getURI().getRawQuery();
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Demo sign-in</title>");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<style>body{font-family:-apple-system,system-ui,sans-serif;background:#f5f5f7;color:#1d1d1f;margin:0;display:grid;place-items:center;min-height:100vh}");
        html.append("main{background:#fff;border-radius:18px;padding:32px 36px;box-shadow:0 10px 40px rgba(0,0,0,.08);width:min(420px,92vw)}");
        html.append("h1{font-size:22px;margin:0 0 4px}p{color:#6e6e73;margin:0 0 20px}ul{list-style:none;padding:0;margin:0}");
        html.append("li a{display:flex;justify-content:space-between;align-items:center;padding:12px 14px;border-radius:12px;text-decoration:none;color:inherit}");
        html.append("li a:hover{background:#f0f0f5}small{color:#6e6e73}</style></head><body><main>");
        html.append("<h1>Sign in to the console</h1><p>Demo identity provider. Pick who you are.</p><ul>");
        for (DemoPerson person : answer.picker()) {
            String href = "/demo-idp/authorize?" + (query == null ? "" : query + "&") + "user=" + URLEncoder.encode(person.subject(), StandardCharsets.UTF_8);
            html.append("<li><a href=\"").append(HtmlUtils.htmlEscape(href)).append("\"><span>").append(HtmlUtils.htmlEscape(person.name()))
                    .append("</span><small>").append(HtmlUtils.htmlEscape(person.email())).append("</small></a></li>");
        }
        html.append("</ul></main></body></html>");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).header("Cache-Control", "no-store").body(html.toString());
    }

    @PostMapping(path = "/demo-idp/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = "application/json")
    public Mono<ResponseEntity<Map<String, Object>>> token(ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            Map<String, Object> answer = idp.token(first(form, "grant_type"), first(form, "code"), first(form, "redirect_uri"), first(form, "client_id"),
                    first(form, "code_verifier"));
            HttpStatus status = answer.containsKey("error") ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
            return ResponseEntity.status(status).header("Cache-Control", "no-store").body(answer);
        });
    }

    @GetMapping(path = "/demo-idp/end-session")
    public ResponseEntity<Void> endSession(@RequestParam(name = "post_logout_redirect_uri", required = false) String returnTo) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(returnTo == null || returnTo.isBlank() ? "/" : returnTo)).build();
    }

    private static String first(MultiValueMap<String, String> form, String name) {
        return form.getFirst(name);
    }
}
