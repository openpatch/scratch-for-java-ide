package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageManagementTest {

  @TempDir Path root;

  private ScratchProject project() throws Exception {
    Files.writeString(root.resolve("Stage.java"), "class Stage { Stage(int w, int h) {} }");
    Files.writeString(root.resolve("Level1.java"), """
        class Level1 extends Stage {
          Level1() { super(480, 360); }
          Level1 copy() { return new Level1(); }
        }
        """);
    Files.writeString(root.resolve("Other.java"), """
        class Other {
          Level1 next = new Level1();
          int Level1 = 3;
          String label = "Level1"; // Level1 in a comment
        }
        """);
    return ScratchProject.open(root);
  }

  @Test
  void duplicatesOnlyTheStageSourceAndItsSelfReferences() throws Exception {
    ScratchProject project = project();
    Path duplicate = StageManagement.duplicate(project, "Level1", "Level2");

    assertThat(Files.readString(duplicate)).contains("class Level2 extends Stage")
        .contains("Level2()", "new Level2()");
    assertThat(Files.readString(root.resolve("Other.java"))).contains("new Level1()");
    assertThat(Files.readString(root.resolve("Level1.java"))).contains("class Level1");
  }

  @Test
  void renamesResolvedTypesAndStartStageWithoutTouchingVariablesOrStrings() throws Exception {
    ScratchProject project = project();
    project.setStartStage("Level1");
    Path renamed = StageManagement.rename(project, "Level1", "Level2");

    assertThat(Files.exists(root.resolve("Level1.java"))).isFalse();
    assertThat(Files.readString(renamed)).contains("class Level2", "Level2()",
        "new Level2()");
    assertThat(Files.readString(root.resolve("Other.java")))
        .contains("Level2 next = new Level2()")
        .contains("int Level1 = 3", "\"Level1\"", "// Level1 in a comment");
    assertThat(project.startStage()).isEqualTo("Level2");
    assertThat(Files.list(root.resolve(".scratch4j/trash"))).isNotEmpty();
  }

  @Test
  void refusesToDeleteAReferencedStageAndTrashesAnUnreferencedOne() throws Exception {
    ScratchProject project = project();
    assertThatThrownBy(() -> StageManagement.delete(project, "Level1"))
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("refer");
    Files.writeString(root.resolve("Other.java"), "class Other {}\n");
    project.setStartStage("Level1");

    Path trash = StageManagement.delete(project, "Level1");
    assertThat(Files.exists(root.resolve("Level1.java"))).isFalse();
    assertThat(Files.readString(trash)).contains("class Level1");
    assertThat(project.settings().startStage).isEmpty();
  }

  @Test
  void leavesProjectUntouchedWhenJavaHasErrors() throws Exception {
    ScratchProject project = project();
    Files.writeString(root.resolve("Other.java"), "class Other { broken }\n");
    assertThatThrownBy(() -> StageManagement.rename(project, "Level1", "Level2"))
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("Fix Java errors");
    assertThat(Files.exists(root.resolve("Level1.java"))).isTrue();
    assertThat(Files.exists(root.resolve("Level2.java"))).isFalse();
  }

  @Test
  void renamesAnyClassWithStaticAndTypeReferences() throws Exception {
    ScratchProject project = project();
    Files.writeString(root.resolve("Helper.java"), """
        class Helper {
          static int twice(int x) { return x * 2; }
        }
        """);
    Files.writeString(root.resolve("User.java"), """
        class User {
          Helper helper = new Helper();
          int n = Helper.twice(2);
          String name = "Helper";
        }
        """);
    project = ScratchProject.open(root);
    Path renamed = StageManagement.renameClass(project, "Helper", "Tools");

    assertThat(renamed.getFileName().toString()).isEqualTo("Tools.java");
    assertThat(Files.readString(renamed)).contains("class Tools");
    assertThat(Files.readString(root.resolve("User.java")))
        .contains("Tools helper = new Tools();", "Tools.twice(2)", "\"Helper\"");
    assertThatThrownBy(() -> StageManagement.rename(ScratchProject.open(root), "Tools", "Util"))
        .hasMessageContaining("Not a stage");
  }
}
