package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectsFolderTest {

  @TempDir
  Path home;

  @Test
  void aGermanLinuxDesktopUsesItsDokumenteFolder() throws Exception {
    Files.createDirectories(home.resolve("Dokumente"));
    Files.createDirectories(home.resolve(".config"));
    Files.writeString(home.resolve(".config/user-dirs.dirs"), """
        # written by xdg-user-dirs-update
        XDG_DESKTOP_DIR="$HOME/Schreibtisch"
        XDG_DOCUMENTS_DIR="$HOME/Dokumente"
        """);
    assertThat(ProjectsFolder.defaultFolder(Map.of(), "Linux", home))
        .isEqualTo(home.resolve("Dokumente").resolve("Scratch for Java"));
  }

  @Test
  void xdgConfigHomeIsHonouredAndAbsoluteFoldersWork() throws Exception {
    Path config = Files.createDirectories(home.resolve("cfg"));
    Path docs = Files.createDirectories(home.resolve("elsewhere/docs"));
    Files.writeString(config.resolve("user-dirs.dirs"), "XDG_DOCUMENTS_DIR=\"" + docs + "\"\n");
    assertThat(ProjectsFolder.documents(Map.of("XDG_CONFIG_HOME", config.toString()), "Linux",
        home)).isEqualTo(docs);
  }

  @Test
  void withoutADocumentsFolderTheHomeFolderIsUsed() {
    assertThat(ProjectsFolder.documents(Map.of(), "Linux", home)).isEqualTo(home);
    // an XDG entry pointing at a folder that is gone does not count
    assertThat(ProjectsFolder.xdgDocuments(home.resolve("missing"), home)).isNull();
  }

  @Test
  void macAndWindowsUseDocumentsAndWindowsFindsOneDrive() throws Exception {
    Files.createDirectories(home.resolve("Documents"));
    assertThat(ProjectsFolder.documents(Map.of(), "Mac OS X", home))
        .isEqualTo(home.resolve("Documents"));
    assertThat(ProjectsFolder.documents(Map.of(), "Windows 11", home))
        .isEqualTo(home.resolve("Documents"));

    Path user = Files.createDirectories(home.resolve("user"));
    Path oneDrive = Files.createDirectories(home.resolve("OneDrive/Dokumente"));
    assertThat(ProjectsFolder.documents(Map.of("OneDrive", oneDrive.getParent().toString()),
        "Windows 11", user)).isEqualTo(oneDrive);
  }

  @Test
  void schoolItCanPointEveryMachineAtOneFolder() {
    assertThat(ProjectsFolder.defaultFolder(Map.of("SCRATCH4J_PROJECTS_DIR", "/srv/klasse7a"),
        "Linux", home)).isEqualTo(Path.of("/srv/klasse7a"));
  }

  @Test
  void aChooserStartsInTheNearestFolderThatExists() {
    assertThat(ProjectsFolder.existing(home.resolve("Dokumente/Scratch for Java")))
        .isEqualTo(home.toFile());
  }
}
