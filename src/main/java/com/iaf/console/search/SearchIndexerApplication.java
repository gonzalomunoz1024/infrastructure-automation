package com.iaf.console.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The console's search: resources from Kafka into OpenSearch, and the search API the BFF uses. */
@SpringBootApplication
public class SearchIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchIndexerApplication.class, args);
    }
}
