package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DesignerRegionsTest {

  @TempDir
  Path tmp;

  @Test
  void stageGetsFieldsAndSetupAtTheEndOfItsConstructor() {
    String source = """
        import org.openpatch.scratch.Stage;

        public class World extends Stage {

          private int score;

          public World(String map) {
            super(640, 360);
            if (map != null) {
              score = 1; // a } in a comment and "}" in a string
            }
          }

          public void run() {
          }
        }
        """;
    String result = DesignerRegions.ensure(source);
    assertThat(result).isEqualTo("""
        import org.openpatch.scratch.Stage;

        public class World extends Stage {

          // scratch4j:begin fields (managed by the stage designer)
          // scratch4j:end fields

          private int score;

          public World(String map) {
            super(640, 360);
            if (map != null) {
              score = 1; // a } in a comment and "}" in a string
            }

            // scratch4j:begin setup (managed by the stage designer)
            // scratch4j:end setup
          }

          public void run() {
          }
        }
        """);
    assertThat(DesignerRegions.ensure(result)).isEqualTo(result);
    assertThat(StageDocument.read(result).write(StageDocument.read(result).model()))
        .isEqualTo(result);
  }

  @Test
  void spriteWithoutConstructorGetsOneAndWindowSettingsMoveIntoTheirRegion() {
    String sprite = """
        import org.openpatch.scratch.Sprite;

        public class Coin extends Sprite {
          public void run() {
          }
        }
        """;
    assertThat(DesignerRegions.ensure(sprite)).isEqualTo("""
        import org.openpatch.scratch.Sprite;

        public class Coin extends Sprite {
          public Coin() {
            // scratch4j:begin setup (managed by the stage designer)
            // scratch4j:end setup
          }

          public void run() {
          }
        }
        """);
    String window = """
        public class Game extends Window {
          public Game() {
            super(640, 360, "assets");
            this.setStage(new World());
          }

          public static void main(String[] args) {
            Window.useTextureSampling(TextureSampling.POINT);
            Text.useFont("assets/font.ttf", 11);
            Window.useFullScreen();
            new Game();
          }
        }
        """;
    String managed = DesignerRegions.ensure(window);
    assertThat(WindowDocument.isManaged(managed)).isTrue();
    var settings = WindowDocument.read(managed);
    assertThat(settings.fullScreen()).isTrue();
    assertThat(settings.pixelArt()).isTrue();
    assertThat(settings.startStage()).isEqualTo("World");
    assertThat(managed).contains("    super(640, 360, \"assets\");\n\n"
        + "    // scratch4j:begin window (managed by the project settings)\n"
        + "    this.setStage(new World());\n"
        + "    // scratch4j:end window\n  }");
    assertThat(managed).contains("    Text.useFont(\"assets/font.ttf\", 11);\n"
        + "    // scratch4j:begin options (managed by the project settings)\n"
        + "    Window.useTextureSampling(TextureSampling.POINT);\n"
        + "    Window.useFullScreen();\n"
        + "    // scratch4j:end options\n"
        + "    new Game();");
  }

  @Test
  void everyBundledTemplateGetsRegionsAndStillCompiles() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    for (var template : BundledTemplates.list()) {
      // Region generation and Java compilation also cover examples requiring newer artwork.
      // Runtime asset availability is checked separately by BundledTemplatesTest.
      Path root = BundledTemplates.create(template.id(), tmp, template.id(), null);
      ScratchProject project = ScratchProject.open(root);
      for (Path source : project.javaSources()) {
        String text = Files.readString(source);
        if (text.matches("(?s).*public\\s+class\\s+\\w+\\s+extends\\s+(Stage|Sprite|"
            + "AnimatedSprite|UISprite)\\b.*")) {
          assertThat(RegionParser.has(text, "setup")).as(source.toString()).isTrue();
          if (text.contains("extends Stage")) {
            assertThat(StageDocument.isDesignerEditable(text)).as(source.toString()).isTrue();
          }
        }
      }
      var result = new CompilerService().compile(project.javaSources(),
          List.of(jar), tmp.resolve("out-" + template.id()));
      assertThat(result.errors()).as(template.id()).isEmpty();
    }
  }

  @Test
  void spritesThroughProjectSuperclassesGetTheirRegions() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    assertThat(DesignerRegions.kindOf(root, "Bamboo")).isEqualTo("Sprite");
    assertThat(DesignerRegions.kindOf(root, "World")).isEqualTo("Stage");
    assertThat(DesignerRegions.kindOf(root, "GameState")).isNull();
    String bamboo = Files.readString(root.resolve("Bamboo.java"));
    assertThat(SpriteAssets.list(bamboo)).hasSize(4).allMatch(SpriteAssets.Entry::managed);
    // a hand-written subclass gets its region from the sprite editor's first write
    String hand = """
        public class Boss extends Enemy {
          public Boss() {
            super(0, 0);
            this.addCostume("boss", "assets/boss.png");
          }
        }
        """;
    assertThat(DesignerRegions.ensureSprite(hand)).contains(
        "    // scratch4j:begin setup (managed by the stage designer)\n"
        + "    this.addCostume(\"boss\", \"assets/boss.png\");\n"
        + "    // scratch4j:end setup\n");
  }
}
