package com.iaf.platform.common.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.iaf.platform.workflows.domain.WorkflowYaml;

/**
 * The content repo on disk: every {@code .yaml} under it, with its {@code kind}. Workflows live
 * under {@code workflows/<team>/}, providers under {@code providers/<team>/}, resource types under
 * {@code resource-types/}; the kind in the file decides, not the folder.
 */
public final class ContentFiles {

    private ContentFiles() {
    }

    public record ContentFile(Path path, String kind, String yaml) {
        public String relative(Path root) {
            return root.relativize(path).toString().replace('\\', '/');
        }
    }

    /** The folders of the content repo that hold config as code. */
    public static final List<String> FOLDERS = List.of("workflows", "providers", "resource-types");

    public static List<ContentFile> scan(Path root) {
        List<ContentFile> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        for (String folder : FOLDERS) {
            Path dir = root.resolve(folder);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                String yaml = Files.readString(p, StandardCharsets.UTF_8);
                                String kind = WorkflowYaml.kindOf(yaml);
                                if (kind != null) {
                                    out.add(new ContentFile(p, kind, yaml));
                                }
                            } catch (IOException | IllegalArgumentException e) {
                                // a broken file is reported by the checks, not by the scan
                            }
                        });
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read content dir " + dir, e);
            }
        }
        return out;
    }

    public static List<ContentFile> ofKind(Path root, String kind) {
        return scan(root).stream().filter(f -> kind.equals(f.kind())).toList();
    }

    /** {@code workflows/<team>/<name>.yaml} style path for a new file. */
    public static Path pathFor(Path root, String folder, String owner, String name) {
        String team = owner == null || owner.isBlank() ? "shared" : owner;
        return inside(root, folder + "/" + requireSlug("The owner team", team) + "/" + requireSlug("The name", name) + ".yaml");
    }

    /** Names that become folders, file names and URLs: lowercase letters, digits and dashes, starting with a letter. */
    public static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]*$");

    /**
     * {@code value} when it is a slug; otherwise a 400 naming {@code what} ("The workflow name",
     * "The owner team"). Workflow and provider names and owner teams become paths in the content
     * repo, so nothing else may reach a file name.
     */
    public static String requireSlug(String what, String value) {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value + "' must be lowercase letters, digits and dashes, starting with a letter, like team-compute");
        }
        return value;
    }

    /** {@code relative} resolved under {@code root}; a path that would land outside it is refused. */
    public static Path inside(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base) || file.equals(base)) {
            throw new IllegalArgumentException("The path '" + relative + "' is outside the content repo");
        }
        return file;
    }
}
