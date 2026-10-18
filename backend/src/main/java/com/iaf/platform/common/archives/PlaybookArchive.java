package com.iaf.platform.common.archives;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Playbook bundles: one {@code .yml}/{@code .yaml} file, a {@code .zip}, or a {@code .tar.gz}
 * ({@code .tgz}). Whatever reads one goes through the same checks: every entry name is relative,
 * uses forward slashes and has no {@code ..} segment or control character; only regular files and
 * directories are allowed (no links, devices or pipes); no name appears twice; and the entry
 * count and the bytes actually unpacked are capped while reading, never trusting the sizes the
 * archive claims. Every failure is an {@link IllegalArgumentException} that names the entry.
 */
public final class PlaybookArchive {

    static final int MAX_NAME = 1024;
    /** The largest entry {@link #inspect} hands back; a bigger one is checked but not captured. */
    public static final int MAX_CAPTURE = 1024 * 1024;
    private static final int FILE_MODE_EXEC = 0100;

    public enum Kind {
        YAML, ZIP, TAR_GZ;

        /** The kind a file name announces. */
        public static Kind of(String fileName) {
            String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if (n.endsWith(".yml") || n.endsWith(".yaml")) {
                return YAML;
            }
            if (n.endsWith(".zip")) {
                return ZIP;
            }
            if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
                return TAR_GZ;
            }
            throw new IllegalArgumentException("A playbook is a .yml, .yaml, .zip or .tar.gz file; got '" + fileName + "'");
        }
    }

    /** At most {@code maxEntries} entries and {@code maxBytes} unpacked bytes. */
    public record Limits(int maxEntries, long maxBytes) {
    }

    /**
     * What an archive holds: its file entries in archive order and the bytes they unpack to;
     * {@code captured} is the content of the entry asked for, or null when there is none or it is
     * larger than {@link #MAX_CAPTURE}.
     */
    public record Listing(Kind kind, List<String> files, long unpackedBytes, byte[] captured) {
    }

    private PlaybookArchive() {
    }

    /** Check an upload and list it without writing anything; {@code capture} names an entry whose bytes to return. */
    public static Listing inspect(Path file, String fileName, Limits limits, String capture) throws IOException {
        return walk(file, fileName, limits, null, capture == null ? null : safeName(capture, false));
    }

    /** Check and unpack into {@code target} (created when missing); a single playbook is copied as {@code target/<fileName>}. */
    public static Listing extract(Path file, String fileName, Path target, Limits limits) throws IOException {
        Files.createDirectories(target);
        return walk(file, fileName, limits, target.toAbsolutePath().normalize(), null);
    }

    /** The checked name of an entry: relative, forward slashes, no {@code .} or {@code ..} segment; "" for the root itself. */
    public static String safeName(String raw, boolean directory) {
        if (raw == null) {
            throw new IllegalArgumentException("An archive entry has no name");
        }
        if (raw.length() > MAX_NAME) {
            throw new IllegalArgumentException("Entry name is longer than " + MAX_NAME + " characters: " + raw.substring(0, 60) + "…");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Entry name has a control character: " + printable(raw));
            }
        }
        if (raw.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Entry name uses a backslash: " + raw);
        }
        if (raw.startsWith("/") || raw.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Entry name is an absolute path: " + raw);
        }
        List<String> segments = new ArrayList<>();
        for (String s : raw.split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                throw new IllegalArgumentException("Entry name climbs out of the archive with '..': " + raw);
            }
            segments.add(s);
        }
        String name = String.join("/", segments);
        if (name.isEmpty() && !directory) {
            throw new IllegalArgumentException("A file entry has an empty name: '" + raw + "'");
        }
        return name;
    }

    // ---------------------------------------------------------------- the walk

    private static Listing walk(Path file, String fileName, Limits limits, Path target, String capture) throws IOException {
        Kind kind = Kind.of(fileName);
        Walk w = new Walk(kind, limits, target, capture);
        switch (kind) {
            case YAML -> {
                String name = safeName(baseName(fileName), false);
                try (InputStream in = Files.newInputStream(file)) {
                    w.file(name, in, false);
                }
            }
            case ZIP -> zip(file, w);
            case TAR_GZ -> tarGz(file, w);
            default -> throw new IllegalStateException();
        }
        return new Listing(kind, List.copyOf(w.files), w.bytes, w.captured);
    }

    private static void zip(Path file, Walk w) throws IOException {
        if (!startsWith(file, new byte[] { 'P', 'K' })) {
            throw new IllegalArgumentException("The file is not a zip archive");
        }
        ZipFile opened;
        try {
            opened = ZipFile.builder().setPath(file).get();
        } catch (IOException e) {
            throw w.damaged(e);
        }
        try (ZipFile zip = opened) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry e = entries.nextElement();
                w.count(e.getName());
                if (e.isUnixSymlink()) {
                    throw new IllegalArgumentException("Links are not allowed in a playbook archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    w.directory(safeName(e.getName(), true));
                    continue;
                }
                String name = safeName(e.getName(), false);
                if (!zip.canReadEntryData(e)) {
                    throw new IllegalArgumentException("Entry " + name + " is encrypted or uses an unsupported compression");
                }
                InputStream in;
                try {
                    in = zip.getInputStream(e);
                } catch (IOException ex) {
                    throw w.damaged(ex);
                }
                try (in) {
                    w.file(name, in, (e.getUnixMode() & FILE_MODE_EXEC) != 0);
                }
            }
        }
    }

    private static void tarGz(Path file, Walk w) throws IOException {
        if (!startsWith(file, new byte[] { 0x1f, (byte) 0x8b })) {
            throw new IllegalArgumentException("The file is not a gzip-compressed tar archive");
        }
        InputStream gzip;
        try {
            gzip = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)));
        } catch (IOException e) {
            throw w.damaged(e);
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry e;
            while ((e = next(tar, w)) != null) {
                w.count(e.getName());
                if (e.isSymbolicLink() || e.isLink()) {
                    throw new IllegalArgumentException("Links are not allowed in a playbook archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    w.directory(safeName(e.getName(), true));
                } else if (e.isFile()) {
                    w.file(safeName(e.getName(), false), tar, (e.getMode() & FILE_MODE_EXEC) != 0);
                } else {
                    throw new IllegalArgumentException("Only files and directories are allowed in a playbook archive: " + e.getName());
                }
            }
        }
    }

    private static TarArchiveEntry next(TarArchiveInputStream tar, Walk w) {
        try {
            return tar.getNextEntry();
        } catch (IOException | IllegalArgumentException e) {
            throw w.damaged(e);
        }
    }

    /** The state of one walk: names seen, bytes read, the entry captured, the target written to. */
    private static final class Walk {
        final Kind kind;
        final Limits limits;
        final Path target;
        final String capture;
        final List<String> files = new ArrayList<>();
        final Set<String> fileNames = new HashSet<>();
        final Set<String> dirNames = new HashSet<>();
        int entries;
        long bytes;
        byte[] captured;

        Walk(Kind kind, Limits limits, Path target, String capture) {
            this.kind = kind;
            this.limits = limits;
            this.target = target;
            this.capture = capture;
        }

        /** A read error: the archive is damaged (a bad request); for a single playbook it stays an I/O error. */
        RuntimeException damaged(Exception e) {
            if (kind == Kind.YAML && e instanceof IOException io) {
                return new UncheckedIOException(io);
            }
            return new IllegalArgumentException("The " + (kind == Kind.ZIP ? "zip" : "tar.gz") + " archive is damaged: " + e.getMessage());
        }

        void count(String name) {
            entries++;
            if (entries > limits.maxEntries()) {
                throw new IllegalArgumentException("The archive has more than " + limits.maxEntries() + " entries");
            }
        }

        void directory(String name) throws IOException {
            if (name.isEmpty()) {
                return;
            }
            if (fileNames.contains(name)) {
                throw new IllegalArgumentException("Entry " + name + " is both a file and a directory");
            }
            parents(name);
            dirNames.add(name);
            if (target != null) {
                Files.createDirectories(inside(name));
            }
        }

        void file(String name, InputStream in, boolean executable) throws IOException {
            if (fileNames.contains(name)) {
                throw new IllegalArgumentException("Entry " + name + " appears twice");
            }
            if (dirNames.contains(name)) {
                throw new IllegalArgumentException("Entry " + name + " is both a file and a directory");
            }
            parents(name);
            fileNames.add(name);
            files.add(name);
            InputStream limited = new Capped(in, this);
            boolean wanted = name.equals(capture);
            if (target != null) {
                Path out = inside(name);
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    limited.transferTo(os);
                }
                try {
                    Files.setPosixFilePermissions(out, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system: keep the defaults
                }
            } else if (wanted) {
                byte[] head = limited.readNBytes(MAX_CAPTURE + 1);
                captured = head.length <= MAX_CAPTURE ? head : null;
                limited.transferTo(OutputStream.nullOutputStream());
            } else {
                limited.transferTo(OutputStream.nullOutputStream());
            }
        }

        /** No parent of {@code name} may be a file. */
        private void parents(String name) {
            int slash = name.indexOf('/');
            while (slash > 0) {
                String parent = name.substring(0, slash);
                if (fileNames.contains(parent)) {
                    throw new IllegalArgumentException("Entry " + name + " sits under " + parent + ", which is a file");
                }
                dirNames.add(parent);
                slash = name.indexOf('/', slash + 1);
            }
        }

        private Path inside(String name) {
            Path out = target.resolve(name).normalize();
            if (!out.startsWith(target)) {
                throw new IllegalArgumentException("Entry " + name + " would land outside the target directory");
            }
            return out;
        }
    }

    /** Counts what is really read against the archive's byte limit. */
    private static final class Capped extends FilterInputStream {
        private final Walk walk;

        Capped(InputStream in, Walk walk) {
            super(in);
            this.walk = walk;
        }

        @Override
        public int read() {
            int b;
            try {
                b = super.read();
            } catch (IOException e) {
                throw walk.damaged(e);
            }
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int n;
            try {
                n = super.read(b, off, len);
            } catch (IOException e) {
                throw walk.damaged(e);
            }
            if (n > 0) {
                add(n);
            }
            return n;
        }

        @Override
        public void close() {
            // the archive stream outlives each entry
        }

        private void add(long n) {
            walk.bytes += n;
            if (walk.bytes > walk.limits.maxBytes()) {
                throw new IllegalArgumentException("The archive unpacks to more than " + walk.limits.maxBytes() + " bytes");
            }
        }
    }

    static String baseName(String fileName) {
        String n = fileName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private static boolean startsWith(Path file, byte[] magic) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(magic.length);
            if (head.length < magic.length) {
                return false;
            }
            for (int i = 0; i < magic.length; i++) {
                if (head[i] != magic[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static String printable(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return sb.toString();
    }
}
