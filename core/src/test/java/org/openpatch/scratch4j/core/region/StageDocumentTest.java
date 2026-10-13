package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden round-trip tests for the stage designer's region model.
 */
class StageDocumentTest {

  /** A representative stage with managed regions. */
  private static final String PLAN_SAMPLE = """
      public class MyStage extends Stage {
        // begin stage designer
        Player player;
        // end stage designer

        public MyStage() {
          // begin stage designer
          this.addBackdrop("background");
          player = new Player();
          player.setPosition(-120, 40);
          player.setDirection(90);
          player.setSize(80);
          this.add(player);
          // end stage designer
        }
      }
      """;

  @Test
  void codeTheDesignerDoesNotWriteStaysAndIsReported() {
    String source = """
        class Level extends Stage {
          Level() {
            // begin stage designer
            helper();
            // end stage designer
          }
        }
        """;
    StageDocument document = StageDocument.read(source);
    assertThat(document.foreign()).extracting(StageDocument.Foreign::statement)
        .containsExactly("helper();");
    assertThat(document.foreign().get(0).line()).isEqualTo(4);
  }

  @Test
  void unfinishedJavaCannotBeRead() {
    String source = PLAN_SAMPLE.replace("this.add(player);", "this.add(player");
    assertThatThrownBy(() -> StageDocument.read(source))
        .isInstanceOf(RegionStatements.UnsupportedRegionException.class);
  }

  @Test
  void stageSoundsRoundTripThroughManagedSetup() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    StageModel model = doc.model();
    model.addSound(new StageModel.Sound("meow", null));
    model.addSound(new StageModel.Sound("intro", "assets/sounds/intro.wav"));

    String source = doc.write(model);
    assertThat(source).contains("this.addSound(\"meow\");")
        .contains("this.addSound(\"intro\", \"assets/sounds/intro.wav\");");
    assertThat(StageDocument.read(source).model()).isEqualTo(model);
  }

  @Test
  void backdropStretchRoundTripsForBuiltInAndProjectFiles() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    StageModel model = doc.model();
    model.backdrops().clear();
    model.addBackdrop(new StageModel.Backdrop("bg_castle", null, true));
    model.addBackdrop(new StageModel.Backdrop("photo", "assets/images/photo.png", false));

    String source = doc.write(model);
    assertThat(source).contains("this.addBackdrop(\"bg_castle\", \"bg_castle\", true);")
        .contains("this.addBackdrop(\"photo\", \"assets/images/photo.png\", false);");
    assertThat(StageDocument.read(source).model()).isEqualTo(model);
  }

  @Test
  void spriteRotationStyleAndCurrentCostumeRoundTrip() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    SpriteRef player = doc.model().sprites().byName("player");
    player.rotationStyle("RotationStyle.LEFT_RIGHT");
    player.currentCostume("player_walk");

    String source = doc.write();
    // a simple name and an import, like a student would write it
    assertThat(source).contains("player.setRotationStyle(RotationStyle.LEFT_RIGHT);")
        .contains("import org.openpatch.scratch.RotationStyle;\n")
        .doesNotContain("org.openpatch.scratch.RotationStyle.")
        .contains("player.switchCostume(\"player_walk\");");
    assertThat(StageDocument.read(source).model()).isEqualTo(doc.model());
    assertThat(StageDocument.read(source).write()).isEqualTo(source);
  }

  @Test
  void aQualifiedRotationStyleFromOlderVersionsIsWrittenShort() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    doc.model().sprites().byName("player")
        .rotationStyle("org.openpatch.scratch.RotationStyle.DONT");
    String source = doc.write();
    assertThat(source).contains("player.setRotationStyle(RotationStyle.DONT);")
        .doesNotContain("org.openpatch.scratch.RotationStyle.DONT");
  }

  @Test
  void aWildcardImportIsEnough() {
    String sample = "import org.openpatch.scratch.*;\n\n" + PLAN_SAMPLE;
    StageDocument doc = StageDocument.read(sample);
    doc.model().sprites().byName("player").rotationStyle("RotationStyle.LEFT_RIGHT");
    assertThat(doc.write()).doesNotContain("import org.openpatch.scratch.RotationStyle;");
  }

  @Test
  void instanceRenameChangesManagedRegionsAndPreservesValues() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    doc.renameInstance("player", "hero");

    String source = doc.write();
    assertThat(source).contains("Player hero;", "hero = new Player();",
        "hero.setPosition(-120, 40);");
    assertThat(source).doesNotContain("Player player;");
    assertThat(StageDocument.read(source).model()).isEqualTo(doc.model());
  }

  @Test
  void instanceRenameRefusesReferencesOutsideManagedRegions() {
    String source = PLAN_SAMPLE.replace("  public MyStage() {",
        "  void run() { player.hide(); }\n\n  public MyStage() {");
    StageDocument doc = StageDocument.read(source);
    assertThatThrownBy(() -> doc.renameInstance("player", "hero"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hand-written");
    assertThat(doc.source()).isEqualTo(source);
  }

  @Test
  void planSampleRoundTripsIdentically() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    assertThat(doc.write()).isEqualTo(PLAN_SAMPLE);
  }

  @Test
  void planSampleParsesIntoTheModel() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    StageModel model = doc.model();
    assertThat(model.width()).isEqualTo(480);
    assertThat(model.height()).isEqualTo(360);
    assertThat(model.backdrops()).containsExactly(new StageModel.Backdrop("background", null));
    assertThat(model.sprites()).hasSize(1);
    SpriteRef player = model.sprites().byName("player");
    assertThat(player.type()).isEqualTo("Player");
    assertThat(player.hasPosition()).isTrue();
    assertThat(player.x()).isEqualTo(-120);
    assertThat(player.y()).isEqualTo(40);
    assertThat(player.direction()).isEqualTo(90);
    assertThat(player.size()).isEqualTo(80);
    assertThat(player.isVisible()).isTrue();
    assertThat(player.isAdded()).isTrue();
  }

  @Test
  void imperativeTemplateRoundTrips() {
    String source = """
        import org.openpatch.scratch.KeyCode;
        import org.openpatch.scratch.Sprite;
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {

          // begin stage designer
          // end stage designer

          public MyStage() {
              this.addBackdrop("background");

            // begin stage designer
            // end stage designer
          }

          public void run() {
          }

          public static void main(String[] args) {
            new MyStage();
          }
        }
        """;
    StageDocument doc = StageDocument.read(source);
    assertThat(doc.model().sprites()).isEmpty();
    assertThat(doc.write()).isEqualTo(source);
  }

  @Test
  void classesFirstTemplateRoundTrips() {
    String source = """
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {

          // begin stage designer
          Player player;
          // end stage designer

          public MyStage() {
  
            // begin stage designer
            this.addBackdrop("background");
            player = new Player();
            this.add(player);
            // end stage designer
          }

          public void run() {
          }

          public static void main(String[] args) {
            new MyStage();
          }
        }
        """;
    StageDocument doc = StageDocument.read(source);
    assertThat(doc.model().sprites()).hasSize(1);
    assertThat(doc.write()).isEqualTo(source);
  }

  @Test
  void modelSurvivesWriteThenRead() {
    StageModel model = StageModel.create();
    model.addBackdrop(new StageModel.Backdrop("background", null));
    model.addBackdrop(new StageModel.Backdrop("desert", "assets/desert.png"));
    SpriteRef enemy = new SpriteRef("enemy1", "Enemy");
    enemy.instantiated(true);
    enemy.costume("alienGreen_stand");
    enemy.setPosition(66.5, -3.25);
    enemy.direction(-90);
    enemy.size(33.3);
    enemy.added(true);
    model.addSprite(enemy);

    String source = StageDocument.read(PLAN_SAMPLE).write(model);
    StageModel reloaded = StageDocument.read(source).model();
    assertThat(reloaded).isEqualTo(model);
  }

  @Test
  void editingTheModelChangesOnlyTheRegions() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    StageModel edited = StageModel.create();
    edited.size(doc.model().width(), doc.model().height());
    edited.addBackdrop(new StageModel.Backdrop("background", null));
    SpriteRef moved = new SpriteRef("player", "Player");
    moved.instantiated(true);
    moved.setPosition(10, -20);
    moved.added(true);
    edited.addSprite(moved);

    String updated = doc.write(edited);
    assertThat(updated)
        .contains("player.setPosition(10, -20);")
        .doesNotContain("player.setDirection(90);")
        .doesNotContain("player.setSize(80);")
        .contains("public MyStage() {");
  }

  @Test
  void fractionalNumbersRoundTripExactly() {
    StageModel model = StageModel.create();
    SpriteRef ref = new SpriteRef("p", "Player");
    ref.instantiated(true);
    ref.setPosition(66.5, -3.25);
    ref.size(33.3);
    ref.added(true);
    model.addSprite(ref);
    String source = StageDocument.read(PLAN_SAMPLE).write(model);
    assertThat(source).contains("p.setPosition(66.5, -3.25);").contains("p.setSize(33.3);");
    assertThat(StageDocument.read(source).model()).isEqualTo(model);
  }

  @Test
  void commentsInTheRegionsAreKeptNotRejected() {
    String commented = PLAN_SAMPLE.replace("player = new Player();",
        "player = new Player(); // hand note");
    StageDocument document = StageDocument.read(commented);
    assertThat(document.foreign()).isEmpty();
    StageModel moved = document.model();
    moved.sprites().byName("player").setPosition(0, 0);
    assertThat(document.write(moved)).contains("player = new Player(); // hand note")
        .contains("player.setPosition(0, 0);");
  }

  /** Whether the designer leaves the sprite to the code in that source. */
  private static boolean leftToCode(String source, String sprite) {
    return !StageDocument.read(source).isSpriteEditable(sprite);
  }

  @Test
  void aSpriteTheCodeChangesIsLeftToTheCode() {
    String foreign = PLAN_SAMPLE.replace("player.setSize(80);",
        "player.setColor(255, 0, 0);");
    assertThat(leftToCode(foreign, "player")).isTrue();
    assertThat(StageDocument.read(foreign).foreign()).hasSize(1);
  }

  @Test
  void aSpriteThatIsNeverAddedIsLeftToTheCode() {
    String incomplete = PLAN_SAMPLE.replace("this.add(player);", "");
    assertThat(leftToCode(incomplete, "player")).isTrue();
  }

  @Test
  void callsOnUnknownSpritesAreTheCodes() {
    String unknown = PLAN_SAMPLE.replace("player.setDirection(90);", "ghost.setDirection(90);");
    StageDocument document = StageDocument.read(unknown);
    assertThat(document.foreign()).extracting(StageDocument.Foreign::statement)
        .containsExactly("ghost.setDirection(90);");
    assertThat(document.isSpriteEditable("player")).isTrue();
  }

  @Test
  void newTypeMustMatchTheField() {
    String mismatch = PLAN_SAMPLE.replace("player = new Player();", "player = new Enemy();");
    assertThat(leftToCode(mismatch, "player")).isTrue();
  }

  @Test
  void uiSpriteWidthAndHeightRoundTrip() {
    String source = PLAN_SAMPLE.replace("Player player;", "Player player;\n  Button button;")
        .replace("    this.add(player);\n", "    this.add(player);\n"
            + "    button = new Button();\n"
            + "    button.setPosition(0, -150);\n"
            + "    button.setWidth(200);\n"
            + "    button.setHeight(48.5);\n"
            + "    this.add(button);\n");
    StageDocument doc = StageDocument.read(source);
    SpriteRef button = doc.model().sprites().byName("button");
    assertThat(button.width()).isEqualTo(200);
    assertThat(button.height()).isEqualTo(48.5);
    assertThat(doc.write()).isEqualTo(source);
    button.clearHeight();
    String written = doc.write();
    assertThat(written).contains("button.setWidth(200);").doesNotContain("setHeight");
    assertThat(StageDocument.read(written).model()).isEqualTo(doc.model());
  }

  @Test
  void textObjectsRoundTripAndGetTheirImports() {
    StageDocument doc = StageDocument.read(PLAN_SAMPLE);
    SpriteRef title = new SpriteRef("title", "Text");
    title.text("Score: \\\"0\\\"\\nGo!");
    title.setPosition(-200, 150);
    title.textWidth(180);
    title.textStyle("TextStyle.BOX");
    title.instantiated(true);
    title.added(true);
    doc.model().addSprite(title);

    String written = doc.write();
    assertThat(written)
        .startsWith("import org.openpatch.scratch.Text;\nimport org.openpatch.scratch.TextStyle;\n")
        .contains("Text title;")
        .contains("title = new Text(\"Score: \\\"0\\\"\\nGo!\", -200, 150, 180, TextStyle.BOX);")
        .contains("this.add(title);");
    StageDocument again = StageDocument.read(written);
    assertThat(again.model()).isEqualTo(doc.model());
    // imports are added once
    assertThat(again.write()).isEqualTo(written);
  }

  @Test
  void textWithoutStyleKeepsTheShortConstructorAndWildcardImport() {
    String source = "import org.openpatch.scratch.*;\n\n" + PLAN_SAMPLE
        .replace("Player player;", "Player player;\n  Text hint;")
        .replace("    this.add(player);\n",
            "    this.add(player);\n    hint = new Text(\"Press space\", 0, 0, 0);\n"
            + "    hint.hide();\n    this.add(hint);\n");
    StageDocument doc = StageDocument.read(source);
    SpriteRef hint = doc.model().sprites().byName("hint");
    assertThat(hint.isText()).isTrue();
    assertThat(hint.isVisible()).isFalse();
    assertThat(doc.write()).isEqualTo(source);
  }

  @Test
  void textWithoutArgumentsIsNotEditable() {
    String bare = PLAN_SAMPLE.replace("Player player;", "Player player;\n  Text t;")
        .replace("    this.add(player);\n",
            "    this.add(player);\n    t = new Text();\n    this.add(t);\n");
    assertThat(leftToCode(bare, "t")).isTrue();
  }

  @Test
  void textConstructorOnASpriteFieldIsNotEditable() {
    String bad = PLAN_SAMPLE.replace("player = new Player();",
        "player = new Text(\"x\", 0, 0, 0);");
    assertThat(leftToCode(bad, "player")).isTrue();
  }
}
