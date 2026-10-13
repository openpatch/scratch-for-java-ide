package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedProjectFileTest {

  @TempDir
  Path dir;

  @Test
  void projectZipsAndWorkspaceJsonsAreAcceptedInAnyCase() throws Exception {
    Path zip = Files.createFile(dir.resolve("Spiel.ZIP"));
    Path json = Files.createFile(dir.resolve("spiel.json"));
    assertThat(SharedProjectFile.accepts(zip)).isTrue();
    assertThat(SharedProjectFile.accepts(json)).isTrue();
    assertThat(SharedProjectFile.isJson(zip)).isFalse();
    assertThat(SharedProjectFile.isJson(json)).isTrue();
  }

  @Test
  void otherFilesFoldersAndMissingFilesAreNot() throws Exception {
    assertThat(SharedProjectFile.accepts(Files.createFile(dir.resolve("Spiel.sb3")))).isFalse();
    assertThat(SharedProjectFile.accepts(Files.createDirectory(dir.resolve("projekt.zip")))).isFalse();
    assertThat(SharedProjectFile.accepts(dir.resolve("fehlt.zip"))).isFalse();
  }
}
