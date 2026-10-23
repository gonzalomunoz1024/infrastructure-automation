package com.iaf.console.state.stack.domain;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** The blueprints the service pins from the hub, read as the service reads them. */
public final class BundledBlueprints {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private BundledBlueprints() {
    }

    public static JsonNode document(String name) {
        try (InputStream in = BundledBlueprints.class.getResourceAsStream("/blueprints/" + name + ".yaml")) {
            return YAML.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Blueprint bundled(String name) {
        return BlueprintParser.parse(document(name));
    }
}
