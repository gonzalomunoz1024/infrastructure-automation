package com.iaf.console.cost.chargeback.adapters.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;
import com.iaf.console.cost.chargeback.ports.outbound.FinanceExportPort;

import reactor.core.publisher.Mono;

/** Finance's export where there is no finance system to call (a laptop, the demo): the month is logged with its checksum. */
public final class LogFinanceExport implements FinanceExportPort {

    private static final Logger log = LoggerFactory.getLogger(LogFinanceExport.class);

    @Override
    public Mono<Void> deliver(ChargebackMonth month, String csv) {
        return Mono.fromRunnable(() -> log.info("chargeback {} of account {} ({} bytes, sha256 {}) would go to finance", month.month(),
                month.accountId(), csv.length(), month.sha256()));
    }

    @Override
    public String kind() {
        return "log";
    }
}
