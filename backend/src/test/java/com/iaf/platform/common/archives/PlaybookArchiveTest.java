package com.iaf.platform.common.archives;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iaf.platform.common.archives.PlaybookArchive.Kind;
import com.iaf.platform.common.archives.PlaybookArchive.Limits;
import com.iaf.platform.common.archives.PlaybookArchive.Listing;
import com.iaf.platform.testing.Archives;

class PlaybookArchiveTest {

    static final Limits LIMITS = new Limits(100, 1_000_000);
    static final String PLAY = "- hosts: all\n  tasks:\n    - ping:\n";

    @TempDir
    Path dir;

    static Map<String, String> bundle() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("roles/", "");
        m.put("roles/web/tasks/main.yml", "- ping:\n");
        m.put("./site.yml", PLAY);
        m.put("requirements.yml", "collections: []\n");
        return m;
    }

    @Test
    void theKindFollowsTheFileName() {
        assertThat(Kind.of("restart.YML")).isEqualTo(Kind.YAML);
        assertThat(Kind.of("bundle.zip")).isEqualTo(Kind.ZIP);
        assertThat(Kind.of("bundle.tar.gz")).isEqualTo(Kind.TAR_GZ);
        assertThat(Kind.of("bundle.tgz")).isEqualTo(Kind.TAR_GZ);
        assertThatThrownBy(() -> Kind.of("bundle.rar")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(".tar.gz");
    }

    @Test
    void aZipIsListedWithItsFilesAndTheEntryAskedFor() throws Exception {
        Path zip = Archives.zip(dir.resolve("b.zip"), bundle());

        Listing l = PlaybookArchive.inspect(zip, "b.zip", LIMITS, "site.yml");

        assertThat(l.kind()).isEqualTo(Kind.ZIP);
        assertThat(l.files()).containsExactly("roles/web/tasks/main.yml", "site.yml", "requirements.yml");
        assertThat(new String(l.captured(), StandardCharsets.UTF_8)).isEqualTo(PLAY);
        assertThat(l.unpackedBytes()).isEqualTo(("- ping:\n" + PLAY + "collections: []\n").length());
    }

    @Test
    void aTarGzIsExtractedInsideTheTargetKeepingExecutableBits() throws Exception {
        Path tgz = Archives.tarGz(dir.resolve("b.tar.gz"), bundle());
        Path target = dir.resolve("project");

        Listing l = PlaybookArchive.extract(tgz, "b.tar.gz", target, LIMITS);

        assertThat(l.files()).containsExactly("roles/web/tasks/main.yml", "site.yml", "requirements.yml");
        assertThat(target.resolve("site.yml")).hasContent(PLAY.stripTrailing());
        assertThat(target.resolve("roles/web/tasks/main.yml")).exists();

        Path exe = Archives.tarGzExecutable(dir.resolve("x.tgz"), "bin/inventory.sh", "#!/bin/sh\necho {}\n");
        PlaybookArchive.extract(exe, "x.tgz", dir.resolve("x"), LIMITS);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("x/bin/inventory.sh")))).isEqualTo("rwxr-xr-x");
    }

    @Test
    void aSinglePlaybookIsItsOwnOnlyEntry() throws Exception {
        Path yml = Files.writeString(dir.resolve("upload.bin"), PLAY);

        Listing l = PlaybookArchive.inspect(yml, "../../restart.yml", LIMITS, "restart.yml");
        assertThat(l.kind()).isEqualTo(Kind.YAML);
        assertThat(l.files()).containsExactly("restart.yml");
        assertThat(l.captured()).isNotNull();

        PlaybookArchive.extract(yml, "restart.yml", dir.resolve("p"), LIMITS);
        assertThat(dir.resolve("p/restart.yml")).hasContent(PLAY.stripTrailing());
    }

    @Test
    void namesThatClimbOutOrAreAbsoluteAreRefused() throws Exception {
        Path climbZip = Archives.zip(dir.resolve("climb.zip"), Map.of("roles/../../evil.yml", PLAY));
        assertThatThrownBy(() -> PlaybookArchive.inspect(climbZip, "climb.zip", LIMITS, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'..'");

        Path absoluteTar = Archives.tarGz(dir.resolve("abs.tar.gz"), Map.of("/etc/cron.d/evil", "x"));
        assertThatThrownBy(() -> PlaybookArchive.extract(absoluteTar, "abs.tar.gz", dir.resolve("t"), LIMITS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
        assertThat(dir.resolve("t")).isEmptyDirectory();

        // with a "/" in the name the zip reader keeps the backslashes as they are
        Path backslash = Archives.zip(dir.resolve("bs.zip"), Map.of("roles/..\\..\\evil.yml", PLAY));
        assertThatThrownBy(() -> PlaybookArchive.inspect(backslash, "bs.zip", LIMITS, null)).hasMessageContaining("backslash");
        assertThatThrownBy(() -> PlaybookArchive.safeName("C:/Windows/evil", false)).hasMessageContaining("absolute");
        assertThatThrownBy(() -> PlaybookArchive.safeName("a\u0000b", false)).hasMessageContaining("control character");
        assertThat(PlaybookArchive.safeName("./roles//web/./main.yml", false)).isEqualTo("roles/web/main.yml");
    }

    @Test
    void linksAndSpecialFilesAreRefused() throws Exception {
        Path zipLink = Archives.zipWithLink(dir.resolve("l.zip"), "inventory", "/etc/passwd");
        assertThatThrownBy(() -> PlaybookArchive.inspect(zipLink, "l.zip", LIMITS, null)).hasMessageContaining("Links are not allowed");

        Path symlink = Archives.tarGzWithLink(dir.resolve("s.tgz"), "inventory", "/etc/passwd", false);
        assertThatThrownBy(() -> PlaybookArchive.inspect(symlink, "s.tgz", LIMITS, null)).hasMessageContaining("Links are not allowed");

        Path hardlink = Archives.tarGzWithLink(dir.resolve("h.tgz"), "inventory", "site.yml", true);
        assertThatThrownBy(() -> PlaybookArchive.inspect(hardlink, "h.tgz", LIMITS, null)).hasMessageContaining("Links are not allowed");
    }

    @Test
    void theEntryCountAndTheUnpackedBytesAreCappedWhileReading() throws Exception {
        Path many = Archives.zipOfZeros(dir.resolve("many.zip"), 11, 1);
        assertThatThrownBy(() -> PlaybookArchive.inspect(many, "many.zip", new Limits(10, 1_000_000), null))
                .hasMessageContaining("more than 10 entries");

        // a few hundred bytes on disk that unpack to 4 MB
        Path bomb = Archives.zipOfZeros(dir.resolve("bomb.zip"), 2, 2_000_000);
        assertThat(Files.size(bomb)).isLessThan(20_000);
        assertThatThrownBy(() -> PlaybookArchive.extract(bomb, "bomb.zip", dir.resolve("b"), new Limits(10, 1_000_000)))
                .hasMessageContaining("more than 1000000 bytes");
    }

    @Test
    void duplicatesAndFileDirectoryClashesAreRefused() throws Exception {
        Map<String, String> clash = new LinkedHashMap<>();
        clash.put("roles", "a file");
        clash.put("roles/web/main.yml", "- ping:\n");
        Path zip = Archives.zip(dir.resolve("c.zip"), clash);
        assertThatThrownBy(() -> PlaybookArchive.inspect(zip, "c.zip", LIMITS, null)).hasMessageContaining("which is a file");

        Map<String, String> twice = new LinkedHashMap<>();
        twice.put("site.yml", PLAY);
        twice.put("./site.yml", "- hosts: evil\n");
        Path tgz = Archives.tarGz(dir.resolve("d.tar.gz"), twice);
        assertThatThrownBy(() -> PlaybookArchive.inspect(tgz, "d.tar.gz", LIMITS, null)).hasMessageContaining("appears twice");
    }

    @Test
    void contentThatDoesNotMatchItsExtensionIsRefused() throws Exception {
        Path notZip = Files.writeString(dir.resolve("x.zip"), PLAY);
        assertThatThrownBy(() -> PlaybookArchive.inspect(notZip, "x.zip", LIMITS, null)).hasMessageContaining("not a zip");
        Path notGzip = Files.writeString(dir.resolve("x.tar.gz"), PLAY);
        assertThatThrownBy(() -> PlaybookArchive.inspect(notGzip, "x.tar.gz", LIMITS, null)).hasMessageContaining("not a gzip");
        Path truncated = dir.resolve("t.tar.gz");
        byte[] whole = Files.readAllBytes(Archives.tarGz(dir.resolve("whole.tar.gz"), bundle()));
        Files.write(truncated, java.util.Arrays.copyOf(whole, whole.length / 2));
        assertThatThrownBy(() -> PlaybookArchive.inspect(truncated, "t.tar.gz", LIMITS, null)).hasMessageContaining("damaged");
    }
}
