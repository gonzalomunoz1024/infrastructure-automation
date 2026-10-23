package com.iaf.console.bff.demo.mail.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The demo's mailbox, in the {@code demo} profile only: notification emails are collected there and listed at {@code GET /demo-mail}. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoMailConfiguration {

    @Bean
    public DemoMailbox demoMailbox() {
        return new DemoMailbox();
    }
}
