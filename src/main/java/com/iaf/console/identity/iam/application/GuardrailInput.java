package com.iaf.console.identity.iam.application;

import java.util.List;

import com.iaf.console.identity.iam.domain.Statement;

/** The contract's {@code GuardrailInput}: on a change a null field stays as it is. */
public record GuardrailInput(String name, String description, List<Statement> statements, List<String> accounts) {
}
