package com.iaf.platform.playbooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.archives.PlaybookArchive.Kind;
import com.iaf.platform.common.archives.PlaybookArchive.Listing;
import com.iaf.platform.common.domain.ForbiddenException;

class UploadRulesTest {

    static final Listing ZIP = new Listing(Kind.ZIP, List.of("site.yml", "roles/web/tasks/main.yml", "files/motd"), 10, null);

    @Test
    void theFileNameIsTheBaseNameTheClientSentWithAKnownExtension() {
        assertThat(UploadRules.fileName("C:\\fakepath\\restart.yml")).isEqualTo("restart.yml");
        assertThat(UploadRules.fileName("../../bundle.tar.gz")).isEqualTo("bundle.tar.gz");
        assertThatThrownBy(() -> UploadRules.fileName("setup.exe")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(".zip");
        assertThatThrownBy(() -> UploadRules.fileName(" ")).hasMessageContaining("no file name");
        assertThatThrownBy(() -> UploadRules.fileName("evil\u0007.yml")).hasMessageContaining("control character");
    }

    @Test
    void thePathMustBeAPlaybookInsideTheArchive() {
        assertThat(UploadRules.path(ZIP, "b.zip", "./site.yml")).isEqualTo("site.yml");
        assertThat(UploadRules.path(ZIP, "b.zip", null)).isNull();
        assertThatThrownBy(() -> UploadRules.path(ZIP, "b.zip", "deploy.yml")).hasMessageContaining("deploy.yml is not in b.zip")
                .hasMessageContaining("site.yml, roles/web/tasks/main.yml, files/motd");
        assertThatThrownBy(() -> UploadRules.path(ZIP, "b.zip", "files/motd")).hasMessageContaining(".yml or .yaml");
        assertThatThrownBy(() -> UploadRules.path(ZIP, "b.zip", "../site.yml")).hasMessageContaining("'..'");

        Listing single = new Listing(Kind.YAML, List.of("restart.yml"), 10, null);
        assertThat(UploadRules.path(single, "restart.yml", "restart.yml")).isEqualTo("restart.yml");
        assertThatThrownBy(() -> UploadRules.path(single, "restart.yml", "site.yml")).hasMessageContaining("single playbook");
    }

    @Test
    void aLongFileListIsShortenedInTheMessage() {
        List<String> many = IntStream.range(0, 20).mapToObj(i -> "f" + i + ".yml").toList();
        assertThat(UploadRules.preview(many)).endsWith("f11.yml and 8 more");
    }

    @Test
    void aPlaybookIsAYamlListOfPlays() {
        assertThatCode(() -> UploadRules.requirePlaybook("site.yml", bytes("- hosts: all\n  tasks: []\n"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadRules.requirePlaybook("site.yml", bytes("hosts: all\n"))).hasMessageContaining("list of plays");
        assertThatThrownBy(() -> UploadRules.requirePlaybook("site.yml", bytes("- hosts: [all\n"))).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> UploadRules.requirePlaybook("site.yml", bytes(""))).hasMessageContaining("list of plays");
        assertThatCode(() -> UploadRules.requirePlaybook("site.yml", null)).as("too large to capture: not parsed").doesNotThrowAnyException();
    }

    @Test
    void onlyTeamMembersAndAdminsAreAuthors() {
        assertThatCode(() -> UploadRules.requireAuthor(SampleCatalog.OPS, "upload playbooks")).doesNotThrowAnyException();
        assertThatCode(() -> UploadRules.requireAuthor(SampleCatalog.ADMIN, "upload playbooks")).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadRules.requireAuthor(SampleCatalog.GUEST, "upload playbooks"))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only members of a team can upload playbooks");
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
