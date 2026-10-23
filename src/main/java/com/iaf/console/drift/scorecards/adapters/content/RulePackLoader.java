package com.iaf.console.drift.scorecards.adapters.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.scorecards.domain.Condition;
import com.iaf.console.drift.scorecards.domain.RemediationInput;
import com.iaf.console.drift.scorecards.domain.RemediationSpec;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;

/**
 * Reads the rule packs (kind: RulePack, the hub's rule-packs/ pinned in contracts.lock): the ones
 * this service carries on its classpath under {@code rule-packs/}, and the files of a directory
 * (a mounted ConfigMap) that add packs or replace one of the same name. A pack that does not
 * parse stops the start: scoring against half a pack would publish wrong scores.
 */
public final class RulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(RulePackLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private RulePackLoader() {
    }

    public static RulePacks load(String directory) {
        Map<String, RulePack> byName = new LinkedHashMap<>();
        try {
            Resource[] bundled = new PathMatchingResourcePatternResolver().getResources("classpath*:rule-packs/*.yaml");
            for (Resource r : bundled) {
                try (InputStream in = r.getInputStream()) {
                    RulePack pack = parse(YAML.readTree(in), r.getFilename());
                    byName.put(pack.name(), pack);
                }
            }
            if (directory != null && !directory.isBlank()) {
                try (Stream<Path> files = Files.list(Path.of(directory))) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml")).sorted().toList()) {
                        RulePack pack = parse(YAML.readTree(file.toFile()), file.toString());
                        if (byName.put(pack.name(), pack) != null) {
                            log.info("rule pack {} from {} replaces the bundled one", pack.name(), file);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the rule packs cannot be read", e);
        }
        RulePacks packs = RulePacks.of(new ArrayList<>(byName.values()));
        log.info("rule packs: {} ({} rules, digest {})", packs.packs().stream().map(p -> p.name() + "@" + p.version()).toList(), packs.allRules().size(),
                packs.digest());
        return packs;
    }

    /** One pack document; what it does not say right is an IllegalArgumentException naming the source. */
    public static RulePack parse(JsonNode doc, String source) {
        try {
            require("iaf.console/v1".equals(doc.path("apiVersion").asText()) && "RulePack".equals(doc.path("kind").asText()),
                    "apiVersion iaf.console/v1 and kind RulePack");
            JsonNode meta = doc.path("metadata");
            String name = text(meta, "name", true);
            List<Rule> rules = new ArrayList<>();
            for (JsonNode r : doc.path("spec").path("rules")) {
                rules.add(rule(name, r));
            }
            require(!rules.isEmpty(), "at least one rule");
            require(rules.stream().map(Rule::id).distinct().count() == rules.size(), "rule ids unique in the pack");
            int version = meta.path("version").asInt(0);
            require(version >= 1, "metadata.version 1 or more");
            return new RulePack(name, text(meta, "title", true), text(meta, "description", false), version, text(meta, "owner", false), rules);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rule pack " + source + ": " + e.getMessage(), e);
        }
    }

    private static Rule rule(String pack, JsonNode r) {
        String id = text(r, "id", true);
        try {
            Severity severity = Severity.of(text(r, "severity", true));
            require(severity != null, "severity info, warning or critical");
            LinkedHashSet<String> types = new LinkedHashSet<>();
            r.path("types").forEach(t -> types.add(t.asText()));
            require(!types.isEmpty(), "at least one type");
            Condition when = r.hasNonNull("when") ? Condition.parse(r.get("when")) : null;
            Condition check = Condition.parse(r.path("check"));
            List<Rule.Example> examples = new ArrayList<>();
            for (JsonNode e : r.path("examples")) {
                examples.add(new Rule.Example(e.path("input"), e.path("expect").asText()));
            }
            return new Rule(pack, id, text(r, "title", true), text(r, "description", false), severity, types, when, check, text(r, "message", true),
                    text(r, "rego", true), remediation(r.get("remediation")), examples);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rule " + id + ": " + e.getMessage(), e);
        }
    }

    private static RemediationSpec remediation(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Map<String, String> set = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.path("set").properties().iterator(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            set.put(e.getKey(), e.getValue().asText());
        }
        List<RemediationInput> inputs = new ArrayList<>();
        for (JsonNode i : node.path("inputs")) {
            List<JsonNode> enumeration = null;
            if (i.has("enum")) {
                enumeration = new ArrayList<>();
                i.get("enum").forEach(enumeration::add);
            }
            inputs.add(new RemediationInput(text(i, "name", true), text(i, "title", true), text(i, "type", false), enumeration, i.get("default"),
                    !i.has("required") || i.get("required").asBoolean()));
        }
        return new RemediationSpec(text(node, "title", true), text(node, "kind", true), text(node, "verb", false), text(node, "workflow", false),
                node.get("input") instanceof ObjectNode o ? o.deepCopy() : null, set.isEmpty() ? null : set, inputs);
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            require(!required, field + " is required");
            return null;
        }
        return v.asText();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException("needs " + what);
        }
    }
}
