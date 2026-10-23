package com.iaf.console.cost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The console's cost: a ledger priced from the platform's price lists, the Cost Explorer, budgets and chargeback. */
@SpringBootApplication
public class CostServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CostServiceApplication.class, args);
    }
}
