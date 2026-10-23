package com.iaf.console.bff.tags.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import com.iaf.console.bff.resources.domain.ResourceView;

/** A Tag Editor row: the resource as a list shows it, and whether the person may change its tags ({@code <service>:tag} on it). */
public record TagEditorRow(@JsonUnwrapped ResourceView resource, boolean canTag) {
}
