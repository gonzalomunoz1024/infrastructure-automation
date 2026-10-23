package com.iaf.console.fake.content.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.domain.CatalogEntries;
import com.iaf.console.fake.content.domain.PriceList;
import com.iaf.console.fake.content.domain.ProviderDef;
import com.iaf.console.fake.content.domain.RegionDef;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.content.domain.TypeOverlays;
import com.iaf.console.fake.content.domain.WorkflowDef;

/**
 * The published content of the fake platform, loaded once from the classpath: the resource types
 * with the hub's overlays merged, the workflows, the providers with their recorded samples, the
 * regions, the price list, the catalog and the target OpenAPI document.
 */
public final class ContentStore {

    /** Where the fake's content lives on the classpath. */
    public static final String DEFAULT_BASE = "platform-fake";

    private final Map<String, ResourceTypeDef> types;
    private final Map<String, WorkflowDef> workflows;
    private final Map<String, ProviderDef> providers;
    private final Map<String, RegionDef> regions;
    private final PriceList prices;
    private final List<ObjectNode> catalog;
    private final String typesEtag;
    private final JsonNode openApi;

    private ContentStore(Map<String, ResourceTypeDef> types, Map<String, WorkflowDef> workflows, Map<String, ProviderDef> providers,
            Map<String, RegionDef> regions, PriceList prices, JsonNode openApi) {
        this.types = types;
        this.workflows = workflows;
        this.providers = providers;
        this.regions = regions;
        this.prices = prices;
        this.openApi = openApi;
        List<ObjectNode> entries = new ArrayList<>();
        for (WorkflowDef workflow : workflows.values()) {
            entries.add(CatalogEntries.entry(workflow, this::effectOf));
        }
        this.catalog = List.copyOf(entries);
        this.typesEtag = "\"" + Json.hash(types.values().stream().map(ResourceTypeDef::hash).toList()) + "\"";
    }

    /** Loads the content under {@link #DEFAULT_BASE}. */
    public static ContentStore load() {
        return load(DEFAULT_BASE);
    }

    /** Loads the content under a classpath base: {@code content/**}, {@code overlays/}, {@code additions/}, {@code regions/}, {@code contracts/}. */
    public static ContentStore load(String base) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(ContentStore.class.getClassLoader());
        Map<String, JsonNode> typeFiles = new TreeMap<>();
        Map<String, WorkflowDef> workflows = new TreeMap<>();
        Map<String, ProviderDef> providers = new TreeMap<>();
        PriceList prices = PriceList.empty();
        for (JsonNode document : documents(resolver, "classpath*:" + base + "/content/**/*.yaml")) {
            String kind = document.path("kind").asText();
            String name = document.path("metadata").path("name").asText();
            switch (kind) {
                case "ResourceType" -> typeFiles.put(name, document);
                case "Workflow" -> workflows.put(name, new WorkflowDef((ObjectNode) document));
                case "Provider" -> providers.put(name, new ProviderDef((ObjectNode) document));
                case "PriceList" -> prices = new PriceList(document);
                default -> {
                }
            }
        }
        for (JsonNode additions : documents(resolver, "classpath*:" + base + "/additions/*.yaml")) {
            String name = additions.path("metadata").path("name").asText();
            ProviderDef provider = providers.get(name);
            if (provider != null) {
                providers.put(name, provider.withActions(additions.path("spec").path("actions")));
            }
        }
        Map<String, JsonNode> overlays = new LinkedHashMap<>();
        for (JsonNode overlay : documents(resolver, "classpath*:" + base + "/overlays/*.yaml")) {
            overlays.put(overlay.path("metadata").path("name").asText(), overlay);
        }
        Map<String, ResourceTypeDef> types = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> typeFile : typeFiles.entrySet()) {
            ObjectNode merged = TypeOverlays.merge(typeFile.getValue(), overlays.get(typeFile.getKey()));
            merged.put("version", 1);
            merged.put("hash", Json.hash(merged));
            types.put(typeFile.getKey(), new ResourceTypeDef(merged));
        }
        Map<String, RegionDef> regions = new LinkedHashMap<>();
        List<JsonNode> regionFiles = documents(resolver, "classpath*:" + base + "/regions/*.yaml");
        regionFiles.sort(Comparator.comparing(r -> r.path("metadata").path("name").asText()));
        for (JsonNode regionFile : regionFiles) {
            boolean isDefault = regionFile.path("spec").path("default").asBoolean(regions.isEmpty());
            RegionDef region = RegionDef.of(regionFile, isDefault, Json.hash(regionFile));
            regions.put(region.name(), region);
        }
        JsonNode openApi = readFirst(resolver, "classpath*:" + base + "/contracts/openapi-target.yaml");
        return new ContentStore(types, workflows, providers, regions, prices, openApi);
    }

    private static List<JsonNode> documents(PathMatchingResourcePatternResolver resolver, String pattern) {
        try {
            Resource[] resources = resolver.getResources(pattern);
            Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            List<JsonNode> documents = new ArrayList<>();
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    documents.add(Json.readYaml(in));
                }
            }
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode readFirst(PathMatchingResourcePatternResolver resolver, String pattern) {
        List<JsonNode> documents = documents(resolver, pattern);
        return documents.isEmpty() ? Json.object() : documents.get(0);
    }

    public Collection<ResourceTypeDef> types() {
        return types.values();
    }

    public Optional<ResourceTypeDef> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    /** The type whose plural names a generated route, such as {@code vms}. */
    public Optional<ResourceTypeDef> typeByPlural(String plural) {
        return types.values().stream().filter(t -> t.plural().equals(plural)).findFirst();
    }

    /** The ETag of the whole type list. */
    public String typesEtag() {
        return typesEtag;
    }

    public Collection<WorkflowDef> workflows() {
        return workflows.values();
    }

    public Optional<WorkflowDef> workflow(String name) {
        return Optional.ofNullable(workflows.get(name));
    }

    public Collection<ProviderDef> providers() {
        return providers.values();
    }

    public Optional<ProviderDef> provider(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    public Collection<RegionDef> regions() {
        return regions.values();
    }

    public Optional<RegionDef> region(String name) {
        return Optional.ofNullable(regions.get(name));
    }

    /** The default region, the first one when none says so. */
    public RegionDef defaultRegion() {
        return regions.values().stream().filter(RegionDef::isDefault).findFirst()
                .orElseGet(() -> regions.values().iterator().next());
    }

    public PriceList prices() {
        return prices;
    }

    /** The catalog entries, optionally of one noun. */
    public List<ObjectNode> catalog(String noun) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode entry : catalog) {
            if (noun == null || noun.equals(entry.path("noun").asText(null))) {
                out.add(entry.deepCopy());
            }
        }
        return out;
    }

    /** The catalog entry of a workflow. */
    public Optional<ObjectNode> catalogEntry(String workflow) {
        return catalog.stream().filter(e -> e.path("workflow").asText().equals(workflow)).findFirst().map(ObjectNode::deepCopy);
    }

    /** The target OpenAPI document, as JSON. */
    public JsonNode openApi() {
        return openApi;
    }

    /** The effect of a provider action, change when the provider or action is unknown. */
    public String effectOf(String provider, String action) {
        ProviderDef def = providers.get(provider);
        return def == null ? "change" : def.effect(action);
    }

    /** The provider a type's resources live in: its observe provider, else the first provider call of its producing workflow. */
    public Optional<String> providerOf(ResourceTypeDef type) {
        if (type.hasObserve()) {
            return Optional.ofNullable(type.observe().path("provider").asText(null));
        }
        WorkflowDef producer = type.producedBy() == null ? null : workflows.get(type.producedBy());
        return producer == null ? Optional.empty() : firstProviderCall(producer.steps());
    }

    private static Optional<String> firstProviderCall(ArrayNode steps) {
        for (JsonNode step : steps) {
            for (Map.Entry<String, JsonNode> entry : step.properties()) {
                JsonNode body = entry.getValue();
                if ("provider".equals(body.path("call").asText(null))) {
                    return Optional.of(body.path("with").path("provider").asText());
                }
                for (JsonNode nested : CatalogEntries.nestedSteps(body)) {
                    Optional<String> found = firstProviderCall((ArrayNode) nested);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }
        return Optional.empty();
    }
}
