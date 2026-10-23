package com.iaf.console.cost.chargeback.adapters.web;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.cost.chargeback.application.ChargebackService;
import com.iaf.console.cost.chargeback.domain.ChargebackReport;
import com.iaf.console.cost.chargeback.domain.Chargebacks;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** A month's chargeback, as JSON and as the CSV finance receives. */
@RestController
public final class ChargebackController {

    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");
    private static final Pattern TAG = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ChargebackService chargebacks;

    public ChargebackController(ChargebackService chargebacks) {
        this.chargebacks = chargebacks;
    }

    @GetMapping("/v1/chargeback")
    public Mono<ChargebackReport> report(ServerWebExchange exchange, @RequestParam(name = "accountId", required = false) List<String> accountIds,
            @RequestParam(required = false) String month, @RequestParam(required = false) String tag) {
        return render(exchange, accountIds, month, tag).map(ChargebackService.Rendered::report);
    }

    @GetMapping(value = "/v1/chargeback.csv", produces = "text/csv")
    public Mono<ResponseEntity<String>> csv(ServerWebExchange exchange, @RequestParam(name = "accountId", required = false) List<String> accountIds,
            @RequestParam(required = false) String month, @RequestParam(required = false) String tag) {
        return render(exchange, accountIds, month, tag).map(r -> ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"chargeback-" + r.report().month() + ".csv\"")
                .header("X-Content-Sha256", Chargebacks.sha256(r.csv())).body(r.csv()));
    }

    private Mono<ChargebackService.Rendered> render(ServerWebExchange exchange, List<String> accountIds, String month, String tag) {
        if (month == null || !MONTH.matcher(month).matches()) {
            throw new InvalidInputException("month is YYYY-MM", List.of(Problem.error("pattern", "month", "a month, e.g. 2026-10")));
        }
        if (tag != null && !TAG.matcher(tag).matches()) {
            throw new InvalidInputException("tag is a tag key", List.of(Problem.error("pattern", "tag", "a tag key of [a-z0-9-]{1,32}")));
        }
        List<AccountScope> scopes = Principals.of(exchange).scopes(accountIds, null);
        return chargebacks.report(scopes, YearMonth.parse(month), tag);
    }
}
