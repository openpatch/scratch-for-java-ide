package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScratchProjectTest {

  @TempDir
  Path tmp;

  @Test
  void discoversStagesAndSpritesOfAFlatProject() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "proj", null);
    ScratchProject project = ScratchProject.open(tmp.resolve("proj"));
    assertThat(project.name()).isEqualTo("proj");
    assertThat(project.stageClasses()).containsExactly("MyStage");
    assertThat(project.spriteClasses()).containsExactly("Player");
    // the program starts in the window class, which shows MyStage first
    assertThat(project.startStage()).isEqualTo("MyWindow");
    assertThat(project.windowClass()).isEqualTo("MyWindow");
    assertThat(project.firstStage()).isEqualTo("MyStage");
    assertThat(project.javaSources())
        .extracting(p -> p.getFileName().toString())
        .containsExactlyInAnyOrder("MyStage.java", "Player.java", "MyWindow.java");
  }

  @Test
  void startStagePersistsInSettings() throws IOException {
    NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "proj", null);
    Path root = tmp.resolve("proj");
    Files.writeString(root.resolve("Level2.java"), """
        import org.openpatch.scratch.Stage;

        public class Level2 extends Stage {
          public Level2() {
            super(480, 360);
          }
        }
        """);
    ScratchProject project = ScratchProject.open(root);
    // the window shows MyStage first, it wins over the alphabetically-first Level2
    assertThat(project.firstStage()).isEqualTo("MyStage");

    // the start star rewrites the window's setStage; the program still starts in MyWindow
    project.setStartStage("Level2");
    ScratchProject reloaded = ScratchProject.open(root);
    assertThat(reloaded.firstStage()).isEqualTo("Level2");
    assertThat(reloaded.startStage()).isEqualTo("MyWindow");
    assertThat(Files.readString(root.resolve("MyWindow.java")))
        .contains("this.setStage(new Level2());");

    // without a window class the start class lives in project.json
    Files.delete(root.resolve("MyWindow.java"));
    ScratchProject plain = ScratchProject.open(root);
    plain.setStartStage("Level2");
    assertThat(ScratchProject.open(root).startStage()).isEqualTo("Level2");
  }

  @Test
  void ignoresGeneratedAndBuildDirsWhenScanning() throws IOException {
    NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "proj", null);
    Path root = tmp.resolve("proj");
    Files.createDirectories(root.resolve(".scratch4j/build"));
    Files.writeString(root.resolve(".scratch4j/build/Gen.java"),
        "class Gen extends org.openpatch.scratch.Stage {}");
    Files.createDirectories(root.resolve("+libs"));
    Files.writeString(root.resolve("+libs/Lib.java"), "class Lib {}");
    ScratchProject project = ScratchProject.open(root);
    assertThat(project.stageClasses()).containsExactly("MyStage");
  }

  @Test
  void projectWithoutStagesHasEmptyStartStage() throws IOException {
    Files.createDirectories(tmp.resolve("empty"));
    assertThat(ScratchProject.open(tmp.resolve("empty")).startStage()).isEmpty();
  }

  @Test
  void corruptedSettingsFallBackToDefaults() throws IOException {
    NewProject.create(ProjectTemplate.IMPERATIVE, tmp, "proj", null);
    Path root = tmp.resolve("proj");
    Files.writeString(root.resolve(".scratch4j/project.json"), "{ not json");
    ScratchProject project = ScratchProject.open(root);
    assertThat(project.settings().startStage).isEmpty();
    assertThat(project.settings().flavourEnum()).isEqualTo(LibraryFlavour.STANDARD);
  }

  @Test
  void windowSettingsPersistInProjectMetadata() throws IOException {
    Files.createDirectories(tmp.resolve("settings"));
    ScratchProject project = ScratchProject.open(tmp.resolve("settings"));
    project.settings().fullScreen = true;
    project.settings().pixelArt = true;
    project.settings().debugOnStart = true;
    project.settings().splashLogo = "assets/images/logo.png";
    project.settings().save(project.root());

    ProjectSettings loaded = ScratchProject.open(project.root()).settings();
    assertThat(loaded.fullScreen).isTrue();
    assertThat(loaded.pixelArt).isTrue();
    assertThat(loaded.debugOnStart).isTrue();
    assertThat(loaded.splashLogo).isEqualTo("assets/images/logo.png");
  }
}
