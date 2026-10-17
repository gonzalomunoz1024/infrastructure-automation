package com.iaf.platform.resources.ports.outbound;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;

/** The resource registry and its types. */
public interface ResourceStorePort {

    List<ResourceTypeDefinition> types();

    Optional<ResourceTypeDefinition> type(String name);

    void saveType(ResourceTypeDefinition type);

    List<Resource> resources();

    Optional<Resource> resource(String id);

    void save(Resource resource);
}
