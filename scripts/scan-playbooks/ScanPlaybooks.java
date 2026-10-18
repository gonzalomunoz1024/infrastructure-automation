import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.iaf.platform.playbooks.domain.SecretScan;
import com.iaf.platform.playbooks.domain.SecretScan.Finding;

/**
 * The upload scan rule, in CI. {@code POST /api/v1/playbooks:upload} refuses an upload when
 * {@link SecretScan} finds what looks like a secret in one of its text files; this runs the same
 * rule, from the backend's classes, over the playbooks the repository keeps itself: every file
 * under a {@code playbooks/} directory at the root or anywhere under {@code workflows/} and
 * {@code providers/}. The content pins its playbooks in Git or uploads them, so there may be
 * none. A fixture runs first either way and must be flagged on exactly the lines marked
 * {@value #MARK}, so the job shows that the rule still catches what it must and lets the rest
 * through.
 *
 * <pre>
 * (cd backend &amp;&amp; mvn -B -q compile)
 * java -cp backend/target/classes scripts/scan-playbooks/ScanPlaybooks.java [repository root]
 * </pre>
 *
 * A finding names the file, the line and what was found, never the value. The exit code is 1
 * for a finding in the repository's playbooks or a fixture not flagged as marked.
 */
public class ScanPlaybooks {

    static final String MARK = "# scan: flagged";
    static final String FIXTURE = "scripts/scan-playbooks/fixture";

    /** A line of a file. */
    record Where(String file, int line) implements Comparable<Where> {
        @Override
        public int compareTo(Where o) {
            int c = file.compareTo(o.file);
            return c != 0 ? c : Integer.compare(line, o.line);
        }
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        boolean annotate = "true".equals(System.getenv("GITHUB_ACTIONS"));

        int failures = fixture(root, annotate);

        List<Path> local = localPlaybooks(root);
        int findings = 0;
        for (Path file : local) {
            for (Finding f : scan(root, file)) {
                error(annotate, new Where(f.file(), f.line()), f.what());
                findings++;
            }
        }
        if (local.isEmpty()) {
            System.out.println("The repository keeps no playbook files of its own (under a playbooks/ directory at the root,"
                    + " in workflows/ or in providers/)" + (Files.isDirectory(root.resolve(FIXTURE)) ? ": the fixture alone ran the rule." : "."));
        } else {
            System.out.println("Scanned " + plural(local.size(), "playbook file") + " of the repository: " + plural(findings, "finding") + ".");
        }
        System.exit(failures + findings > 0 ? 1 : 0);
    }

    /** The fixture is flagged on exactly its marked lines; the number of lines that are not. */
    static int fixture(Path root, boolean annotate) throws IOException {
        Path dir = root.resolve(FIXTURE);
        if (!Files.isDirectory(dir)) {
            error(annotate, new Where(FIXTURE, 0), "the fixture is missing");
            return 1;
        }
        Set<Where> marked = new TreeSet<>();
        Set<Where> flagged = new TreeSet<>();
        for (Path file : files(dir)) {
            String name = relative(root, file);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(MARK)) {
                    marked.add(new Where(name, i + 1));
                }
            }
            for (Finding f : scan(root, file)) {
                flagged.add(new Where(f.file(), f.line()));
            }
        }
        int failures = 0;
        for (Where w : marked) {
            if (!flagged.contains(w)) {
                error(annotate, w, "the upload scan rule no longer flags this line of the fixture");
                failures++;
            }
        }
        for (Where w : flagged) {
            if (!marked.contains(w)) {
                error(annotate, w, "the upload scan rule flags this line of the fixture, which is not marked '" + MARK + "'");
                failures++;
            }
        }
        System.out.println("Fixture: " + plural(marked.size(), "marked line") + ", " + plural(flagged.size(), "flagged line")
                + (failures == 0 ? ", as marked." : ": " + plural(failures, "mismatch", "mismatches") + "."));
        return failures;
    }

    /** What the rule finds in a file, as an upload would have it; a file too big to be text has nothing. */
    static List<Finding> scan(Path root, Path file) throws IOException {
        if (Files.size(file) > SecretScan.MAX_SCANNED) {
            return List.of();
        }
        return SecretScan.scan(relative(root, file), Files.readAllBytes(file));
    }

    /** Every file under {@code playbooks/} at the root and under any {@code playbooks/} directory in workflows/ and providers/. */
    static List<Path> localPlaybooks(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Path top = root.resolve("playbooks");
        if (Files.isDirectory(top)) {
            out.addAll(files(top));
        }
        for (String content : List.of("workflows", "providers")) {
            Path dir = root.resolve(content);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> playbookDirs;
            try (Stream<Path> walk = Files.walk(dir)) {
                playbookDirs = walk.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equals("playbooks")).sorted().toList();
            }
            for (Path p : playbookDirs) {
                out.addAll(files(p));
            }
        }
        return out.stream().distinct().sorted().toList();
    }

    static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static void error(boolean annotate, Where w, String what) {
        if (annotate) {
            System.out.println("::error file=" + property(w.file()) + (w.line() > 0 ? ",line=" + w.line() : "") + ",title=upload scan::" + data(what));
        } else {
            System.out.println("error: " + w.file() + (w.line() > 0 ? ":" + w.line() : "") + " " + what);
        }
    }

    /** A GitHub workflow command's message. */
    static String data(String s) {
        return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    /** A GitHub workflow command's property value. */
    static String property(String s) {
        return data(s).replace(":", "%3A").replace(",", "%2C");
    }

    static String plural(int n, String word) {
        return plural(n, word, word + "s");
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
