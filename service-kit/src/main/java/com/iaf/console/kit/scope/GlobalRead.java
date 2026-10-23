package com.iaf.console.kit.scope;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a port method, or a whole port, that reads no tenant data (content, resource types,
 * regions, the catalog) and so needs no {@link AccountScope}; the architecture rules skip it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface GlobalRead {

    /** Why the read is global, in one plain sentence. */
    String value() default "";
}
