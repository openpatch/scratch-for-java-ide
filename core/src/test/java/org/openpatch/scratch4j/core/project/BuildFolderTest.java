package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BuildFolderTest {

  @TempDir
  Path tmp;

  @AfterEach
  void back() {
    BuildFolder.setBase(null);
  }

  @Test
  void onWindowsTheBuildGoesToLocalAppDataPerProject() throws Exception {
    Path base = BuildFolder.defaultBase(tmp.resolve("AppData/Local").toString());
    assertThat(base).isEqualTo(tmp.resolve("AppData/Local/scratch4j"));
    Path cat = tmp.resolve("share/Cat");
    Path dog = tmp.resolve("share/Dog");
    Path catBuild = BuildFolder.of(cat, base);
    assertThat(catBuild.getParent()).isEqualTo(base);
    assertThat(catBuild.getFileName().toString()).matches("[0-9a-f]{16}");
    assertThat(BuildFolder.of(dog, base)).isNotEqualTo(catBuild);
    // the same project, however the path is spelled, gets the same folder
    assertThat(BuildFolder.of(tmp.resolve("share/x/../Cat"), base)).isEqualTo(catBuild);
    assertThat(Files.readString(catBuild.resolve("project.txt"))).contains("Cat");
  }

  @Test
  void withoutLocalAppDataTheBuildStaysInTheProject() {
    Path cat = tmp.resolve("Cat");
    assertThat(BuildFolder.defaultBase(null)).isNull();
    assertThat(BuildFolder.of(cat, (Path) null)).isEqualTo(cat.resolve(".scratch4j/build"));
  }

  @Test
  void aFolderThatCannotBeMadeFallsBackToTheProject() throws Exception {
    Path file = Files.writeString(tmp.resolve("not-a-folder"), "x");
    Path cat = tmp.resolve("Cat");
    assertThat(BuildFolder.of(cat, file)).isEqualTo(cat.resolve(".scratch4j/build"));
  }

  @Test
  void theChosenFolderWins() {
    Path chosen = tmp.resolve("builds");
    BuildFolder.setBase(chosen);
    assertThat(BuildFolder.of(tmp.resolve("Cat")).getParent()).isEqualTo(chosen);
  }
}
