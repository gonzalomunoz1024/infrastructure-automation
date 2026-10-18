package com.iaf.platform.assistant.adapters.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * The Devin playbooks as the repo keeps them: {@code devin/<name>.md} whose front matter names the
 * {@code title}, the {@code macro} and the {@code structured_output_schema} file next to it. The
 * body Devin runs is the markdown after the front matter.
 */
final class PlaybookFiles {

    /** Devin's own limits: a macro is {@code !} and letters, digits, {@code _} or {@code -}; a schema is at most 64 KB with no external {@code $ref}. */
    static final Pattern MACRO = Pattern.compile("^![A-Za-z0-9_-]+$");
    static final int MAX_SCHEMA_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private PlaybookFiles() {
    }

    record PlaybookFile(String file, String title, String macro, String body, JsonNode schema) {
    }

    /** Every playbook in {@code dir}, in file-name order; markdown without a macro in its front matter is not a playbook. */
    static List<PlaybookFile> read(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("no playbook directory at " + dir.toAbsolutePath().normalize());
        }
        List<PlaybookFile> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".md")).sorted().toList()) {
                PlaybookFile f = parse(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8), name -> readSibling(dir, name));
                if (f != null) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Null when the text has no front matter with a macro. {@code sibling} reads the schema file the front matter names. */
    static PlaybookFile parse(String file, String text, Function<String, String> sibling) {
        String t = text == null ? "" : text.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) {
            return null;
        }
        int end = t.indexOf("\n---", 3);
        if (end < 4) {
            return null;
        }
        JsonNode front;
        try {
            front = YAML.readTree(t.substring(4, end));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(file + ": the front matter is not YAML: " + e.getOriginalMessage());
        }
        if (front == null || !front.hasNonNull("macro")) {
            return null;
        }
        String macro = front.get("macro").asText().trim();
        if (!MACRO.matcher(macro).matches()) {
            throw new IllegalArgumentException(file + ": macro '" + macro + "' must be ! followed by letters, digits, _ or -");
        }
        String title = front.path("title").asText("").trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException(file + ": the front matter has no title");
        }
        int bodyStart = t.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : t.substring(bodyStart + 1).strip() + "\n";
        JsonNode schema = null;
        String schemaFile = front.path("structured_output_schema").asText("").trim();
        if (!schemaFile.isEmpty()) {
            schema = schema(schemaFile, sibling.apply(schemaFile));
        }
        return new PlaybookFile(file, title, macro, body, schema);
    }

    /** A structured-output schema as Devin accepts it: JSON, at most 64 KB, no external {@code $ref}. */
    static JsonNode schema(String file, String text) {
        if (text == null) {
            throw new IllegalArgumentException("schema " + file + " not found");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("schema " + file + " is over 64 KB");
        }
        JsonNode schema;
        try {
            schema = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("schema " + file + " is not JSON: " + e.getOriginalMessage());
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("schema " + file + " is not a JSON object");
        }
        for (JsonNode ref : schema.findValues("$ref")) {
            if (!ref.asText("").startsWith("#")) {
                throw new IllegalArgumentException("schema " + file + " has an external $ref " + ref.asText());
            }
        }
        return schema;
    }

    /** The schema file in {@code dir}, or null when it is not there. */
    static JsonNode schemaOrNull(Path dir, String file) {
        String text = readSibling(dir, file);
        return text == null ? null : schema(file, text);
    }

    private static String readSibling(Path dir, String name) {
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir.normalize()) || !Files.isRegularFile(p)) {
            return null;
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p + ": " + e.getMessage(), e);
        }
    }
}
