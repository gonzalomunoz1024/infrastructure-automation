package com.iaf.platform.workflows.adapters.outbound;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.VersionRules;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The content repo as the store, through JGit. Published versions are the files on the main
 * branch; a draft or candidate is the file on its branch {@code change/<name>-<version>}; a merge
 * is a commit on main (a merge commit when the branch exists); retired versions stay readable
 * from main's history. Every version is read from a git ref into an in-memory index at startup
 * and after each write, so reads never touch git. Writes are in-core commits (no checkout); when
 * the written branch is the one checked out, the working tree and index are refreshed too.
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "platform.content.git", havingValue = "true")
public class GitWorkflowStore implements WorkflowStorePort {

    private static final String HEADS = "refs/heads/";
    private static final String WORKFLOWS_DIR = "workflows";
    private static final int MAX_HISTORY = 500;

    private final Path dir;
    private final Clock clock;
    /** name -> version -> record */
    private final Map<String, Map<String, WorkflowRecord>> index = new ConcurrentHashMap<>();
    private Repository repo;
    private String mainBranch = "main";

    @Autowired
    public GitWorkflowStore(PlatformProperties props, Clock clock) {
        this(Path.of(props.getContent().getDir()), clock);
    }

    public GitWorkflowStore(Path dir, Clock clock) {
        this.dir = dir.toAbsolutePath().normalize();
        this.clock = clock;
    }

    @PostConstruct
    public synchronized void init() {
        try {
            if (!Files.exists(dir.resolve(".git"))) {
                try (Git g = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
                    log.info("initialised a git repository in {}", dir);
                }
            }
            repo = Git.open(dir.toFile()).getRepository();
            String full = repo.getFullBranch();
            if (full != null && full.startsWith(HEADS)) {
                mainBranch = full.substring(HEADS.length());
            }
            importUncommittedContent();
            reindex();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("Cannot open the content repository at " + dir, e);
        }
    }

    @PreDestroy
    public void close() {
        if (repo != null) {
            repo.close();
        }
    }

    public String mainBranch() {
        return mainBranch;
    }

    /** The file at a path on a branch, read from git; empty when either is unknown. */
    public synchronized Optional<String> fileAt(String branch, String path) {
        try {
            ObjectId tip = repo.resolve(HEADS + branch);
            return Optional.ofNullable(tip == null ? null : read(tip, path));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path + " on " + branch, e);
        }
    }

    // ------------------------------------------------------------------ reads (from the index)

    @Override
    public List<WorkflowRecord> all() {
        List<WorkflowRecord> out = new ArrayList<>();
        index.keySet().stream().sorted().forEach(n -> out.addAll(versions(n)));
        return out;
    }

    @Override
    public List<WorkflowRecord> versions(String name) {
        Map<String, WorkflowRecord> m = index.get(name);
        return m == null ? List.of() : m.values().stream().sorted((a, b) -> Semver.ORDER.compare(a.version(), b.version())).toList();
    }

    @Override
    public Optional<WorkflowRecord> get(String name, String version) {
        Map<String, WorkflowRecord> m = index.get(name);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(version));
    }

    @Override
    public Optional<WorkflowRecord> published(String name) {
        return versions(name).stream().filter(WorkflowRecord::isPublished).max((a, b) -> Semver.ORDER.compare(a.version(), b.version()));
    }

    @Override
    public Optional<WorkflowRecord> draft(String name) {
        return versions(name).stream().filter(r -> r.status() == WorkflowStatus.DRAFT).max((a, b) -> Semver.ORDER.compare(a.version(), b.version()));
    }

    // ------------------------------------------------------------------ writes (git, then the index)

    @Override
    public synchronized void save(WorkflowRecord record) {
        String path = record.path() == null ? WORKFLOWS_DIR + "/" + ownerOf(record) + "/" + record.name() + ".yaml" : record.path();
        WorkflowRecord stored = withPath(record, path);
        try {
            switch (record.status()) {
                case DRAFT, CANDIDATE -> saveOnBranch(stored);
                case PUBLISHED -> publishOnMain(stored);
                case RETIRED -> {
                    // the file on main was replaced by the newer version; the content stays in history
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + record.name() + " " + record.version() + " to git", e);
        }
        index.computeIfAbsent(record.name(), n -> new ConcurrentHashMap<>()).put(record.version(), stored);
    }

    @Override
    public synchronized void delete(String name, String version) {
        Optional<WorkflowRecord> existing = get(name, version);
        if (existing.isEmpty()) {
            return;
        }
        WorkflowRecord r = existing.get();
        try {
            String branch = VersionRules.branch(name, version);
            if (repo.resolve(HEADS + branch) != null) {
                deleteBranch(branch);
            }
            if (r.status() == WorkflowStatus.PUBLISHED) {
                ObjectId mainTip = repo.resolve(HEADS + mainBranch);
                if (mainTip != null && read(mainTip, r.path()) != null) {
                    commit(mainBranch, mainTip, List.of(mainTip), r.path(), null, "Delete " + name + " " + version, "platform", clock.instant());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + name + " " + version + " from git", e);
        }
        Map<String, WorkflowRecord> m = index.get(name);
        if (m != null) {
            m.remove(version);
            if (m.isEmpty()) {
                index.remove(name);
            }
        }
    }

    private void saveOnBranch(WorkflowRecord record) throws IOException {
        String branch = VersionRules.branch(record.name(), record.version());
        ObjectId branchTip = repo.resolve(HEADS + branch);
        String current = branchTip == null ? null : read(branchTip, record.path());
        if (current != null && current.equals(record.yaml())) {
            return; // a status change only (draft -> candidate and back): nothing new to commit
        }
        ObjectId parent = branchTip != null ? branchTip : repo.resolve(HEADS + mainBranch);
        String message = (branchTip == null ? "Draft " : "Update draft ") + record.name() + " " + record.version();
        commit(branch, branchTip, parent == null ? List.of() : List.of(parent), record.path(), record.yaml(), message, record.updatedBy(), record.updatedAt());
    }

    private void publishOnMain(WorkflowRecord record) throws IOException {
        String branch = VersionRules.branch(record.name(), record.version());
        ObjectId branchTip = repo.resolve(HEADS + branch);
        ObjectId mainTip = repo.resolve(HEADS + mainBranch);
        String onMain = mainTip == null ? null : read(mainTip, record.path());
        if (onMain == null || !onMain.equals(record.yaml())) {
            List<ObjectId> parents = new ArrayList<>();
            if (mainTip != null) {
                parents.add(mainTip);
            }
            if (branchTip != null && !branchTip.equals(mainTip)) {
                parents.add(branchTip);
            }
            commit(mainBranch, mainTip, parents, record.path(), record.yaml(), "Publish " + record.name() + " " + record.version(),
                    record.publishedBy() == null ? record.updatedBy() : record.publishedBy(), record.publishedAt() == null ? record.updatedAt() : record.publishedAt());
        }
        if (branchTip != null) {
            deleteBranch(branch);
        }
    }

    /**
     * An in-core commit of one file (or its deletion when {@code content} is null) on a branch.
     *
     * @param expectedTip the branch's current tip (null when the branch does not exist yet)
     * @param parents     the commit's parents: the branch tip, or main's tip for a new branch, plus the merged branch
     */
    private ObjectId commit(String branch, ObjectId expectedTip, List<ObjectId> parents, String path, String content, String message, String by, Instant at)
            throws IOException {
        ObjectId parentTree = null;
        if (!parents.isEmpty()) {
            try (RevWalk rw = new RevWalk(repo)) {
                parentTree = rw.parseCommit(parents.get(0)).getTree().getId();
            }
        }
        byte[] bytes = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
        ObjectId commitId;
        try (ObjectInserter ins = repo.newObjectInserter(); ObjectReader reader = repo.newObjectReader()) {
            DirCache dc = DirCache.newInCore();
            DirCacheBuilder builder = dc.builder();
            if (parentTree != null) {
                builder.addTree(new byte[0], DirCacheEntry.STAGE_0, reader, parentTree);
            }
            builder.finish();
            DirCacheEditor editor = dc.editor();
            if (bytes == null) {
                editor.add(new DirCacheEditor.DeletePath(path));
            } else {
                ObjectId blob = ins.insert(Constants.OBJ_BLOB, bytes);
                editor.add(new DirCacheEditor.PathEdit(path) {
                    @Override
                    public void apply(DirCacheEntry entry) {
                        entry.setFileMode(FileMode.REGULAR_FILE);
                        entry.setObjectId(blob);
                        entry.setLength(bytes.length);
                    }
                });
            }
            editor.finish();
            ObjectId tree = dc.writeTree(ins);
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(tree);
            cb.setParentIds(parents);
            String who = by == null || by.isBlank() ? "platform" : by;
            PersonIdent ident = new PersonIdent(who, who + "@platform.local", at == null ? clock.instant() : at, ZoneOffset.UTC);
            cb.setAuthor(ident);
            cb.setCommitter(ident);
            cb.setMessage(message);
            commitId = ins.insert(cb);
            ins.flush();
        }
        RefUpdate ru = repo.updateRef(HEADS + branch);
        ru.setNewObjectId(commitId);
        ru.setExpectedOldObjectId(expectedTip == null ? ObjectId.zeroId() : expectedTip);
        ru.setRefLogMessage(message, false);
        RefUpdate.Result result = ru.update();
        switch (result) {
            case NEW, FAST_FORWARD, FORCED, NO_CHANGE -> {
            }
            default -> throw new IOException("ref update of " + branch + " failed: " + result);
        }
        syncWorkTree(branch, path, bytes);
        return commitId;
    }

    private void deleteBranch(String branch) throws IOException {
        RefUpdate ru = repo.updateRef(HEADS + branch);
        ru.setForceUpdate(true);
        RefUpdate.Result result = ru.delete();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NO_CHANGE
                && result != RefUpdate.Result.NEW) {
            log.warn("deleting branch {}: {}", branch, result);
        }
    }

    /** When the written branch is checked out, make the working tree and index agree with the new HEAD. */
    private void syncWorkTree(String branch, String path, byte[] bytes) {
        try {
            if (repo.isBare() || !branch.equals(repo.getBranch())) {
                return;
            }
            Path file = dir.resolve(path);
            try (Git git = new Git(repo)) {
                if (bytes == null) {
                    Files.deleteIfExists(file);
                    git.rm().addFilepattern(path).call();
                } else {
                    Files.createDirectories(file.getParent());
                    Files.write(file, bytes);
                    git.add().addFilepattern(path).call();
                }
            }
        } catch (IOException | GitAPIException | RuntimeException e) {
            log.warn("working tree of {} not refreshed for {}: {}", dir, path, e.toString());
        }
    }

    // ------------------------------------------------------------------ the index

    /** A content dir that was never committed (fresh repository): commit its config-as-code folders once. */
    private void importUncommittedContent() throws IOException, GitAPIException {
        if (repo.resolve(HEADS + mainBranch) != null) {
            return;
        }
        boolean any = false;
        try (Git git = new Git(repo)) {
            for (String folder : ContentFiles.FOLDERS) {
                if (Files.isDirectory(dir.resolve(folder))) {
                    git.add().addFilepattern(folder).call();
                    any = true;
                }
            }
            if (any) {
                PersonIdent ident = new PersonIdent("platform", "platform@platform.local", clock.instant(), ZoneOffset.UTC);
                git.commit().setMessage("Import content").setAuthor(ident).setCommitter(ident).setSign(false).call();
                log.info("committed the content of {} on {}", dir, mainBranch);
            }
        }
    }

    private record Parsed(WorkflowDefinition definition) {
    }

    private void reindex() throws IOException {
        index.clear();
        Map<ObjectId, Optional<Parsed>> cache = new HashMap<>();
        ObjectId mainTip = repo.resolve(HEADS + mainBranch);
        if (mainTip != null) {
            try (RevWalk rw = new RevWalk(repo)) {
                RevCommit head = rw.parseCommit(mainTip);
                for (var e : workflowFiles(head.getTree().getId())) {
                    parse(e.getKey(), e.getValue(), cache).ifPresent(p -> put(record(p, WorkflowStatus.PUBLISHED, e.getKey(), head)));
                }
                rw.reset();
                rw.markStart(head);
                int n = 0;
                for (RevCommit c : rw) {
                    if (n++ > MAX_HISTORY) {
                        break;
                    }
                    if (c.getId().equals(head.getId())) {
                        continue;
                    }
                    for (var e : workflowFiles(c.getTree().getId())) {
                        parse(e.getKey(), e.getValue(), cache)
                                .filter(p -> get(p.definition().name(), p.definition().version()).isEmpty())
                                .ifPresent(p -> put(record(p, WorkflowStatus.RETIRED, e.getKey(), c)));
                    }
                }
            }
        }
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix(HEADS + "change/")) {
            String branch = ref.getName().substring(HEADS.length());
            try (RevWalk rw = new RevWalk(repo)) {
                RevCommit tip = rw.parseCommit(ref.getObjectId());
                for (var e : workflowFiles(tip.getTree().getId())) {
                    parse(e.getKey(), e.getValue(), cache)
                            .filter(p -> branch.equals(VersionRules.branch(p.definition().name(), p.definition().version())))
                            .filter(p -> get(p.definition().name(), p.definition().version()).isEmpty())
                            .ifPresent(p -> put(record(p, WorkflowStatus.DRAFT, e.getKey(), tip)));
                }
            }
        }
        log.info("indexed {} workflow version(s) from git at {}", index.values().stream().mapToInt(Map::size).sum(), dir);
    }

    private WorkflowRecord record(Parsed p, WorkflowStatus status, String path, RevCommit commit) {
        Instant at = Instant.ofEpochSecond(commit.getCommitTime());
        String by = commit.getAuthorIdent() == null ? "git" : commit.getAuthorIdent().getName();
        WorkflowRecord r = WorkflowRecord.of(p.definition(), status, path, at, by);
        return status == WorkflowStatus.PUBLISHED ? r.published(at, by, null) : r;
    }

    private void put(WorkflowRecord r) {
        index.computeIfAbsent(r.name(), n -> new ConcurrentHashMap<>()).put(r.version(), r);
    }

    private Optional<Parsed> parse(String path, ObjectId blob, Map<ObjectId, Optional<Parsed>> cache) {
        return cache.computeIfAbsent(blob, id -> {
            try {
                String yaml = new String(repo.open(id).getBytes(), StandardCharsets.UTF_8);
                if (!WorkflowDefinition.KIND.equals(WorkflowYaml.kindOf(yaml))) {
                    return Optional.empty();
                }
                return Optional.of(new Parsed(WorkflowYaml.parse(yaml)));
            } catch (IOException | IllegalArgumentException e) {
                log.warn("skipping {} ({}): {}", path, id.name(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** path -> blob of every yaml file under workflows/ in a tree. */
    private List<Map.Entry<String, ObjectId>> workflowFiles(ObjectId tree) throws IOException {
        List<Map.Entry<String, ObjectId>> out = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(WORKFLOWS_DIR));
            while (tw.next()) {
                String path = tw.getPathString();
                if (path.endsWith(".yaml") || path.endsWith(".yml")) {
                    out.add(Map.entry(path, tw.getObjectId(0)));
                }
            }
        }
        return out;
    }

    private String read(ObjectId commit, String path) throws IOException {
        try (RevWalk rw = new RevWalk(repo); TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(rw.parseCommit(commit).getTree());
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(path));
            while (tw.next()) {
                if (tw.getPathString().equals(path)) {
                    return new String(repo.open(tw.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static String ownerOf(WorkflowRecord r) {
        String owner = r.definition().metadata().owner();
        return owner == null || owner.isBlank() ? "shared" : owner;
    }

    private static WorkflowRecord withPath(WorkflowRecord r, String path) {
        if (path.equals(r.path())) {
            return r;
        }
        return new WorkflowRecord(r.name(), r.version(), r.status(), r.yaml(), r.definition(), r.contentHash(), path, r.updatedAt(), r.updatedBy(),
                r.publishedAt(), r.publishedBy(), r.releaseId(), r.notes());
    }
}
