package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectFolderManagementTest {

  @TempDir Path root;

  @Test
  void createsRenamesAndRecoverablyDeletesFolderWithUnreferencedFiles() throws IOException {
    ScratchProject project = ScratchProject.open(root);
    Path folder = ProjectFolderManagement.create(project, root, "media");
    Path image = folder.resolve("icon.png");
    Files.writeString(image, "pixels");

    Path renamed = ProjectFolderManagement.rename(project, folder, "artwork");
    assertThat(folder).doesNotExist();
    assertThat(Files.readString(renamed.resolve("icon.png"))).isEqualTo("pixels");

    Path trash = ProjectFolderManagement.delete(project, renamed);
    assertThat(renamed).doesNotExist();
    assertThat(Files.readString(trash.resolve("icon.png"))).isEqualTo("pixels");
    assertThat(trash).startsWith(root.resolve(".scratch4j/trash"));
  }

  @Test
  void protectsReservedFoldersAndReferencedPaths() throws IOException {
    Path assets = Files.createDirectory(root.resolve("assets"));
    Path images = Files.createDirectory(assets.resolve("images"));
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFolderManagement.rename(project, images, "pictures"))
        .isInstanceOf(IOException.class);
    Path custom = ProjectFolderManagement.create(project, assets, "custom");
    Files.writeString(custom.resolve("sprite.png"), "pixels");
    Files.writeString(root.resolve("Player.java"),
        "class Player { String image = \"assets/custom/sprite.png\"; }");
    assertThatThrownBy(() -> ProjectFolderManagement.delete(project, custom))
        .isInstanceOf(IOException.class).hasMessageContaining("Player.java");
    assertThat(custom).exists();
  }

  @Test
  void renameAndMoveRewriteThePathsOfTheFilesInside() throws IOException {
    Path assets = Files.createDirectories(root.resolve("assets/images"));
    Path custom = Files.createDirectory(root.resolve("assets/custom"));
    Files.writeString(custom.resolve("sprite.png"), "pixels");
    Files.writeString(root.resolve("Player.java"),
        "class Player { String image = \"assets/custom/sprite.png\"; }");
    ScratchProject project = ScratchProject.open(root);

    Path renamed = ProjectFolderManagement.rename(project, custom, "heroes");
    assertThat(Files.readString(root.resolve("Player.java")))
        .contains("\"assets/heroes/sprite.png\"");

    Path moved = ProjectFolderManagement.move(project, renamed, assets);
    assertThat(moved).isEqualTo(assets.toRealPath().resolve("heroes"));
    assertThat(Files.readString(moved.resolve("sprite.png"))).isEqualTo("pixels");
    assertThat(Files.readString(root.resolve("Player.java")))
        .contains("\"assets/images/heroes/sprite.png\"");
    assertThatThrownBy(() -> ProjectFolderManagement.move(project, moved, moved))
        .isInstanceOf(IOException.class).hasMessageContaining("itself");
  }

  @Test
  void refusesToMoveAFolderThatCodeBuildsPathsFrom() throws IOException {
    Path custom = Files.createDirectories(root.resolve("assets/custom"));
    Files.writeString(custom.resolve("sprite.png"), "pixels");
    Files.writeString(root.resolve("Player.java"),
        "class Player { String image(int i) { return \"assets/custom/\" + i + \".png\"; } }");
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFolderManagement.rename(project, custom, "heroes"))
        .isInstanceOf(IOException.class).hasMessageContaining("Player.java");
    assertThat(custom).exists();
  }

  @Test
  void refusesFoldersContainingJavaSourcesAndInvalidNames() throws IOException {
    Path src = Files.createDirectory(root.resolve("src"));
    Files.writeString(src.resolve("Player.java"), "class Player {}");
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFolderManagement.delete(project, src))
        .isInstanceOf(IOException.class).hasMessageContaining("Java source");
    assertThatThrownBy(() -> ProjectFolderManagement.create(project, root, "../outside"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> ProjectFolderManagement.delete(project, root))
        .isInstanceOf(IOException.class);
  }
}
