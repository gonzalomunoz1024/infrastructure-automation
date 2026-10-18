package com.iaf.platform.providers.adapters.outbound;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.http.OutboundAddresses;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.RepoPaths;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link GitSourcePort} through JGit: the branches and tags of the repository are fetched into a
 * throw-away bare repository, the ref is resolved to its commit, and the wanted files are read
 * from that commit's tree (nothing is checked out, symlinks and submodules are skipped, so a
 * repository cannot point the platform at its own disk). Only https repositories are read, with
 * the same address guard as provider calls; {@code platform.providers.git-allow-local} lets tests
 * read {@code file:} repositories. A token travels as the password of the fetch and nowhere else.
 */
@Slf4j
@Component
public class JGitSource implements GitSourcePort {

    static final int MAX_FILES = 2000;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    /** What one read keeps in memory in all: 2000 files of 1 MB would be 2 GB. */
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final String TOKEN_USER = "x-access-token";

    private final CredentialsPort credentials;
    private final boolean allowLocal;
    private final Duration timeout;
    private final long maxTotalBytes;

    @Autowired
    public JGitSource(CredentialsPort credentials, PlatformProperties props) {
        this(credentials, props.getProviders().isGitAllowLocal(), props.getProviders().getGitTimeout());
    }

    public JGitSource(CredentialsPort credentials, boolean allowLocal, Duration timeout) {
        this(credentials, allowLocal, timeout, MAX_TOTAL_BYTES);
    }

    JGitSource(CredentialsPort credentials, boolean allowLocal, Duration timeout, long maxTotalBytes) {
        this.credentials = credentials;
        this.allowLocal = allowLocal;
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.maxTotalBytes = maxTotalBytes;
    }

    @Override
    public Mono<Snapshot> read(String repo, String ref, String dir, Predicate<String> wanted, String credential) {
        return Mono.defer(() -> {
            String uri = uriOf(repo);
            String at = ref == null || ref.isBlank() ? null : ref.trim();
            String under = RepoPaths.trimSlashes(dir);
            if (under.contains("..")) {
                throw new IllegalArgumentException("path must stay inside the repository: " + dir);
            }
            Mono<String> token = credential == null || credential.isBlank() ? Mono.just("")
                    : credentials.resolve(credential.trim()).switchIfEmpty(Mono.error(() -> new IllegalArgumentException("No secret at " + credential.trim())));
            return token.flatMap(t -> Mono.fromCallable(() -> fetch(uri, display(repo), at, under, wanted == null ? p -> true : wanted, t))
                    .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    private Snapshot fetch(String uri, String shown, String ref, String dir, Predicate<String> wanted, String token) throws IOException {
        requireResolvable(uri);
        Path tmp = Files.createTempDirectory("iaf-git-");
        try (Git git = Git.init().setBare(true).setDirectory(tmp.toFile()).call()) {
            // a redirect could lead anywhere; a repository that moved is read at its new URL
            git.getRepository().getConfig().setString("http", null, "followRedirects", "false");
            // no background gc on a repository that is deleted right after the read
            git.getRepository().getConfig().setInt("gc", null, "auto", 0);
            git.getRepository().getConfig().setBoolean("gc", null, "autoDetach", false);
            FetchCommand fetch = git.fetch().setRemote(uri)
                    .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"))
                    .setTagOpt(TagOpt.NO_TAGS)
                    .setTimeout((int) Math.max(1, timeout.toSeconds()));
            if (!token.isEmpty()) {
                fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider(TOKEN_USER, token));
            }
            fetch.call();
            Repository repository = git.getRepository();
            String wantedRef = ref == null ? defaultBranch(repository) : ref;
            ObjectId id = resolve(repository, wantedRef);
            if (id == null) {
                throw new IllegalArgumentException("No branch, tag or commit '" + wantedRef + "' in " + shown);
            }
            try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
                RevCommit commit = walk.parseCommit(id);
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                if (!dir.isEmpty()) {
                    tree.setFilter(PathFilter.create(dir));
                }
                Map<String, String> files = new TreeMap<>();
                List<String> skipped = new ArrayList<>();
                long total = 0;
                boolean full = false;
                while (tree.next()) {
                    String path = tree.getPathString();
                    if (!wanted.test(path)) {
                        continue;
                    }
                    FileMode mode = tree.getFileMode(0);
                    if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                        skipped.add(path + " (not a regular file)");
                        continue;
                    }
                    if (files.size() >= MAX_FILES) {
                        skipped.add(path + " (more than " + MAX_FILES + " files)");
                        continue;
                    }
                    ObjectLoader loader = repository.open(tree.getObjectId(0));
                    if (loader.getSize() > MAX_FILE_BYTES) {
                        skipped.add(path + " (larger than 1 MB)");
                        continue;
                    }
                    if (full || total + loader.getSize() > maxTotalBytes) {
                        full = true;
                        skipped.add(path + " (more than " + (maxTotalBytes >= 1 << 20 ? (maxTotalBytes >> 20) + " MB" : maxTotalBytes + " bytes") + " of files)");
                        continue;
                    }
                    total += loader.getSize();
                    byte[] bytes = loader.getBytes();
                    if (binary(bytes)) {
                        skipped.add(path + " (binary)");
                        continue;
                    }
                    files.put(path, new String(bytes, StandardCharsets.UTF_8));
                }
                log.info("read {} file(s) of {} at {} ({})", files.size(), shown, wantedRef, commit.name());
                return new Snapshot(shown, wantedRef, commit.name(), files, skipped);
            }
        } catch (GitAPIException e) {
            throw new IllegalArgumentException("Could not read " + shown + (ref == null ? "" : " at " + ref) + ": " + reason(e), e);
        } finally {
            delete(tmp);
        }
    }

    /** The branch a ref-less read uses: main, else master, else the first branch. */
    private static String defaultBranch(Repository repository) throws IOException {
        for (String b : List.of("main", "master")) {
            if (repository.findRef("refs/heads/" + b) != null) {
                return b;
            }
        }
        return repository.getRefDatabase().getRefsByPrefix("refs/heads/").stream().findFirst()
                .map(r -> r.getName().substring("refs/heads/".length())).orElse("main");
    }

    private static ObjectId resolve(Repository repository, String ref) throws IOException {
        for (String candidate : List.of("refs/heads/" + ref, "refs/tags/" + ref, ref)) {
            try {
                ObjectId id = repository.resolve(candidate + "^{commit}");
                if (id != null) {
                    return id;
                }
            } catch (org.eclipse.jgit.errors.RevisionSyntaxException | org.eclipse.jgit.errors.AmbiguousObjectException
                    | org.eclipse.jgit.errors.IncorrectObjectTypeException | org.eclipse.jgit.errors.MissingObjectException e) {
                // not this spelling; try the next one
            }
        }
        return null;
    }

    /**
     * The URL JGit fetches: https (a bare {@code host/path} gets https://), checked against the
     * address guard; a file: URL or an absolute path only when local repositories are allowed.
     * Credentials in the URL are refused: they belong in Vault.
     */
    String uriOf(String repo) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required: the repository's https URL, like https://github.com/acme/ops-playbooks");
        }
        String r = repo.trim();
        if (r.startsWith("/") || r.startsWith("file:")) {
            if (!allowLocal) {
                throw new IllegalArgumentException("Repositories on the platform's own disk cannot be read; use an https URL");
            }
            return r;
        }
        if (!SCHEME.matcher(r).find()) {
            if (r.startsWith("git@")) {
                throw new IllegalArgumentException("Use the repository's https URL; ssh is not supported: " + r);
            }
            r = "https://" + r;
        }
        URI uri;
        try {
            uri = URI.create(r);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a repository URL: " + display(repo));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Use the repository's https URL; " + display(repo) + " is not one");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Credentials never go in the repository URL; put the token in Vault and pass credential: vault:<path>");
        }
        OutboundAddresses.requireAllowed(uri);
        return r;
    }

    /** Every address the repository's host resolves to passes the guard provider calls use. */
    static void requireResolvable(String uri) {
        if (!uri.regionMatches(true, 0, "https:", 0, 6)) {
            return;
        }
        String host = URI.create(uri).getHost();
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (OutboundAddresses.blocked(address)) {
                    throw new IllegalArgumentException(host + " resolves to a link-local or cloud metadata address; repositories cannot live there");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unknown host " + host);
        }
    }

    /** The repository as messages show it: without any user info. */
    static String display(String repo) {
        if (repo == null) {
            return "";
        }
        return repo.trim().replaceAll("//[^/@]*@", "//");
    }

    private static boolean binary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String reason(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage() == null || t.getMessage().isBlank() ? e.getMessage() : t.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // a leftover temp file is harmless
                }
            });
        } catch (IOException e) {
            log.debug("could not clean {}: {}", dir, e.toString());
        }
    }
}
