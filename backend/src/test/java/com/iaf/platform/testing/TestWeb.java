package com.iaf.platform.testing;

import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.iaf.platform.config.GlobalExceptionHandler;

/**
 * A WebTestClient bound to one or more REST adapters with the platform's error handling and the JSON
 * defaults Spring Boot gives the application (java.time as ISO strings, lowercase enums), so adapter
 * tests see the same bodies the running application returns.
 */
public final class TestWeb {

    private TestWeb() {
    }

    /** The JSON mapper as Spring Boot auto-configures it. */
    public static ObjectMapper json() {
        return Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .build();
    }

    public static WebTestClient client(Object... controllers) {
        ObjectMapper mapper = json();
        return WebTestClient.bindToController(controllers)
                .controllerAdvice(new GlobalExceptionHandler())
                .httpMessageCodecs(codecs -> {
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
                    codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
                })
                .build();
    }
}
