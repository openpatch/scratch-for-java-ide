package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which folders the Files tree shows. */
class ProjectTreeTest {

  @TempDir
  Path root;

  private static List<String> shown(TreeItem<Path> item, Path root) {
    List<String> out = new ArrayList<>();
    for (TreeItem<Path> child : item.getChildren()) {
      out.add(root.relativize(child.getValue()).toString().replace('\\', '/'));
      out.addAll(shown(child, root));
    }
    return out;
  }

  private List<String> tree(Set<Path> keepVisible) {
    return shown(ProjectTree.build(root, Set.of(), keepVisible), root);
  }

  @Test
  void emptyAssetFoldersAndAnAllEmptyAssetsFolderAreHidden() throws Exception {
    Files.writeString(root.resolve("MyStage.java"), "");
    Files.createDirectories(root.resolve("assets/images"));
    Files.createDirectories(root.resolve("assets/sounds/effects"));
    Files.writeString(root.resolve("assets/sounds/.gitkeep"), "");
    Files.createDirectories(root.resolve("notes"));
    assertThat(tree(Set.of())).as("assets has nothing to show, an empty folder elsewhere stays")
        .containsExactly("notes", "MyStage.java");

    Files.writeString(root.resolve("assets/images/cat.png"), "");
    assertThat(tree(Set.of())).containsExactly("assets", "assets/images",
        "assets/images/cat.png", "notes", "MyStage.java");
  }

  @Test
  void aFolderTheStudentJustMadeStaysVisibleWhileEmpty() throws Exception {
    Path levels = Files.createDirectories(root.resolve("assets/levels"));
    assertThat(tree(Set.of())).isEmpty();
    assertThat(tree(Set.of(levels.toAbsolutePath().normalize())))
        .containsExactly("assets", "assets/levels");
  }
}
