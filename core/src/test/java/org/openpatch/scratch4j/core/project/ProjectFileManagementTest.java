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

  @Test
  void moveIntoAFolderRewritesThePathInCode() throws IOException {
    Path images = Files.createDirectories(root.resolve("assets/images"));
    Path heroes = Files.createDirectories(root.resolve("assets/images/heroes"));
    Path cat = images.resolve("cat.png");
    Files.writeString(cat, "pixels");
    Files.writeString(root.resolve("Cat.java"), """
        class Cat {
          String costume = "assets/images/cat.png";
        }
        """);
    ScratchProject project = ScratchProject.open(root);

    Path moved = ProjectFileManagement.move(project, cat, heroes);
    assertThat(cat).doesNotExist();
    assertThat(moved).isEqualTo(heroes.toRealPath().resolve("cat.png"));
    assertThat(Files.readString(root.resolve("Cat.java")))
        .contains("\"assets/images/heroes/cat.png\"");
    assertThatThrownBy(() -> ProjectFileManagement.move(project, moved, heroes))
        .isInstanceOf(IOException.class).hasMessageContaining("already");
  }

  @Test
  void moveRefusesPathsItCannotRewrite() throws IOException {
    Path images = Files.createDirectories(root.resolve("assets/images"));
    Path data = Files.createDirectories(root.resolve("data"));
    Path cat = images.resolve("cat.png");
    Files.writeString(cat, "pixels");
    Files.writeString(root.resolve("Cat.java"),
        "class Cat { String note = \"see assets/images/cat.png\"; }");
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFileManagement.move(project, cat, data))
        .isInstanceOf(IOException.class).hasMessageContaining("Cat.java");
    assertThat(cat).exists();
  }

  @Test
  void javaClassesStayInTheProjectFolder() throws IOException {
    Path folder = Files.createDirectories(root.resolve("helpers"));
    Path player = root.resolve("Player.java");
    Files.writeString(player, "class Player {}");
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFileManagement.move(project, player, folder))
        .isInstanceOf(IOException.class).hasMessageContaining("project folder");
    Path stray = folder.resolve("Helper.java");
    Files.writeString(stray, "class Helper {}");
    assertThat(ProjectFileManagement.move(project, stray, root))
        .isEqualTo(root.toRealPath().resolve("Helper.java"));
  }

  @Test
  void renameAndDuplicateOrdinaryFiles() throws IOException {
    Path notes = root.resolve("notes.txt");
    Files.writeString(notes, "hello");
    ScratchProject project = ScratchProject.open(root);
    Path renamed = ProjectFileManagement.rename(project, notes, "ideas.md");
    assertThat(Files.readString(renamed)).isEqualTo("hello");
    assertThat(ProjectFileManagement.copyName(renamed)).isEqualTo("ideas-2.md");
    Path copy = ProjectFileManagement.duplicate(project, renamed, "ideas-2.md");
    assertThat(Files.readString(copy)).isEqualTo("hello");
    assertThat(renamed).exists();
    assertThatThrownBy(() -> ProjectFileManagement.rename(project, renamed, "Ideas.java"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> ProjectFileManagement.rename(project, renamed, "../x.md"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void pasteCopiesFilesAndFoldersUnderAFreeName() throws IOException {
    Path images = Files.createDirectories(root.resolve("assets/images"));
    Path cat = Files.writeString(images.resolve("cat.png"), "cat");
    Files.writeString(Files.createDirectories(images.resolve("pack")).resolve("dog.png"), "dog");
    Path outside = Files.writeString(Files.createTempDirectory("outside").resolve("cat.png"), "x");
    ScratchProject project = ScratchProject.open(root);

    assertThat(ProjectFileManagement.copyInto(project, cat, images).getFileName())
        .hasToString("cat-2.png");
    assertThat(ProjectFileManagement.copyInto(project, outside, root).getFileName())
        .hasToString("cat.png");
    Path pack = ProjectFileManagement.copyInto(project, images.resolve("pack"), root);
    assertThat(Files.readString(pack.resolve("dog.png"))).isEqualTo("dog");
    assertThatThrownBy(() -> ProjectFileManagement.copyInto(project, images, images.resolve("pack")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void pastedClassesStayInTheProjectFolder() throws IOException {
    Path player = Files.writeString(root.resolve("Player.java"), "class Player {}");
    Path folder = Files.createDirectories(root.resolve("notes"));
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFileManagement.copyInto(project, player, folder))
        .isInstanceOf(IOException.class).hasMessageContaining("project folder");
    Files.writeString(folder.resolve("Helper.java"), "class Helper {}");
    assertThatThrownBy(() -> ProjectFileManagement.copyInto(project, folder, root))
        .isInstanceOf(IOException.class).hasMessageContaining("Java");
  }

  @Test
  void restoreUndoesADelete() throws IOException {
    Path notes = Files.writeString(Files.createDirectories(root.resolve("docs"))
        .resolve("notes.txt"), "keep");
    ScratchProject project = ScratchProject.open(root);
    Path trashed = ProjectFileManagement.delete(project, notes);
    Files.delete(root.resolve("docs"));
    ProjectFileManagement.restore(project, trashed, notes);
    assertThat(Files.readString(notes)).isEqualTo("keep");
    assertThatThrownBy(() -> ProjectFileManagement.restore(project, notes, root.resolve("x.txt")))
        .isInstanceOf(IOException.class);
  }
}
