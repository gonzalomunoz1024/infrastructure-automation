package com.iaf.platform.resources.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.Revisioned;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The resource registry of the {@code mongo} profile: every resource a job registered is a
 * document of {@code resources}, keyed by its id, so lineage and retirements survive a restart.
 * The resource types are the {@code kind: ResourceType} files of the content dir, written to
 * {@code resource_types} (one document per type, keyed by its name) at every start (the files
 * win) and read from a copy reloaded whenever any instance writes. With
 * {@code platform.demo.seed-history} the demo resources are added once (their ids are fixed).
 *
 * <p>A resource document keeps the ids it was built from as a top-level array, so
 * {@link #builtFrom} is one indexed query.
 */
@Slf4j
@Component
@Primary
@Profile("mongo")
public class MongoResourceStore implements ResourceStorePort {

    static final String TYPES = "resource_types";
    static final String COLLECTION = "resources";

    private static final Comparator<Resource> ORDER = Comparator.comparing(Resource::type)
            .thenComparing(Resource::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Resource::id);

    private final Mongo mongo;
    private final PlatformProperties props;
    private final Clock clock;
    private final Revisioned<List<ResourceTypeDefinition>> types;

    public MongoResourceStore(Mongo mongo, PlatformProperties props, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
        this.types = new Revisioned<>(mongo, TYPES, this::loadTypes);
        mongo.index(COLLECTION, Indexes.ascending("type"));
        mongo.index(COLLECTION, Indexes.ascending("builtFrom"));
    }

    /**
     * The types of the content dir that differ from the stored ones are written, then the
     * revision moves once, so another instance reloads them together. A start that stops between
     * the two leaves those instances on the old types until the next write to the types moves the
     * revision; the instances started after it read the collection afresh.
     */
    @PostConstruct
    public void seed() {
        Path root = ResourceSeeds.root(props);
        Map<String, ResourceTypeDefinition> stored = types().stream().collect(Collectors.toMap(ResourceTypeDefinition::name, Function.identity()));
        List<ResourceTypeDefinition> changed = ResourceSeeds.types(root).stream().filter(t -> !t.equals(stored.get(t.name()))).toList();
        if (!changed.isEmpty()) {
            changed.forEach(this::upsertType);
            types.bump();
        }
        log.info("resource registry: {} types ({} written from {})", types().size(), changed.size(), root.resolve("resource-types"));
        if (props.getDemo().isSeedHistory() && !types().isEmpty()) {
            int added = 0;
            for (Resource r : ResourceSeeds.demo(root, types(), clock.instant())) {
                if (insertIfAbsent(r)) {
                    added++;
                }
            }
            log.info("resource registry: {} demo resources seeded", added);
        }
    }

    @Override
    public List<ResourceTypeDefinition> types() {
        return types.get();
    }

    @Override
    public Optional<ResourceTypeDefinition> type(String name) {
        return name == null ? Optional.empty() : types().stream().filter(t -> t.name().equals(name)).findFirst();
    }

    @Override
    public void saveType(ResourceTypeDefinition type) {
        upsertType(type);
        types.bump();
    }

    @Override
    public List<Resource> resources() {
        return inOrder(Filters.empty());
    }

    @Override
    public Optional<Resource> resource(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(mongo.collection(COLLECTION).find(Filters.eq("_id", id)).first()).map(this::read);
    }

    /** The resources whose {@code builtFrom} array holds the id (an indexed match on its elements), in the registry's order. */
    @Override
    public List<Resource> builtFrom(String id) {
        if (id == null || id.isBlank()) {
            return List.of();
        }
        return inOrder(Filters.eq("builtFrom", id));
    }

    @Override
    public void save(Resource resource) {
        mongo.collection(COLLECTION).replaceOne(Filters.eq("_id", resource.id()), document(resource), new ReplaceOptions().upsert(true));
    }

    /** Adds a resource unless one has its id; true when it was added. */
    private boolean insertIfAbsent(Resource resource) {
        try {
            mongo.collection(COLLECTION).insertOne(document(resource));
            return true;
        } catch (MongoException e) {
            if (Mongo.duplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    private Document document(Resource r) {
        return new Document("_id", r.id())
                .append("type", r.type())
                .append("status", r.status())
                .append("createdAt", Mongo.date(r.createdAt()))
                .append("builtFrom", r.builtFrom())
                .append(Mongo.DOC, mongo.doc(r));
    }

    private void upsertType(ResourceTypeDefinition type) {
        Document d = new Document("_id", type.name()).append(Mongo.DOC, mongo.doc(type));
        mongo.collection(TYPES).replaceOne(Filters.eq("_id", type.name()), d, new ReplaceOptions().upsert(true));
    }

    private List<ResourceTypeDefinition> loadTypes() {
        List<ResourceTypeDefinition> out = new ArrayList<>();
        mongo.collection(TYPES).find().forEach(d -> out.add(mongo.read(d, ResourceTypeDefinition.class)));
        return out.stream().sorted(Comparator.comparing(ResourceTypeDefinition::name)).toList();
    }

    /** The resources the filter matches: by type, then oldest first (undated ones last), then by id, as in memory. */
    private List<Resource> inOrder(Bson filter) {
        List<Resource> out = new ArrayList<>();
        mongo.collection(COLLECTION).find(filter).forEach(d -> out.add(read(d)));
        out.sort(ORDER);
        return out;
    }

    private Resource read(Document stored) {
        return mongo.read(stored, Resource.class);
    }
}
