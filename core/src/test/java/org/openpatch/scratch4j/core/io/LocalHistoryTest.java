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

  @Test
  void typingKeepsAVersionOnlyEveryIntervalButEverySaveIsWritten() throws Exception {
    Path file = project.resolve("Cat.java");
    Files.writeString(file, "v1");
    try {
      LocalHistory.setTypingInterval(java.time.Duration.ofMinutes(2));
      LocalHistory.writeStringWhileTyping(project, file, "v2"); // the first: kept
      LocalHistory.writeStringWhileTyping(project, file, "v3"); // within 2 minutes: not
      LocalHistory.writeStringWhileTyping(project, file, "v4");
      assertThat(Files.readString(file)).isEqualTo("v4");
      assertThat(LocalHistory.revisions(project, file)).hasSize(1);
      assertThat(Files.readString(LocalHistory.revisions(project, file).get(0))).isEqualTo("v1");

      // a designer's or a quick fix's change always keeps one
      LocalHistory.writeString(project, file, "v5");
      assertThat(LocalHistory.revisions(project, file)).hasSize(2);

      // zero: every save keeps one, as before
      LocalHistory.setTypingInterval(java.time.Duration.ZERO);
      LocalHistory.writeStringWhileTyping(project, file, "v6");
      assertThat(LocalHistory.revisions(project, file)).hasSize(3);
    } finally {
      LocalHistory.setTypingInterval(java.time.Duration.ofMinutes(2));
    }
  }
}
