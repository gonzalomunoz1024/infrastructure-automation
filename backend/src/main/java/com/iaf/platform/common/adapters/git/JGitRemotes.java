package com.iaf.platform.common.adapters.git;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.adapters.http.OutboundAddresses;
import com.iaf.platform.common.archives.PlaybookArchive;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.config.PlaybooksProperties;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Git remotes through JGit, in process (no git binary). Every HTTP connection, the first one and
 * every redirect JGit follows, goes through a guard: https only, the repository's own host and
 * port only (so a credential never travels to another host), and no address the
 * {@link OutboundAddresses} rules refuse. A checkout is a bare mirror per repository under
 * {@code <cache-dir>/mirrors}, fetched only when the commit asked for is missing, and the files of
 * each commit exported once under {@code <cache-dir>/trees/<repo key>-<commit>}. Links in a
 * checkout must resolve inside it.
 */
@Slf4j
@Component
public class JGitRemotes implements GitRemotePort {

    /** The user name sent with a bare token. */
    static final String TOKEN_USER = "x-access-token";

    private final CredentialsPort credentials;
    private final Duration timeout;
    private final Path cacheDir;
    private final boolean allowFileUrls;
    private final boolean allowHttp;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public JGitRemotes(PlaybooksProperties props, CredentialsPort credentials) {
        this(credentials, props.getGit().getTimeout(), Path.of(props.getGit().getCacheDir()), props.getGit().isAllowFileUrls(), false);
    }

    /** {@code allowHttp} lets tests talk plain http to a loopback server; production is https only. */
    JGitRemotes(CredentialsPort credentials, Duration timeout, Path cacheDir, boolean allowFileUrls, boolean allowHttp) {
        this.credentials = credentials;
        this.timeout = timeout;
        this.cacheDir = cacheDir;
        this.allowFileUrls = allowFileUrls;
        this.allowHttp = allowHttp;
    }

    @Override
    public Mono<Resolved> resolve(String repo, String ref, String credential) {
        return Mono.defer(() -> {
            Remote remote = remote(repo);
            String wanted = requireRef(ref);
            return secret(credential).flatMap(secret -> blocking(() -> lsRemote(remote, wanted, secret.orElse(null))))
                    .map(commit -> new Resolved(repo.trim(), wanted, commit));
        });
    }

    @Override
    public Mono<Path> checkout(String repo, String commit, String credential) {
        return Mono.defer(() -> {
            Remote remote = remote(repo);
            String id = commit == null ? "" : commit.trim().toLowerCase(Locale.ROOT);
            if (!COMMIT.matcher(id).matches()) {
                throw new IllegalArgumentException("A checkout needs a full commit id (40 hex digits); got '" + commit + "'");
            }
            return secret(credential).flatMap(secret -> blocking(() -> materialize(remote, id, secret.orElse(null))));
        });
    }

    // ---------------------------------------------------------------- remotes

    /** A checked repository URL: {@code url} is what JGit opens, {@code key} names its cache entries. */
    record Remote(String url, String scheme, String host, int port, String key) {
        boolean file() {
            return "file".equals(scheme);
        }
    }

    Remote remote(String repo) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required");
        }
        String r = repo.trim();
        if (r.matches("^[\\w.-]+@[^/:]+:.*")) {
            throw new IllegalArgumentException("Repository " + r + " is an ssh address; playbooks come from https remotes");
        }
        URI uri;
        try {
            uri = new URI(r.contains("://") ? r : "https://" + r);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Repository " + r + " is not a URL: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) {
            if (!allowFileUrls) {
                throw new IllegalArgumentException("file:// repositories are not allowed; use an https remote");
            }
            return new Remote(uri.toString(), scheme, null, -1, key(uri.toString()));
        }
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw new IllegalArgumentException("Repository " + r + " uses " + (scheme.isEmpty() ? "no scheme" : scheme)
                    + "; playbooks come from https remotes only");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("A repository URL cannot carry credentials; pass a credential=vault:<path> reference instead");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Repository " + r + " has no host");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Repository " + r + " has a query or a fragment");
        }
        OutboundAddresses.requireAllowed(uri);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        String url = scheme + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + (uri.getRawPath() == null ? "" : uri.getRawPath());
        return new Remote(url, scheme, host, port, key(url));
    }

    private static String requireRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref is required: a tag, a branch or a commit");
        }
        String r = ref.trim();
        if (r.startsWith("-") || r.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == ' ') || r.contains("..")) {
            throw new IllegalArgumentException("'" + ref + "' is not a git ref");
        }
        return r;
    }

    /** The secret behind a credential reference; an empty Optional without one. */
    private Mono<Optional<String>> secret(String credential) {
        if (credential == null || credential.isBlank()) {
            return Mono.just(Optional.empty());
        }
        String ref = credential.trim();
        if (!ref.startsWith("vault:")) {
            throw new IllegalArgumentException("credential is a vault:<path> reference");
        }
        return credentials.resolve(ref).map(Optional::of)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("The vault has no secret at " + ref)));
    }

    // ---------------------------------------------------------------- ls-remote

    String lsRemote(Remote remote, String ref, String secret) {
        String lower = ref.toLowerCase(Locale.ROOT);
        if (COMMIT.matcher(lower).matches()) {
            return lower;
        }
        Map<String, Ref> refs;
        if (remote.file()) {
            refs = call(remote, () -> configure(Git.lsRemoteRepository().setRemote(remote.url()), remote, secret).callAsMap());
        } else {
            // over http an empty in-memory repository: the dumb protocol needs a local one even to list refs
            try (Repository scratch = new InMemoryRepository(new DfsRepositoryDescription("ls-remote"))) {
                refs = call(remote, () -> configure(new LsRemoteCommand(scratch).setRemote(remote.url()), remote, secret).callAsMap());
            }
        }
        for (String name : candidates(ref)) {
            Ref r = refs.get(name);
            if (r != null) {
                ObjectId id = r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId();
                if (id != null) {
                    return id.name();
                }
            }
        }
        throw new NotFoundException("Ref", "'" + ref + "' of " + remote.url());
    }

    /** git's order: the full name, refs/&lt;ref&gt;, then tags before branches. */
    static List<String> candidates(String ref) {
        if (ref.equals("HEAD") || ref.startsWith("refs/")) {
            return List.of(ref);
        }
        return List.of("refs/" + ref, "refs/tags/" + ref, "refs/heads/" + ref);
    }

    // ---------------------------------------------------------------- checkout

    Path materialize(Remote remote, String commit, String secret) throws IOException {
        Path tree = cacheDir.resolve("trees").resolve(remote.key() + "-" + commit);
        if (Files.isDirectory(tree)) {
            return tree;
        }
        synchronized (locks.computeIfAbsent(remote.key(), k -> new Object())) {
            if (Files.isDirectory(tree)) {
                return tree;
            }
            Path mirror = cacheDir.resolve("mirrors").resolve(remote.key() + ".git");
            try (Repository db = mirror(mirror)) {
                ObjectId id = ObjectId.fromString(commit);
                if (!db.getObjectDatabase().has(id)) {
                    fetch(db, remote, secret, commit);
                }
                if (!db.getObjectDatabase().has(id)) {
                    throw new NotFoundException("Commit", commit + " of " + remote.url());
                }
                export(db, id, tree);
            }
            return tree;
        }
    }

    private static Repository mirror(Path dir) throws IOException {
        Repository db = FileRepositoryBuilder.create(dir.toFile());
        if (!Files.exists(dir.resolve("HEAD"))) {
            Files.createDirectories(dir.getParent());
            db.create(true);
        }
        return db;
    }

    private void fetch(Repository db, Remote remote, String secret, String commit) {
        try (Git git = new Git(db)) {
            call(remote, () -> configure(git.fetch().setRemote(remote.url())
                    .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"))
                    .setTagOpt(TagOpt.NO_TAGS), remote, secret).call());
            if (!db.getObjectDatabase().has(ObjectId.fromString(commit))) {
                // not on a branch or tag: servers that allow it hand out a commit by id
                try {
                    configure(git.fetch().setRemote(remote.url()).setRefSpecs(new RefSpec(commit + ":refs/pins/" + commit))
                            .setTagOpt(TagOpt.NO_TAGS), remote, secret).call();
                } catch (GitAPIException | RuntimeException e) {
                    log.debug("fetching {} by id from {} failed: {}", commit, remote.url(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Write the files of {@code id} to a fresh directory and move it into place. */
    private static void export(Repository db, ObjectId id, Path tree) throws IOException {
        Files.createDirectories(tree.getParent());
        Path tmp = Files.createTempDirectory(tree.getParent(), ".export-");
        try {
            List<Path> links = new ArrayList<>();
            try (RevWalk rw = new RevWalk(db); TreeWalk tw = new TreeWalk(db)) {
                RevCommit c;
                try {
                    c = rw.parseCommit(id);
                } catch (org.eclipse.jgit.errors.IncorrectObjectTypeException e) {
                    throw new IllegalArgumentException(id.name() + " is not a commit");
                }
                tw.addTree(c.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    String name = PlaybookArchive.safeName(tw.getPathString(), false);
                    Path out = tmp.resolve(name).normalize();
                    if (!out.startsWith(tmp)) {
                        throw new IllegalArgumentException("Path " + name + " would land outside the checkout");
                    }
                    FileMode mode = tw.getFileMode(0);
                    Files.createDirectories(out.getParent());
                    if (mode == FileMode.GITLINK) {
                        Files.createDirectories(out); // submodules are not fetched
                    } else if (mode == FileMode.SYMLINK) {
                        String target = new String(db.open(tw.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                        Files.createSymbolicLink(out, Path.of(target));
                        links.add(out);
                    } else {
                        try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                            db.open(tw.getObjectId(0)).copyTo(os);
                        }
                        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString(mode == FileMode.EXECUTABLE_FILE ? "rwxr-xr-x" : "rw-r--r--"));
                    }
                }
            }
            Path root = tmp.toRealPath();
            for (Path link : links) {
                Path real;
                try {
                    real = link.toRealPath();
                } catch (IOException e) {
                    throw new IllegalArgumentException("Link " + tmp.relativize(link) + " points nowhere inside the repository");
                }
                if (!real.startsWith(root)) {
                    throw new IllegalArgumentException("Link " + tmp.relativize(link) + " points outside the repository");
                }
            }
            try {
                Files.move(tmp, tree, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException | AtomicMoveNotSupportedException e) {
                if (!Files.isDirectory(tree)) {
                    throw e;
                }
            }
        } finally {
            deleteTree(tmp);
        }
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("could not delete {}: {}", dir, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- JGit plumbing

    @FunctionalInterface
    interface GitCall<T> {
        T call() throws GitAPIException;
    }

    /** Run a JGit command and turn its failures into the port's errors. */
    private static <T> T call(Remote remote, GitCall<T> call) {
        try {
            return call.call();
        } catch (InvalidRemoteException e) {
            throw new NotFoundException("Repository", remote.url());
        } catch (org.eclipse.jgit.api.errors.TransportException e) {
            if (e.getCause() instanceof NoRemoteRepositoryException) {
                throw new NotFoundException("Repository", remote.url());
            }
            throw new RemoteUnavailable("Could not read " + remote.url() + ": " + reason(e));
        } catch (GitAPIException e) {
            throw new RemoteUnavailable("Could not read " + remote.url() + ": " + reason(e));
        }
    }

    private static String reason(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }

    private <C extends TransportCommand<C, ?>> C configure(C command, Remote remote, String secret) {
        command.setTimeout((int) Math.max(1, timeout.toSeconds()));
        command.setTransportConfigCallback(transport -> {
            if (transport instanceof TransportHttp http) {
                http.setHttpConnectionFactory(new GuardedConnections(remote, allowHttp));
            } else if (!remote.file()) {
                throw new IllegalStateException("Only https remotes are read; got " + transport.getClass().getSimpleName());
            }
        });
        if (secret != null) {
            command.setCredentialsProvider(provider(secret));
        }
        return command;
    }

    /** {@code <user>:<password or token>}, or a bare token sent as {@value #TOKEN_USER}. */
    static CredentialsProvider provider(String secret) {
        int colon = secret.indexOf(':');
        return colon > 0 ? new UsernamePasswordCredentialsProvider(secret.substring(0, colon), secret.substring(colon + 1))
                : new UsernamePasswordCredentialsProvider(TOKEN_USER, secret);
    }

    /** JDK connections that refuse other schemes, other hosts (redirects) and refused addresses. */
    static final class GuardedConnections implements HttpConnectionFactory2 {
        private final JDKHttpConnectionFactory delegate = new JDKHttpConnectionFactory();
        private final Remote remote;
        private final boolean allowHttp;

        GuardedConnections(Remote remote, boolean allowHttp) {
            this.remote = remote;
            this.allowHttp = allowHttp;
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            check(url, null);
            return delegate.create(url);
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            check(url, proxy);
            return delegate.create(url, proxy);
        }

        @Override
        public GitSession newSession() {
            return delegate.newSession();
        }

        void check(URL url, Proxy proxy) throws IOException {
            String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
                throw new IOException("only https is followed, not " + scheme);
            }
            int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!scheme.equals(remote.scheme()) || !host.equals(remote.host()) || port != remote.port()) {
                throw new IOException("the remote redirected to " + scheme + "://" + host + ":" + port + "; only "
                        + remote.scheme() + "://" + remote.host() + ":" + remote.port() + " is followed");
            }
            try {
                OutboundAddresses.requireAllowed(url.toURI());
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new IOException(e.getMessage());
            }
            if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
                // the JDK caches this answer, so the connection that follows dials the address checked here
                OutboundAddresses.resolveAllowed(host);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(() -> {
            try {
                return work.call();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 24 hex digits of the URL's SHA-256: the cache name of a repository. */
    static String key(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
