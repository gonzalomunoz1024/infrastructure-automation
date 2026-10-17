package com.iaf.platform.common.expressions;

/** A template that cannot be parsed or evaluated. Maps to 400 at the edge. */
public class ExpressionException extends IllegalArgumentException {
    public ExpressionException(String message) {
        super(message);
    }
}
