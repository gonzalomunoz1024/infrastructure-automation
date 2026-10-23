package com.iaf.console.bff.platform.application;

import java.util.function.Supplier;

/** The platform's URL, resolved late: the embedded fake only knows its port once it listens. */
public record PlatformBaseUrl(Supplier<String> supplier) {

    public String get() {
        return supplier.get().replaceAll("/+$", "");
    }
}
