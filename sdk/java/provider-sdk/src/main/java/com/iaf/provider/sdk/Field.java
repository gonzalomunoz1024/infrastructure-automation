package com.iaf.provider.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a record component in the derived JSON Schema. Every component is required unless
 * it is a {@code java.util.Optional} or marked {@code optional = true}.
 *
 * <pre>
 * record Reserve(&#64;Field(description = "The pool to take the address from") String pool,
 *                &#64;Field(optional = true) String hostname) {}
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface Field {

    /** The description in the schema; empty = none. */
    String description() default "";

    /** Whether the field may be left out. */
    boolean optional() default false;
}
