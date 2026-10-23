package com.iaf.console.cost.boot;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.cost.budgets.adapters.memory.InMemoryBudgetPeriods;
import com.iaf.console.cost.budgets.adapters.memory.InMemoryBudgets;
import com.iaf.console.cost.budgets.adapters.mongo.MongoBudgetPeriods;
import com.iaf.console.cost.budgets.adapters.mongo.MongoBudgets;
import com.iaf.console.cost.budgets.application.BudgetChecks;
import com.iaf.console.cost.budgets.application.BudgetEvaluator;
import com.iaf.console.cost.budgets.application.BudgetEvents;
import com.iaf.console.cost.budgets.application.BudgetsService;
import com.iaf.console.cost.budgets.ports.outbound.BudgetPeriodStorePort;
import com.iaf.console.cost.budgets.ports.outbound.BudgetStorePort;
import com.iaf.console.cost.chargeback.adapters.memory.InMemoryChargebacks;
import com.iaf.console.cost.chargeback.adapters.mongo.MongoChargebacks;
import com.iaf.console.cost.chargeback.application.ChargebackService;
import com.iaf.console.cost.chargeback.application.MonthCloser;
import com.iaf.console.cost.chargeback.ports.outbound.ChargebackStorePort;
import com.iaf.console.cost.chargeback.ports.outbound.FinanceExportPort;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryCheckpoints;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryDays;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryDeltas;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryLedgers;
import com.iaf.console.cost.ledger.adapters.memory.InMemoryRates;
import com.iaf.console.cost.ledger.adapters.mongo.MongoCheckpoints;
import com.iaf.console.cost.ledger.adapters.mongo.MongoDays;
import com.iaf.console.cost.ledger.adapters.mongo.MongoDeltas;
import com.iaf.console.cost.ledger.adapters.mongo.MongoLedgers;
import com.iaf.console.cost.ledger.adapters.mongo.MongoRates;
import com.iaf.console.cost.ledger.application.Accounts;
import com.iaf.console.cost.ledger.application.CostQueries;
import com.iaf.console.cost.ledger.application.DayCloser;
import com.iaf.console.cost.ledger.application.EventHandler;
import com.iaf.console.cost.ledger.application.EventSource;
import com.iaf.console.cost.ledger.application.Housekeeping;
import com.iaf.console.cost.ledger.application.LedgerService;
import com.iaf.console.cost.ledger.application.LedgerStatuses;
import com.iaf.console.cost.ledger.application.OpenDays;
import com.iaf.console.cost.ledger.application.ResourceCosts;
import com.iaf.console.cost.ledger.application.SyncService;
import com.iaf.console.cost.ledger.ports.outbound.AccountDirectoryPort;
import com.iaf.console.cost.ledger.ports.outbound.CheckpointPort;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.cost.ledger.ports.outbound.DeltaStorePort;
import com.iaf.console.cost.ledger.ports.outbound.LedgerStatePort;
import com.iaf.console.cost.ledger.ports.outbound.RateStorePort;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.pricing.application.Pricing;
import com.iaf.console.cost.pricing.ports.outbound.PricingSourcePort;
import com.iaf.console.kit.idempotency.IdempotentConsumer;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.outbox.OutboxPort;

/**
 * The service's slices: the stores by profile ({@code mongo}, else in memory), the pricing, the
 * ledger, the budgets and the chargeback, and the background jobs that start with the context.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CostProperties.class)
public class CostConfiguration {

    @Bean
    public Clock costClock() {
        return Clock.systemUTC();
    }

    // the stores

    @Configuration(proxyBeanMethods = false)
    @Profile("mongo")
    static class MongoStores {

        @Bean
        public MongoRates mongoRates(Mongo mongo) {
            return new MongoRates(mongo);
        }

        @Bean
        public MongoDeltas mongoDeltas(Mongo mongo) {
            return new MongoDeltas(mongo);
        }

        @Bean
        public MongoDays mongoDays(Mongo mongo) {
            return new MongoDays(mongo);
        }

        @Bean
        public MongoLedgers mongoLedgers(Mongo mongo) {
            return new MongoLedgers(mongo);
        }

        @Bean
        public MongoCheckpoints mongoCheckpoints(Mongo mongo) {
            return new MongoCheckpoints(mongo);
        }

        @Bean
        public MongoBudgets mongoBudgets(Mongo mongo) {
            return new MongoBudgets(mongo);
        }

        @Bean
        public MongoBudgetPeriods mongoBudgetPeriods(Mongo mongo) {
            return new MongoBudgetPeriods(mongo);
        }

        @Bean
        public MongoChargebacks mongoChargebacks(Mongo mongo) {
            return new MongoChargebacks(mongo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!mongo")
    static class MemoryStores {

        @Bean
        public RateStorePort inMemoryRates() {
            return new InMemoryRates();
        }

        @Bean
        public DeltaStorePort inMemoryDeltas() {
            return new InMemoryDeltas();
        }

        @Bean
        public DayStorePort inMemoryDays() {
            return new InMemoryDays();
        }

        @Bean
        public LedgerStatePort inMemoryLedgers() {
            return new InMemoryLedgers();
        }

        @Bean
        public CheckpointPort inMemoryCheckpoints() {
            return new InMemoryCheckpoints();
        }

        @Bean
        public BudgetStorePort inMemoryBudgets() {
            return new InMemoryBudgets();
        }

        @Bean
        public BudgetPeriodStorePort inMemoryBudgetPeriods() {
            return new InMemoryBudgetPeriods();
        }

        @Bean
        public ChargebackStorePort inMemoryChargebacks() {
            return new InMemoryChargebacks();
        }
    }

    // pricing and the ledger

    @Bean
    public Pricing pricing(PricingSourcePort source, CostProperties properties) {
        return new Pricing(source, properties.pricing().refresh());
    }

    @Bean
    public LedgerService ledgerService(RateStorePort rates, DeltaStorePort deltas, LedgerStatePort ledgers, Pricing pricing, Clock clock,
            CostProperties properties) {
        CostProperties.LedgerSettings l = properties.ledger();
        LedgerService.ChargeStart start = l.demoSpreadDays() > 0 ? LedgerService.ChargeStart.spread(l.demoSpreadDays())
                : LedgerService.ChargeStart.created(l.backfillDays());
        return new LedgerService(rates, deltas, ledgers, pricing, clock, start);
    }

    @Bean
    public Accounts accounts(AccountDirectoryPort directory, Clock clock, CostProperties properties) {
        return new Accounts(directory, clock, properties.identity().cacheTtl());
    }

    @Bean
    public OpenDays openDays(DayStorePort days, DeltaStorePort deltas, Clock clock) {
        return new OpenDays(days, deltas, clock);
    }

    @Bean
    public DayCloser dayCloser(LedgerStatePort ledgers, DayStorePort days, DeltaStorePort deltas, Clock clock, CostProperties properties) {
        return new DayCloser(ledgers, days, deltas, clock, properties.ledger().closeGrace(), properties.ledger().closeInterval());
    }

    @Bean
    public CostQueries costQueries(LedgerStatePort ledgers, DayStorePort days, OpenDays open, Accounts accounts, Pricing pricing, Clock clock) {
        return new CostQueries(ledgers, days, open, accounts, pricing, clock);
    }

    @Bean
    public ResourceCosts resourceCosts(RateStorePort rates, StateServicePort state, Clock clock) {
        return new ResourceCosts(rates, state, clock);
    }

    @Bean
    public EventHandler eventHandler(LedgerService ledger, StateServicePort state, Accounts accounts, IdempotentConsumer once) {
        return new EventHandler(ledger, state, accounts, once);
    }

    @Bean
    public SyncService syncService(StateServicePort state, AccountDirectoryPort accounts, LedgerService ledger, Pricing pricing, Clock clock) {
        return new SyncService(state, accounts, ledger, pricing, clock);
    }

    @Bean
    public Housekeeping housekeeping(LedgerService ledger, CostProperties properties) {
        return new Housekeeping(ledger, properties.ledger().repairInterval());
    }

    @Bean
    public LedgerStatuses ledgerStatuses(ObjectProvider<EventSource> sources, RateStorePort rates, LedgerStatePort ledgers, DayCloser closer,
            Pricing pricing, Clock clock) {
        List<EventSource> list = sources.orderedStream().toList();
        return new LedgerStatuses(list, rates, ledgers, closer, pricing, clock);
    }

    // budgets

    @Bean
    public BudgetEvents budgetEvents(OutboxPort outbox) {
        return new BudgetEvents(outbox);
    }

    @Bean
    public BudgetsService budgetsService(BudgetStorePort budgets, BudgetPeriodStorePort periods, CostQueries costs, Pricing pricing, BudgetEvents events,
            Clock clock) {
        return new BudgetsService(budgets, periods, costs, pricing, events, clock);
    }

    @Bean
    public BudgetChecks budgetChecks(BudgetStorePort budgets, BudgetsService service, Clock clock) {
        return new BudgetChecks(budgets, service, clock);
    }

    @Bean
    public BudgetEvaluator budgetEvaluator(BudgetStorePort budgets, BudgetPeriodStorePort periods, BudgetsService service, CostQueries costs,
            BudgetEvents events, Clock clock, CostProperties properties) {
        return new BudgetEvaluator(budgets, periods, service, costs, events, clock, properties.budgets().evaluateInterval());
    }

    // chargeback

    @Bean
    public ChargebackService chargebackService(ChargebackStorePort store, CostQueries costs, Accounts accounts, Pricing pricing, Clock clock) {
        return new ChargebackService(store, costs, accounts, pricing, clock);
    }

    @Bean
    public MonthCloser monthCloser(LedgerStatePort ledgers, ChargebackStorePort store, ChargebackService chargebacks, FinanceExportPort finance,
            OutboxPort outbox, Clock clock, CostProperties properties) {
        return new MonthCloser(ledgers, store, chargebacks, finance, outbox, clock, properties.chargeback().interval());
    }

    /** The background jobs: price refresh, day closing, the pending-delta repair, budget evaluation and the month closer. */
    @Bean
    public SmartLifecycle costJobs(Pricing pricing, DayCloser closer, Housekeeping housekeeping, BudgetEvaluator evaluator, MonthCloser months,
            CostProperties properties) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                pricing.start();
                if (properties.ingest().enabled()) {
                    closer.start();
                    housekeeping.start();
                    evaluator.start();
                    months.start();
                }
                running = true;
            }

            @Override
            public void stop() {
                months.stop();
                evaluator.stop();
                housekeeping.stop();
                closer.stop();
                pricing.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
