package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Visual edits change only the statements whose values changed: comments and
 * code the designer does not manage stay where they are.
 */
class StageRegionEditTest {

  /** A stage with a comment, a stage call and a sprite the designer leaves to the code. */
  private static final String STAGE = """
      public class Pond extends Stage {
        // begin stage designer
        private Player player;
        private Coin coin;
        private int score;
        // end stage designer

        public Pond() {
          super(480, 360);
          // begin stage designer
          this.addBackdrop("background");
          // the hero
          player = new Player("hero", -1.5f);
          player.setPosition(-120, 40); // start left
          this.setTint(60);
          this.add(player);
          coin = new Coin();
          coin.setPosition(10, 10);
          coin.setColor(255, 0, 0);
          this.add(coin);
          // end stage designer
        }
      }
      """;

  @Test
  void onlyTheSpriteTheCodeTouchesIsLeftToTheCode() {
    StageDocument document = StageDocument.read(STAGE);
    assertThat(document.codeOwnedSprites()).containsExactly("coin");
    assertThat(document.isSpriteEditable("player")).isTrue();
    // int score is a value, not a sprite
    assertThat(document.model().sprites().byName("score")).isNull();
    assertThat(document.model().sprites().byName("player").constructorArguments())
        .containsExactly("\"hero\"", "-1.5f");
  }

  @Test
  void movingASpriteChangesOnlyItsPosition() {
    StageDocument document = StageDocument.read(STAGE);
    StageModel model = document.model();
    model.sprites().byName("player").setPosition(5, 6);
    String moved = document.writeOn(STAGE, model);
    assertThat(moved).isEqualTo(STAGE.replace("player.setPosition(-120, 40);",
        "player.setPosition(5, 6);"));
  }

  @Test
  void aSpriteLeftToTheCodeCannotBeChangedVisually() {
    StageDocument document = StageDocument.read(STAGE);
    StageModel model = document.model();
    model.sprites().byName("coin").setPosition(0, 0);
    assertThatThrownBy(() -> document.writeOn(STAGE, model))
        .hasMessageContaining("coin");
  }

  @Test
  void reorderingAddsEverySpriteAfterItIsCreated() {
    StageDocument document = StageDocument.read(STAGE);
    StageModel model = document.model();
    SpriteRef player = model.sprites().byName("player");
    model.sprites().remove(player);
    model.sprites().add(player);
    String reordered = document.writeOn(STAGE, model);
    assertThat(reordered).doesNotContain("    this.add(player);\n    coin = new Coin();")
        .contains("""
                coin.setColor(255, 0, 0);
                this.add(coin);
                this.add(player);
                // end stage designer
            """);
    assertThat(reordered.indexOf("this.add(coin);"))
        .isGreaterThan(reordered.indexOf("coin = new Coin();"));
    assertThat(StageDocument.read(reordered).model().sprites().stream()
        .map(SpriteRef::name).toList()).containsExactly("coin", "player");
  }

  @Test
  void anAddInsideCodeKeepsItsLayerOrder() {
    String nested = STAGE.replace("    this.add(coin);\n",
        "    if (true) {\n          this.add(coin);\n        }\n");
    StageDocument document = StageDocument.read(nested);
    StageModel model = document.model();
    SpriteRef player = model.sprites().byName("player");
    model.sprites().remove(player);
    model.sprites().add(player);
    assertThatThrownBy(() -> document.writeOn(nested, model))
        .hasMessageContaining("layer order");
  }

  @Test
  void deletingASpriteTakesItsLinesAndTheirComments() {
    StageDocument document = StageDocument.read(STAGE);
    StageModel model = document.model();
    model.sprites().remove(model.sprites().byName("player"));
    String deleted = document.writeOn(STAGE, model);
    assertThat(deleted).doesNotContain("player").doesNotContain("start left")
        .contains("this.setTint(60);").contains("// the hero").contains("private int score;");
    assertThat(deleted).doesNotContainPattern("(?m)^\\s+$");
  }

  @Test
  void anEditTheCodeMadeMeanwhileWins() {
    StageDocument document = StageDocument.read(STAGE);
    StageModel model = document.model();
    model.sprites().byName("player").setPosition(5, 6);
    String typed = STAGE.replace("player.setPosition(-120, 40);", "player.setPosition(0, 0);");
    assertThatThrownBy(() -> document.writeOn(typed, model))
        .hasMessageContaining("The code changed player");
    // other code typed meanwhile stays, the visual edit applies on top of it
    String other = STAGE.replace("this.setTint(60);", "this.setTint(70);");
    assertThat(document.writeOn(other, model)).contains("this.setTint(70);")
        .contains("player.setPosition(5, 6);");
  }

  @Test
  void windowsLineEndingsStay() {
    String crlf = STAGE.replace("\n", "\r\n");
    StageDocument document = StageDocument.read(crlf);
    StageModel model = document.model();
    SpriteRef player = model.sprites().byName("player");
    model.sprites().remove(player);
    model.sprites().add(player);
    assertThat(document.writeOn(crlf, model).replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void constructorsWithLiteralParametersAreOffered() throws Exception {
    Path root = Files.createTempDirectory("constructors");
    Files.writeString(root.resolve("Racer.java"), """
        public class Racer extends Sprite {
          public Racer() {}
          public Racer(String look, double speed) {}
          public Racer(Track track) {}
          private Racer(int secret) {}
        }
        """);
    var constructors = SpriteConstructors.of(root, "Racer");
    assertThat(constructors).extracting(c -> c.parameters().size()).containsExactly(0, 2);
    assertThat(constructors.get(1).accepts(List.of("\"bee\"", "2.5"))).isTrue();
    assertThat(constructors.get(1).accepts(List.of("2.5", "\"bee\""))).isFalse();
    assertThat(constructors.get(1).accepts(List.of("\"bee\"", "speed"))).isFalse();
    // a class without constructors has Java's empty one
    Files.writeString(root.resolve("Rock.java"), "public class Rock extends Sprite {}\n");
    assertThat(SpriteConstructors.of(root, "Rock")).extracting(c -> c.parameters().size())
        .containsExactly(0);
  }
}
