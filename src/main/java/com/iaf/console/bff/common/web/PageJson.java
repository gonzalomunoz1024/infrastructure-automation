package com.iaf.console.bff.common.web;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.kit.paging.Page;

/**
 * Pages as the BFF's contract writes them: {@code next} is always there, {@code null} on the last
 * page, where the kit's {@code Page} would leave it out.
 */
@Configuration(proxyBeanMethods = false)
public class PageJson {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    abstract static class PageMixin {
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer pageCursorAlwaysWritten() {
        return builder -> builder.mixIn(Page.class, PageMixin.class);
    }
}
