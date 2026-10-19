package com.iaf.platform.workflows.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.iaf.platform.providers.domain.ProviderDefinition;

/**
 * Provider files that are not published yet (drafts), put in front of the published providers
 * for one call: the designer's Test workflow and Validate send them with the workflow, and a
 * workflow change request's checks and replay see the files of the provider change requests it
 * requires. A draft stands in for the published provider of its name, or joins them under a new
 * name. Nothing is stored.
 */
public final class ProviderDrafts {

    private ProviderDrafts() {
    }

    /**
     * {@code drafts} in front of {@code published}: the drafts first, so a sample lookup that takes
     * the first match (an ansible repo and playbook, a terraform module source) finds a draft
     * before a published provider, then every published provider no draft names.
     */
    public static List<ProviderDefinition> over(List<ProviderDefinition> published, List<ProviderDefinition> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return published;
        }
        Map<String, ProviderDefinition> byName = new LinkedHashMap<>();
        drafts.forEach(d -> byName.put(d.name(), d));
        published.forEach(p -> byName.putIfAbsent(p.name(), p));
        return List.copyOf(byName.values());
    }

    /** The draft named {@code name}, if one is. */
    public static Optional<ProviderDefinition> named(List<ProviderDefinition> drafts, String name) {
        return drafts == null || name == null ? Optional.empty() : drafts.stream().filter(d -> name.equals(d.name())).findFirst();
    }
}
