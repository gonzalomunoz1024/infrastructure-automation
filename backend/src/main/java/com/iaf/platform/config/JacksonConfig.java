package com.iaf.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The YAML mapper for config-as-code files. The JSON mapper comes from Spring Boot. */
@Configuration
public class JacksonConfig {

    @Bean
    public YamlMapper yamlMapper() {
        return new YamlMapper();
    }

    /** A thin named wrapper so the YAML mapper never gets injected where the JSON one is meant. */
    public static class YamlMapper {
        private final ObjectMapper mapper;

        public YamlMapper() {
            YAMLFactory factory = YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                    .build();
            this.mapper = new ObjectMapper(factory)
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        public ObjectMapper mapper() {
            return mapper;
        }
    }
}
