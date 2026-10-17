package com.iaf.platform.common.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        return root.resolve(folder).resolve(team).resolve(name + ".yaml");
    }
}
