package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewProjectTest {

  @TempDir
  Path tmp;

  private Path scratchJar() throws IOException {
    return NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
  }

  @Test
  void createsEveryTemplateWithLibraryAndMetadata() throws IOException {
    for (ProjectTemplate template : ProjectTemplate.values()) {
      NewProject.create(template, tmp, "proj-" + template.name().toLowerCase(), scratchJar());
    }
    for (ProjectTemplate template : ProjectTemplate.values()) {
      Path root = tmp.resolve("proj-" + template.name().toLowerCase());
      assertThat(root.resolve("MyStage.java")).isRegularFile();
      assertThat(root.resolve(".scratch4j/project.json")).isRegularFile();
      assertThat(root.resolve("assets/images")).isDirectory();
      assertThat(root.resolve("assets/sounds")).isDirectory();
      assertThat(Files.list(root.resolve("+libs"))).hasSize(1);
      ScratchProject project = ScratchProject.open(root);
      assertThat(project.stageClasses()).contains("MyStage");
      assertThat(project.startStage()).isEqualTo("MyWindow");
      assertThat(project.firstStage()).isEqualTo("MyStage");
    }
  }

  @Test
  void layoutMatchesTemplate() throws IOException {
    NewProject.create(ProjectTemplate.BLUEJ_STARTER, tmp, "bluejproj", scratchJar());
    NewProject.create(ProjectTemplate.VSCODE_STARTER, tmp, "vscodeproj", scratchJar());
    NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "plainproj", scratchJar());
    assertThat(ScratchProject.open(tmp.resolve("bluejproj")).layout())
        .isEqualTo(ProjectLayout.BLUEJ);
    assertThat(ScratchProject.open(tmp.resolve("vscodeproj")).layout())
        .isEqualTo(ProjectLayout.VSCODE);
    assertThat(ScratchProject.open(tmp.resolve("plainproj")).layout())
        .isEqualTo(ProjectLayout.PLAIN);
  }

  @Test
  void classesFirstTemplateHasPlayerSpriteWithRegions() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "cfproj", scratchJar());
    String player = Files.readString(tmp.resolve("cfproj/Player.java"));
    assertThat(player).contains("class Player extends Sprite");
    assertThat(player).contains("// scratch4j:begin setup");
    assertThat(player).contains("addCostume(\"bunny1_stand\")");
    assertThat(ScratchProject.open(tmp.resolve("cfproj")).spriteClasses())
        .contains("Player");
  }

  @Test
  void imperativeTemplateHasStageRegions() throws IOException {
    NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "improj", scratchJar());
    String stage = Files.readString(tmp.resolve("improj/MyStage.java"));
    assertThat(stage).contains("// scratch4j:begin fields");
    assertThat(stage).contains("// scratch4j:begin setup");
  }

  @Test
  void refusesToOverwriteExistingFolder() throws IOException {
    NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "dup", null);
    assertThatThrownBy(() -> NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "dup", null))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void everyTemplateCompilesAgainstTheScratchJar() throws IOException {
    var compiler = new org.openpatch.scratch4j.core.compile.CompilerService();
    for (ProjectTemplate template : ProjectTemplate.values()) {
      Path root = tmp.resolve("compile-" + template.name().toLowerCase());
      NewProject.create(template, tmp, root.getFileName().toString(), scratchJar());
      ScratchProject project = ScratchProject.open(root);
      var result = compiler.compile(project.javaSources(),
          List.copyOf(project.libs()), root.resolve(".scratch4j/build/classes"));
      assertThat(result.success())
          .as("template %s compiles; diagnostics: %s", template, result.diagnostics())
          .isTrue();
    }
  }
}
