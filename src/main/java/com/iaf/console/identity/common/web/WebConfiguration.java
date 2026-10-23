package com.iaf.console.identity.common.web;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.CallerArgumentResolver;

/** The web layer's shared pieces: the caller argument, the clock and the id minter. */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebFluxConfigurer {

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(new CallerArgumentResolver());
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public Ids ids(Clock clock) {
        return Ids.secure(clock);
    }
}
