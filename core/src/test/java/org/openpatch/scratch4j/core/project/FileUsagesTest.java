package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUsagesTest {

  @TempDir Path root;

  private static final String CAT = """
      class Cat {
        void setup() {
          addCostume("cat", "assets/images/cat.png");
          say("assets/images/cat.png is me");
        }
        void addCostume(String name, String path) {}
        void say(String text) {}
      }
      """;

  private Path cat() throws IOException {
    Path cat = root.resolve("assets/images/cat.png");
    Files.createDirectories(cat.getParent());
    Files.writeString(cat, "pixels");
    Files.writeString(root.resolve("Cat.java"), CAT);
    return cat;
  }

  @Test
  void listsUpdatableAndManualUsagesWithTheirLines() throws IOException {
    Path cat = cat();
    Path shader = root.resolve("assets/shaders/glow.frag");
    Files.createDirectories(shader.getParent());
    Files.writeString(shader, "// uses\n// assets/images/cat.png\n");
    ScratchProject project = ScratchProject.open(root);
    project.settings().splashLogo = "assets/images/cat.png";

    List<FileUsages.Usage> usages = FileUsages.find(project, cat);

    assertThat(usages).hasSize(4);
    FileUsages.Usage literal = usages.stream()
        .filter(u -> u.updatable() && u.line() == 3).findFirst().orElseThrow();
    assertThat(literal.lineText()).isEqualTo("addCostume(\"cat\", \"assets/images/cat.png\");");
    assertThat(literal.lineText().substring(literal.from(), literal.to()))
        .isEqualTo("assets/images/cat.png");
    assertThat(usages).filteredOn(u -> !u.updatable())
        .extracting(u -> u.file().getFileName().toString() + ":" + u.line())
        .containsExactlyInAnyOrder("Cat.java:4", "glow.frag:2");
    assertThat(usages).filteredOn(u -> u.line() == 0).singleElement()
        .satisfies(u -> assertThat(u.updatable()).isTrue());
  }

  @Test
  void updateModeRewritesWhatItCanAndLeavesTheRest() throws IOException {
    Path cat = cat();
    Path heroes = Files.createDirectories(root.resolve("assets/heroes"));
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFileManagement.move(project, cat, heroes))
        .hasMessageContaining("Cat.java:4");

    ProjectFileManagement.move(project, cat, heroes, FileUsages.Mode.UPDATE);

    assertThat(Files.readString(root.resolve("Cat.java")))
        .contains("addCostume(\"cat\", \"assets/heroes/cat.png\")")
        .contains("say(\"assets/images/cat.png is me\")");
  }

  @Test
  void ignoreModeOnlyMoves() throws IOException {
    Path cat = cat();
    Path heroes = Files.createDirectories(root.resolve("assets/heroes"));
    ScratchProject project = ScratchProject.open(root);

    Path moved = ProjectFileManagement.move(project, cat, heroes, FileUsages.Mode.IGNORE);

    assertThat(moved).exists();
    assertThat(Files.readString(root.resolve("Cat.java"))).isEqualTo(CAT);
  }

  @Test
  void forceDeletesWhatIsStillUsed() throws IOException {
    Path cat = cat();
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectFileManagement.delete(project, cat))
        .hasMessageContaining("referenced");
    Path trash = ProjectFileManagement.delete(project, cat, true);
    assertThat(cat).doesNotExist();
    assertThat(trash).exists();
  }

  @Test
  void folderUsagesIncludeCodeThatBuildsPathsFromIt() throws IOException {
    Path folder = Files.createDirectories(root.resolve("assets/heroes"));
    Files.writeString(folder.resolve("cat.png"), "pixels");
    Files.writeString(root.resolve("Cat.java"),
        "class Cat { String costume = \"assets/heroes/cat.png\"; }");
    Files.writeString(root.resolve("Dog.java"),
        "class Dog { String frame(int i) { return \"assets/heroes/\" + i; } }");
    ScratchProject project = ScratchProject.open(root);

    List<FileUsages.Usage> usages = FileUsages.find(project, folder);

    assertThat(usages).filteredOn(u -> !u.updatable())
        .extracting(u -> u.file().getFileName().toString()).containsExactly("Dog.java");
    assertThat(usages).filteredOn(FileUsages.Usage::updatable).hasSize(1);
    assertThatThrownBy(() -> ProjectFolderManagement.delete(project, folder))
        .isInstanceOf(IOException.class);
    Path trash = ProjectFolderManagement.delete(project, folder, true);
    assertThat(trash).exists();
  }

  @Test
  void aClassIsUsedWhereOtherClassesNameIt() throws IOException {
    Files.writeString(root.resolve("Player.java"), "class Player { }");
    Files.writeString(root.resolve("Game.java"), """
        class Game {
          Player player = new Player();
        }
        """);
    ScratchProject project = ScratchProject.open(root);

    List<FileUsages.Usage> usages = FileUsages.find(project, root.resolve("Player.java"));

    assertThat(usages).hasSize(2).allMatch(u -> !u.updatable()
        && u.file().getFileName().toString().equals("Game.java") && u.line() == 2);
    assertThat(FileUsages.find(project, root.resolve("Game.java"))).isEmpty();
    ProjectFileManagement.delete(project, root.resolve("Player.java"), true);
    assertThat(root.resolve("Player.java")).doesNotExist();
  }
}
