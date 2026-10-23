package com.iaf.console.cost.chargeback.ports.outbound;

import com.iaf.console.cost.chargeback.domain.ChargebackMonth;

import reactor.core.publisher.Mono;

/** Where a closed month goes for finance: its CSV, with the sha256 in the month. Delivering twice must be harmless. */
public interface FinanceExportPort {

    Mono<Void> deliver(ChargebackMonth month, String csv);

    String kind();
}
