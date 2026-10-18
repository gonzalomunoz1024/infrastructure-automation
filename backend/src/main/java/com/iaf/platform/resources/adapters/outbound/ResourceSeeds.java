package com.iaf.platform.resources.adapters.outbound;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.DemoResources;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import lombok.extern.slf4j.Slf4j;

/**
 * What every registry reads at start, whatever keeps it: the {@code kind: ResourceType} files of
 * the content dir and, with {@code platform.demo.seed-history}, the demo resources
 * {@link DemoResources} derives from the types and the workflows.
 */
@Slf4j
final class ResourceSeeds {

    private ResourceSeeds() {
    }

    static Path root(PlatformProperties props) {
        return Path.of(props.getContent().getDir()).toAbsolutePath().normalize();
    }

    /** The resource types of the content dir; a file that does not parse is logged and skipped. */
    static List<ResourceTypeDefinition> types(Path root) {
        List<ResourceTypeDefinition> out = new ArrayList<>();
        for (ContentFiles.ContentFile f : ContentFiles.ofKind(root, ResourceTypeDefinition.KIND)) {
            try {
                out.add(ResourceTypeDefinition.parse(f.yaml()));
            } catch (IllegalArgumentException e) {
                log.warn("skipping resource type {}: {}", f.relative(root), e.getMessage());
            }
        }
        return out;
    }

    /** The demo resources for the types, derived from the workflows of the content dir. */
    static List<Resource> demo(Path root, List<ResourceTypeDefinition> types, Instant now) {
        List<WorkflowDefinition> workflows = new ArrayList<>();
        for (ContentFiles.ContentFile f : ContentFiles.ofKind(root, WorkflowDefinition.KIND)) {
            try {
                workflows.add(WorkflowYaml.parse(f.yaml()));
            } catch (IllegalArgumentException e) {
                log.debug("demo seed ignores {}: {}", f.relative(root), e.getMessage());
            }
        }
        return DemoResources.seed(types, workflows, now);
    }
}
