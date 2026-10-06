package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFileManagementTest {

  @TempDir Path root;

  @Test
  void ordinaryFileMovesToTrashAndCanBeRecovered() throws IOException {
    Path file = root.resolve("notes.txt");
    Files.writeString(file, "important notes");
    Path trash = ProjectFileManagement.delete(ScratchProject.open(root), file);
    assertThat(file).doesNotExist();
    assertThat(Files.readString(trash)).isEqualTo("important notes");
    assertThat(trash).startsWith(root.resolve(".scratch4j/trash"));
  }

  @Test
  void referencedSpriteSourceCannotBeDeleted() throws IOException {
    Path sprite = root.resolve("Player.java");
    Files.writeString(sprite, "class Player extends Sprite {}");
    Files.writeString(root.resolve("MyStage.java"),
        "class MyStage { Player player = new Player(); }");
    assertThatThrownBy(() -> ProjectFileManagement.delete(ScratchProject.open(root), sprite))
        .isInstanceOf(IOException.class).hasMessageContaining("MyStage.java");
    assertThat(sprite).exists();
  }

  @Test
  void rejectsDirectoryAndLibraryJar() throws IOException {
    Path libs = root.resolve("+libs");
    Files.createDirectories(libs);
    Path jar = libs.resolve("scratch.jar");
    Files.writeString(jar, "jar");
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFileManagement.delete(project, libs))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> ProjectFileManagement.delete(project, jar))
        .isInstanceOf(IOException.class).hasMessageContaining("bundled library");
  }
}
