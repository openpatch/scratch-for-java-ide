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
  void stagesAndSpritesMayInheritThroughProjectClasses() throws IOException {
    // the shape of the Spielwerkstatt: a main class shows World, World extends Level extends Stage
    Files.createDirectories(tmp.resolve("game"));
    Path root = tmp.resolve("game");
    Files.writeString(root.resolve("Main.java"), """
        void main() {
           Window window = new Window(768, 432);
           window.setStage(new World());
        }
        """);
    Files.writeString(root.resolve("Level.java"), "public class Level extends Stage {}");
    Files.writeString(root.resolve("World.java"), "public class World extends Level {}");
    Files.writeString(root.resolve("Cave.java"), "public class Cave extends Level {}");
    Files.writeString(root.resolve("Item.java"),
        "public abstract class Item extends AnimatedSprite {}");
    Files.writeString(root.resolve("Coin.java"), "public class Coin extends Item {}");
    Files.writeString(root.resolve("Rock.java"),
        "public class Rock extends org.openpatch.scratch.Sprite {}");
    Files.writeString(root.resolve("Tree.java"), "public class Tree<T> extends Rock {}");
    Files.writeString(root.resolve("Score.java"), "public class Score extends Object {}");
    ScratchProject project = ScratchProject.open(root);
    project.settings().startStage = "Main";

    assertThat(project.stageClasses()).containsExactly("Cave", "Level", "World");
    assertThat(project.spriteClasses()).containsExactly("Coin", "Item", "Rock", "Tree");
    assertThat(project.startStage()).isEqualTo("Main");
    // the stage main shows, not the alphabetically first one
    assertThat(project.firstStage()).isEqualTo("World");

    // the start star rewrites main's setStage; the program still starts in Main
    project.setStartStage("Cave");
    assertThat(Files.readString(root.resolve("Main.java")))
        .contains("window.setStage(new Cave());")
        .contains("new Window(768, 432)");
    assertThat(project.startStage()).isEqualTo("Main");
    assertThat(project.firstStage()).isEqualTo("Cave");
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

  @Test
  void theWindowIsTheOneTheStartClassMakesInMain() throws IOException {
    Path root = tmp.resolve("mains");
    Files.createDirectories(root);
    Files.writeString(root.resolve("Welt.java"), """
        public class Welt extends org.openpatch.scratch.Stage {
          public static void main(String[] args) {
            Window fenster = new Window(768, 432);
            fenster.setStage(new Welt());
          }
        }
        """);
    Files.writeString(root.resolve("Main.java"), """
        void main() {
          Window window = new Window(400, 300);
          window.setStage(new Level2());
        }
        """);
    Files.writeString(root.resolve("Level2.java"),
        "public class Level2 extends org.openpatch.scratch.Stage {}");
    ScratchProject project = ScratchProject.open(root);
    // the class the program starts with: Main
    assertThat(project.mainWindowSource()).isEqualTo(root.resolve("Main.java"));
    // Welt starts through its own main
    project.settings().startStage = "Welt";
    project.settings().save(root);
    assertThat(ScratchProject.open(root).mainWindowSource()).isEqualTo(root.resolve("Welt.java"));

    Path none = tmp.resolve("none");
    Files.createDirectories(none);
    Files.writeString(none.resolve("Welt.java"),
        "public class Welt extends org.openpatch.scratch.Stage {}");
    assertThat(ScratchProject.open(none).mainWindowSource()).isNull();
  }

  @Test
  void aCompactMainStartsBeforeTheStageItShows() throws IOException {
    Path root = tmp.resolve("compact");
    Files.createDirectories(root);
    Files.writeString(root.resolve("Main.java"), """
        void main() {
          Window fenster = new Window(768, 432);
          fenster.setStage(new Welt());
        }
        """);
    Files.writeString(root.resolve("Welt.java"),
        "public class Welt extends org.openpatch.scratch.Stage {}");
    Files.writeString(root.resolve("Level2.java"),
        "public class Level2 extends org.openpatch.scratch.Stage {}");
    // nothing chosen: main, not the first stage
    assertThat(ScratchProject.open(root).startStage()).isEqualTo("Main");

    // the stage main shows was saved as the start: main still sets up the window
    ScratchProject project = ScratchProject.open(root);
    project.settings().startStage = "Welt";
    project.settings().save(root);
    assertThat(project.startStage()).isEqualTo("Main");
    assertThat(project.firstStage()).isEqualTo("Welt");

    project.setStartStage("Level2");
    assertThat(root.resolve("Main.java")).content().contains("setStage(new Level2())");
    ScratchProject reloaded = ScratchProject.open(root);
    assertThat(reloaded.startStage()).isEqualTo("Main");
    assertThat(reloaded.firstStage()).isEqualTo("Level2");

    // a stage main does not show stays the start
    reloaded.settings().startStage = "Welt";
    assertThat(reloaded.startStage()).isEqualTo("Welt");
  }
}
