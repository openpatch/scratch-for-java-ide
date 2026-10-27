package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

/** Designer-generated Text and UISprite lines compile against the library. */
class DesignerOutputCompilesTest {

  @TempDir
  Path tmp;

  @Test
  void textAndUiSpriteInstancesCompile() throws Exception {
    Path jar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "p", jar);
    Path root = tmp.resolve("p");
    Files.writeString(root.resolve("Button.java"), """
        import org.openpatch.scratch.Layer;
        import org.openpatch.scratch.Sprite;

        public class Button extends Sprite {
          public Button() {
            this.setLayer(Layer.UI);
            this.addCostume("button", "assets/images/button.png");
            this.setNineSlice(8, 8, 8, 8);
          }
        }
        """);
    Path stage = root.resolve("MyStage.java");
    StageDocument doc = StageDocument.read(Files.readString(stage));
    SpriteRef button = new SpriteRef("button", "Button");
    button.instantiated(true);
    button.added(true);
    button.setPosition(0, -140);
    button.width(220);
    button.height(50);
    doc.model().addSprite(button);
    SpriteRef title = new SpriteRef("title", "Text");
    title.instantiated(true);
    title.added(true);
    title.text("Level 1\\nGo!");
    title.setPosition(-220, 160);
    title.textWidth(200);
    title.textStyle("TextStyle.BOX");
    doc.model().addSprite(title);
    doc.model().color(new StageModel.BackgroundColor(76, 151, 255));
    Files.writeString(stage, doc.write());
    assertThat(Files.readString(stage)).contains("this.setColor(76, 151, 255);");

    var project = ScratchProject.open(root);
    var result = new CompilerService().compile(project.javaSources(), List.of(jar),
        tmp.resolve("out"));
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void sheetAndAnimationLinesCompile() throws Exception {
    Path jar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "q", jar);
    Path root = tmp.resolve("q");
    String hero = """
        import org.openpatch.scratch.Sprite;

        public class Hero extends Sprite {
          public Hero() {
            // begin sprite editor
            // end sprite editor
          }
        }
        """;
    hero = SpriteAssets.addSheetCostumes(hero, "tile", "assets/images/tiles.png", 32, 32);
    hero = SpriteAssets.addSheetAnimation(hero, "run", "assets/images/hero.png", 6, 24, 32, 1, 8);
    hero = SpriteAssets.putAnimation(hero, "walk", "assets/images/walk%d.png", 3);
    hero = SpriteRegionWriter.setAnimationInterval(hero, 90);
    Files.writeString(root.resolve("Hero.java"), hero);
    var project = ScratchProject.open(root);
    var result = new CompilerService().compile(project.javaSources(), List.of(jar),
        tmp.resolve("out2"));
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void windowSettingsCompile() throws Exception {
    Path jar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "w", jar);
    Path root = tmp.resolve("w");
    var all = new WindowDocument.Settings(640, 480, "MyStage", true, true,
        "assets/images/logo.png", true);
    Files.writeString(root.resolve("MyWindow.java"), WindowDocument.create("MyWindow", all));
    Files.writeString(root.resolve("Main.java"), MainWindow.write("""
        import org.openpatch.scratch.*;

        public class Main {
          public static void main(String[] args) {
            Window window = new Window(480, 360);
            window.setStage(new MyStage());
          }
        }
        """, all));
    var project = ScratchProject.open(root);
    var result = new CompilerService().compile(project.javaSources(), List.of(jar),
        tmp.resolve("out3"));
    assertThat(result.errors()).isEmpty();
  }
}
