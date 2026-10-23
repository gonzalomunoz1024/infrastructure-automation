package com.iaf.console.state.inventory.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.console.state.inventory.domain.Drift;
import com.iaf.console.state.inventory.domain.DriftField;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;
import com.iaf.console.state.inventory.ports.outbound.AccountKeysPort;

import reactor.core.publisher.Mono;

/**
 * Envelope encryption of sensitive fields, one data key per account under the master key (WP 2.5).
 * A sensitive value (a field the type marks {@code x-console: {sensitive: true}}, or one whose name
 * says secret) is sealed before it is stored: {@code {"$enc": "<nonce+ciphertext>", "k": <key version>}}
 * in the log and the projection, {@code "***"} on Kafka, in the trail and in every answer unless the
 * caller asks for {@code reveal=sensitive}. Destroying an account's key (its closure) is the
 * crypto-shred: nothing sealed under it can be read again, however many copies the log keeps.
 */
public final class SensitiveData {

    /** The marker of a sealed value. */
    public static final String ENC = "$enc";
    /** What a sealed value looks like to anyone not allowed to see it. */
    public static final String MASK = "***";
    /** Field names that are sensitive whatever the type says. */
    public static final Pattern SECRET_NAMES = Pattern.compile("(?i)(password|passwd|secret|token|credential|private[-_]?key|api[-_]?key)");

    private static final Logger log = LoggerFactory.getLogger(SensitiveData.class);
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final AccountKeysPort keys;
    private final SecretKey masterKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Mono<SecretKey>> cache = new ConcurrentHashMap<>();

    public SensitiveData(AccountKeysPort keys, byte[] masterKey, Clock clock) {
        if (masterKey == null || masterKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("the master key is " + KEY_BYTES + " bytes");
        }
        this.keys = keys;
        this.masterKey = new SecretKeySpec(masterKey, "AES");
        this.clock = clock;
    }

    /** A fresh random master key, for the demo and the tests; production sets console.crypto.master-key. */
    public static byte[] randomMasterKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    // sealing

    /** The event with its sensitive values sealed under the account's key. */
    public Mono<ResourceEvent> seal(ResourceEvent event, ResourceTypeInfo type) {
        ObjectNode data = event.data();
        if (data == null || !hasAnything(data, type)) {
            return Mono.just(event);
        }
        return key(event.accountId()).map(key -> {
            ObjectNode sealed = data.deepCopy();
            for (String holder : List.of("input", "attributes", "changes", "observed")) {
                ObjectNode node = Json.objectField(sealed, holder);
                if (node != null) {
                    sealObject(node, type, key, event.accountId());
                }
            }
            JsonNode fields = sealed.get("fields");
            if (fields instanceof ArrayNode array) {
                for (JsonNode f : array) {
                    if (f instanceof ObjectNode field && sensitive(pathName(Json.text(field, "path")), type)) {
                        for (String side : List.of("expected", "observed")) {
                            JsonNode v = field.get(side);
                            if (v != null && !v.isNull() && !isSealed(v)) {
                                field.set(side, encrypt(v, key, event.accountId()));
                            }
                        }
                    }
                }
            }
            return event.withData(sealed);
        });
    }

    private static boolean hasAnything(ObjectNode data, ResourceTypeInfo type) {
        for (String holder : List.of("input", "attributes", "changes", "observed")) {
            ObjectNode node = Json.objectField(data, holder);
            if (node != null && node.properties().stream().anyMatch(e -> sensitive(e.getKey(), type) && !isSealed(e.getValue()))) {
                return true;
            }
        }
        JsonNode fields = data.get("fields");
        if (fields != null && fields.isArray()) {
            for (JsonNode f : fields) {
                if (sensitive(pathName(Json.text(f, "path")), type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sealObject(ObjectNode node, ResourceTypeInfo type, SecretKey key, String accountId) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode value = node.get(name);
            if (sensitive(name, type) && value != null && !value.isNull() && !isSealed(value)) {
                node.set(name, encrypt(value, key, accountId));
            }
        }
    }

    /** Whether a field is sensitive: the type says so, or its name does. */
    public static boolean sensitive(String field, ResourceTypeInfo type) {
        if (field == null) {
            return false;
        }
        return (type != null && type.sensitiveFields().contains(field)) || SECRET_NAMES.matcher(field).find();
    }

    private static String pathName(String path) {
        if (path == null) {
            return null;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        return slash < 0 ? p : p.substring(0, slash);
    }

    public static boolean isSealed(JsonNode value) {
        return value instanceof ObjectNode o && o.has(ENC);
    }

    // masking

    /** The node with every sealed value replaced by {@value #MASK}, deep; null stays null. */
    public static ObjectNode mask(ObjectNode node) {
        if (node == null) {
            return null;
        }
        ObjectNode out = node.deepCopy();
        maskIn(out);
        return out;
    }

    private static void maskIn(JsonNode node) {
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode v = o.get(name);
                if (isSealed(v)) {
                    o.put(name, MASK);
                } else {
                    maskIn(v);
                }
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                if (isSealed(v)) {
                    a.set(i, TextNode.valueOf(MASK));
                } else {
                    maskIn(v);
                }
            }
        }
    }

    public static ResourceEvent mask(ResourceEvent event) {
        return event.data() == null ? event : event.withData(mask(event.data()));
    }

    public static Resource mask(Resource r) {
        return rebuild(r, mask(r.attributes()), mask(r.changes()), mask(r.observed()), maskDrift(r.drift()));
    }

    private static Drift maskDrift(Drift drift) {
        if (drift == null) {
            return null;
        }
        List<DriftField> fields = new ArrayList<>();
        for (DriftField f : drift.fields()) {
            fields.add(new DriftField(f.path(), isSealed(f.expected()) ? TextNode.valueOf(MASK) : f.expected(),
                    isSealed(f.observed()) ? TextNode.valueOf(MASK) : f.observed()));
        }
        return new Drift(drift.findingId(), drift.since(), drift.severity(), fields);
    }

    // revealing

    /** The resource with its sealed values decrypted; a value whose key is gone stays masked. */
    public Mono<Resource> reveal(Resource r) {
        if (!hasSealed(r.attributes()) && !hasSealed(r.changes()) && !hasSealed(r.observed()) && !hasSealedDrift(r.drift())) {
            return Mono.just(r);
        }
        return keyIfAny(r.accountId()).map(key -> rebuild(r, open(r.attributes(), key, r.accountId()), open(r.changes(), key, r.accountId()),
                open(r.observed(), key, r.accountId()), openDrift(r.drift(), key, r.accountId())))
                .defaultIfEmpty(mask(r));
    }

    public Mono<ResourceEvent> reveal(ResourceEvent event) {
        if (!hasSealed(event.data())) {
            return Mono.just(event);
        }
        return keyIfAny(event.accountId()).map(key -> event.withData(open(event.data(), key, event.accountId()))).defaultIfEmpty(mask(event));
    }

    private Drift openDrift(Drift drift, SecretKey key, String accountId) {
        if (drift == null) {
            return null;
        }
        List<DriftField> fields = new ArrayList<>();
        for (DriftField f : drift.fields()) {
            fields.add(new DriftField(f.path(), openValue(f.expected(), key, accountId), openValue(f.observed(), key, accountId)));
        }
        return new Drift(drift.findingId(), drift.since(), drift.severity(), fields);
    }

    private ObjectNode open(ObjectNode node, SecretKey key, String accountId) {
        if (node == null) {
            return null;
        }
        ObjectNode out = node.deepCopy();
        openIn(out, key, accountId);
        return out;
    }

    private void openIn(JsonNode node, SecretKey key, String accountId) {
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode v = o.get(name);
                if (isSealed(v)) {
                    o.set(name, openValue(v, key, accountId));
                } else {
                    openIn(v, key, accountId);
                }
            }
        } else if (node instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                if (isSealed(v)) {
                    a.set(i, openValue(v, key, accountId));
                } else {
                    openIn(v, key, accountId);
                }
            }
        }
    }

    private JsonNode openValue(JsonNode value, SecretKey key, String accountId) {
        if (!isSealed(value)) {
            return value;
        }
        try {
            return decrypt((ObjectNode) value, key, accountId);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("a sealed value of account {} could not be opened: {}", accountId, e.toString());
            return TextNode.valueOf(MASK);
        }
    }

    private static boolean hasSealed(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (isSealed(node)) {
            return true;
        }
        for (JsonNode child : node) {
            if (hasSealed(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSealedDrift(Drift drift) {
        return drift != null && drift.fields().stream().anyMatch(f -> isSealed(f.expected()) || isSealed(f.observed()));
    }

    private static Resource rebuild(Resource r, ObjectNode attributes, ObjectNode changes, ObjectNode observed, Drift drift) {
        return new Resource(r.wfrn(), r.accountId(), r.region(), r.type(), r.service(), r.name(), r.state(), r.version(), r.registryId(),
                r.providerId(), attributes, changes, observed, r.observedStatus(), r.observedAt(), drift, r.tags(), r.requestedBy(), r.workflow(),
                r.lastJobId(), r.lastError(), r.source(), r.createdAt(), r.updatedAt(), r.deletedAt(), r.tombstoneUntil());
    }

    // the keys

    /** Destroys an account's data key: the crypto-shred of everything sealed for it. */
    public Mono<Boolean> shred(String accountId) {
        cache.remove(accountId);
        return keys.destroy(accountId).doOnNext(gone -> {
            if (gone) {
                log.warn("the data key of account {} was destroyed: its sealed values are unreadable from now on", accountId);
            }
        });
    }

    private Mono<SecretKey> key(String accountId) {
        return cache.computeIfAbsent(accountId, id -> loadOrCreate(id).cache());
    }

    private Mono<SecretKey> keyIfAny(String accountId) {
        Mono<SecretKey> cached = cache.get(accountId);
        if (cached != null) {
            return cached;
        }
        return keys.find(accountId).map(this::unwrap).doOnNext(k -> cache.put(accountId, Mono.just(k)));
    }

    private Mono<SecretKey> loadOrCreate(String accountId) {
        return keys.find(accountId)
                .switchIfEmpty(Mono.defer(() -> keys.save(wrap(accountId, newDataKey()))))
                .map(this::unwrap)
                .onErrorResume(e -> {
                    cache.remove(accountId);
                    return Mono.error(e);
                });
    }

    private byte[] newDataKey() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return key;
    }

    private AccountKeysPort.WrappedKey wrap(String accountId, byte[] dataKey) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD((accountId + ":1").getBytes(StandardCharsets.UTF_8));
            return new AccountKeysPort.WrappedKey(accountId, 1, cipher.doFinal(dataKey), nonce, clock.instant());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not wrap a data key", e);
        }
    }

    private SecretKey unwrap(AccountKeysPort.WrappedKey wrapped) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, wrapped.nonce()));
            cipher.updateAAD((wrapped.accountId() + ":" + wrapped.version()).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(cipher.doFinal(wrapped.wrapped()), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the data key of account " + wrapped.accountId() + " does not open under this master key", e);
        }
    }

    private ObjectNode encrypt(JsonNode value, SecretKey key, String accountId) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
            byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            ObjectNode sealed = Json.object();
            sealed.put(ENC, Base64.getEncoder().encodeToString(out));
            sealed.put("k", 1);
            return sealed;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not seal a value", e);
        }
    }

    private JsonNode decrypt(ObjectNode sealed, SecretKey key, String accountId) throws GeneralSecurityException {
        byte[] all = Base64.getDecoder().decode(Objects.requireNonNull(Json.text(sealed, ENC)));
        if (all.length < NONCE_BYTES + 1) {
            throw new IllegalArgumentException("a sealed value is too short");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(all, 0, nonce, 0, NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(accountId.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
        try {
            return Json.MAPPER.readTree(plain);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("a sealed value is not JSON", e);
        }
    }

    /** The set of fields a type seals, for the API's documentation of a type. */
    public static Set<String> sensitiveFieldsOf(ResourceTypeInfo type) {
        return type == null ? Set.of() : type.sensitiveFields();
    }
}
