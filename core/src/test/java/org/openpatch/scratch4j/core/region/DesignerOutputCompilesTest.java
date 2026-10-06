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

/** Designer-generated Text and UISprite lines compile against the library. */
class DesignerOutputCompilesTest {

  @TempDir
  Path tmp;

  @Test
  void textAndUiSpriteInstancesCompile() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "p", jar);
    Path root = tmp.resolve("p");
    Files.writeString(root.resolve("Button.java"), """
        import org.openpatch.scratch.UISprite;

        public class Button extends UISprite {
          public Button() {
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
    Files.writeString(stage, doc.write());

    var project = ScratchProject.open(root);
    var result = new CompilerService().compile(project.javaSources(), List.of(jar),
        tmp.resolve("out"));
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void sheetAndAnimationLinesCompile() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "q", jar);
    Path root = tmp.resolve("q");
    String hero = """
        import org.openpatch.scratch.AnimatedSprite;

        public class Hero extends AnimatedSprite {
          public Hero() {
            // scratch4j:begin setup
            // scratch4j:end setup
          }
        }
        """;
    hero = SpriteAssets.addSheetCostumes(hero, "tile", "assets/images/tiles.png", 32, 32);
    hero = SpriteAssets.addSheetAnimation(hero, "run", "assets/images/hero.png", 6, 24, 32, 1);
    hero = SpriteAssets.putAnimation(hero, "walk", "assets/images/walk%d.png", 3);
    hero = SpriteRegionWriter.setAnimationInterval(hero, 90);
    Files.writeString(root.resolve("Hero.java"), hero);
    var project = ScratchProject.open(root);
    var result = new CompilerService().compile(project.javaSources(), List.of(jar),
        tmp.resolve("out2"));
    assertThat(result.errors()).isEmpty();
  }
}
