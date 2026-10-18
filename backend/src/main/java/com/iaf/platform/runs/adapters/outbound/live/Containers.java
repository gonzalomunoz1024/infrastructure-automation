package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One step in a container ({@code docker} or {@code podman}): {@code run --rm} with the step's
 * directories mounted, the process user of the platform (so it can write what the step leaves
 * behind), no new privileges and every kernel privilege dropped ({@code --cap-drop ALL}). Plain
 * settings go on the command line ({@code -e NAME=value}); secrets are handed over by name
 * ({@code -e NAME}) so no secret ever shows on a command line: the CLI gets their values in its
 * own environment, next to what it needs to reach its daemon.
 */
final class Containers {

    /** What the runtime CLI may read from the platform's environment to find its daemon and registries. */
    static final List<String> CLI_ENV = List.of("DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "DOCKER_CONTEXT",
            "CONTAINER_HOST", "CONTAINERS_CONF", "CONTAINERS_REGISTRIES_CONF", "REGISTRY_AUTH_FILE", "XDG_RUNTIME_DIR", "HOME");

    private Containers() {
    }

    record Mount(Path host, String container, boolean readOnly) {
    }

    /** A container to start; {@code network} is a network name, blank for the runtime's default, {@code none} for none. */
    static final class Box {
        final String runtime;
        final String image;
        final String name;
        final List<Mount> mounts = new ArrayList<>();
        /** Plain settings: on the command line. */
        final Map<String, String> env = new LinkedHashMap<>();
        /** Secrets (and anything that may hold one, like a proxy URL): by name only. */
        final Map<String, String> secretEnv = new LinkedHashMap<>();
        final List<String> tmpfs = new ArrayList<>();
        String workdir;
        String network = "";
        String user;
        boolean readOnly;
        String memory;
        String cpus;
        Integer pids;

        Box(String runtime, String image, String jobId, int seq) {
            this.runtime = check(runtime);
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("a container step needs an image");
            }
            this.image = image.trim();
            this.name = "iaf-" + slug(jobId) + "-" + seq + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        Box mount(Path host, String container, boolean ro) {
            mounts.add(new Mount(host, container, ro));
            return this;
        }
    }

    /** {@code <runtime> run ...} for {@code command} inside the box; the values of {@code box.secretEnv} go to the CLI's environment. */
    static List<String> run(Path runtimeExecutable, Box box, List<String> command) {
        List<String> argv = new ArrayList<>();
        argv.add(runtimeExecutable.toString());
        argv.add("run");
        argv.add("--rm");
        argv.add("-i");
        argv.add("--name");
        argv.add(box.name);
        argv.add("--security-opt");
        argv.add("no-new-privileges");
        argv.add("--cap-drop");
        argv.add("ALL");
        if (box.user != null) {
            argv.add("--user");
            argv.add(box.user);
        }
        if (box.network != null && !box.network.isBlank()) {
            argv.add("--network");
            argv.add(box.network);
        }
        if (box.readOnly) {
            argv.add("--read-only");
        }
        if (box.memory != null && !box.memory.isBlank()) {
            argv.add("--memory");
            argv.add(box.memory);
        }
        if (box.cpus != null && !box.cpus.isBlank()) {
            argv.add("--cpus");
            argv.add(box.cpus);
        }
        if (box.pids != null) {
            argv.add("--pids-limit");
            argv.add(Integer.toString(box.pids));
        }
        for (String t : box.tmpfs) {
            argv.add("--tmpfs");
            argv.add(t);
        }
        for (Mount m : box.mounts) {
            argv.add("-v");
            argv.add(m.host().toAbsolutePath() + ":" + m.container() + (m.readOnly() ? ":ro" : ""));
        }
        if (box.workdir != null) {
            argv.add("-w");
            argv.add(box.workdir);
        }
        box.env.forEach((name, value) -> {
            argv.add("-e");
            argv.add(name + "=" + value);
        });
        for (String name : box.secretEnv.keySet()) {
            argv.add("-e");
            argv.add(name);
        }
        argv.add(box.image);
        argv.addAll(command);
        return argv;
    }

    /** The environment of the CLI: what it needs from the platform's, a PATH, and the secrets the container gets by name. */
    static Map<String, String> cliEnv(Box box, String path) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : CLI_ENV) {
            String v = System.getenv(name);
            if (v != null) {
                env.put(name, v);
            }
        }
        env.put("PATH", path);
        env.putAll(box.secretEnv);
        return env;
    }

    /** {@code <runtime> rm -f <name>}: run when the step is ended early, since killing the CLI leaves the container running. */
    static List<String> remove(Path runtimeExecutable, Box box) {
        return List.of(runtimeExecutable.toString(), "rm", "-f", box.name);
    }

    /** {@code uid:gid} of a directory the platform created: the user the container runs as. */
    static String owner(Path dir) {
        try {
            Object uid = Files.getAttribute(dir, "unix:uid");
            Object gid = Files.getAttribute(dir, "unix:gid");
            return uid + ":" + gid;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    static String check(String runtime) {
        String r = runtime == null ? "" : runtime.trim().toLowerCase(Locale.ROOT);
        String base = r.contains("/") ? r.substring(r.lastIndexOf('/') + 1) : r;
        if (!base.equals("docker") && !base.equals("podman")) {
            throw new IllegalArgumentException("platform.runners.container-runtime is docker or podman, not '" + runtime + "'");
        }
        return runtime.trim();
    }

    private static String slug(String s) {
        String v = s == null ? "job" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return v.length() > 40 ? v.substring(0, 40) : v;
    }
}
