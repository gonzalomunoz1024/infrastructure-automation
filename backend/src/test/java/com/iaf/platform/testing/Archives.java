package com.iaf.platform.testing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/** Builds zip and tar.gz playbook bundles for tests, including the malformed ones a check must refuse. */
public final class Archives {

    private Archives() {
    }

    /** A zip of {@code name -> content}; a name ending in "/" is a directory. Names are written as given. */
    public static Path zip(Path file, Map<String, String> entries) throws IOException {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(file))) {
            for (var e : entries.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(e.getKey());
                zip.putArchiveEntry(entry);
                if (!e.getKey().endsWith("/")) {
                    zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeArchiveEntry();
            }
        }
        return file;
    }

    /** A zip whose only entry is a symbolic link to {@code target}. */
    public static Path zipWithLink(Path file, String name, String target) throws IOException {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(file))) {
            ZipArchiveEntry entry = new ZipArchiveEntry(name);
            entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
            zip.putArchiveEntry(entry);
            zip.write(target.getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        return file;
    }

    /** A gzip-compressed tar of {@code name -> content}; a name ending in "/" is a directory. Absolute names are kept. */
    public static Path tarGz(Path file, Map<String, String> entries) throws IOException {
        try (TarArchiveOutputStream tar = tar(file)) {
            for (var e : entries.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(e.getKey(), true);
                byte[] bytes = e.getKey().endsWith("/") ? new byte[0] : e.getValue().getBytes(StandardCharsets.UTF_8);
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
        }
        return file;
    }

    /** A tar.gz with one executable file. */
    public static Path tarGzExecutable(Path file, String name, String content) throws IOException {
        try (TarArchiveOutputStream tar = tar(file)) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setMode(0100755);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        return file;
    }

    /** A tar.gz whose only entry is a symbolic (or hard) link. */
    public static Path tarGzWithLink(Path file, String name, String target, boolean hard) throws IOException {
        try (TarArchiveOutputStream tar = tar(file)) {
            TarArchiveEntry entry = new TarArchiveEntry(name, hard ? TarConstants.LF_LINK : TarConstants.LF_SYMLINK);
            entry.setLinkName(target);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        return file;
    }

    /** {@code count} entries of {@code bytes} zeros each, which compress to almost nothing. */
    public static Path zipOfZeros(Path file, int count, int bytes) throws IOException {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(file))) {
            byte[] zeros = new byte[bytes];
            for (int i = 0; i < count; i++) {
                zip.putArchiveEntry(new ZipArchiveEntry("f" + i + ".yml"));
                zip.write(zeros);
                zip.closeArchiveEntry();
            }
        }
        return file;
    }

    private static TarArchiveOutputStream tar(Path file) throws IOException {
        OutputStream out = new GZIPOutputStream(Files.newOutputStream(file));
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        return tar;
    }
}
