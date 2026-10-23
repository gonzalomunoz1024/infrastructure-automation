package com.iaf.console.kit.archsample.good.registry.domain;

/** A sample domain record for the architecture rules' tests: it depends on nothing. */
public record Thing(String id, String name) {

    public static boolean isName(String s) {
        return s != null && !s.isBlank();
    }
}
