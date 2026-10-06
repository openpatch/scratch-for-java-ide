package org.openpatch.scratch4j.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalHistoryTest {

  @TempDir Path project;

  @Test
  void savesPreviousContentsAndRestoresWithoutLosingCurrentVersion() throws Exception {
    Path file = project.resolve("src/Stage.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "first");

    LocalHistory.writeString(project, file, "second");
    LocalHistory.writeString(project, file, "second");
    assertThat(LocalHistory.revisions(project, file)).hasSize(1);
    Path first = LocalHistory.revisions(project, file).get(0);
    assertThat(Files.readString(first)).isEqualTo("first");

    LocalHistory.restore(project, file, first);
    assertThat(Files.readString(file)).isEqualTo("first");
    assertThat(LocalHistory.revisions(project, file))
        .anySatisfy(revision -> assertThat(Files.readString(revision)).isEqualTo("second"));
  }

  @Test
  void binarySnapshotAndRetention() throws Exception {
    Path file = project.resolve("assets/image.png");
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[] {0, 1, (byte) 255});
    for (int i = 0; i < 55; i++) {
      LocalHistory.snapshot(project, file);
    }
    assertThat(LocalHistory.revisions(project, file)).hasSize(50);
    assertThat(Files.readAllBytes(LocalHistory.revisions(project, file).get(0)))
        .isEqualTo(new byte[] {0, 1, (byte) 255});
  }

  @Test
  void refusesFilesOutsideProject() throws Exception {
    Path outsider = project.getParent().resolve("outside.java");
    assertThatThrownBy(() -> LocalHistory.writeString(project, outsider, "x"))
        .isInstanceOf(java.io.IOException.class);
    assertThat(Files.exists(outsider)).isFalse();
  }
}
