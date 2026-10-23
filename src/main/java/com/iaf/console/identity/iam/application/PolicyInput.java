package com.iaf.console.identity.iam.application;

import java.util.List;

import com.iaf.console.identity.iam.domain.Statement;

/** The contract's {@code PolicyInput}. */
public record PolicyInput(String name, String description, List<Statement> statements) {
}
