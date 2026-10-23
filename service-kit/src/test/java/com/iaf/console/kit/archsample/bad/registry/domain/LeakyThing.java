package com.iaf.console.kit.archsample.bad.registry.domain;

import org.springframework.stereotype.Component;

/** A sample domain class that leans on Spring, which the rules reject. */
@Component
public class LeakyThing {

    public String name() {
        return "leaky";
    }
}
