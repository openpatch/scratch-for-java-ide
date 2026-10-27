package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpriteAssetsTest {

  private static final String SOURCE = """
      public class Player extends AnimatedSprite {
        public Player() {
          this.addCostume("original");
          // begin sprite editor
          this.setSize(80);
          // end sprite editor
        }
      }
      """;

  @Test
  void addsAndRemovesOnlyManagedAssets() {
    String withCostume = SpriteAssets.add(SOURCE, SpriteAssets.Kind.COSTUME,
        "running", "assets/images/running.png", 0);
    String withSound = SpriteAssets.add(withCostume, SpriteAssets.Kind.SOUND,
        "jump", "assets/sounds/jump.wav", 0);
    String withAnimation = SpriteAssets.add(withSound, SpriteAssets.Kind.ANIMATION,
        "walk", "bunny1_walk%d", 2);
    assertThat(withAnimation).contains("this.addCostume(\"running\", \"assets/images/running.png\");")
        .contains("this.addSound(\"jump\", \"assets/sounds/jump.wav\");")
        .contains("this.addAnimation(\"walk\", \"bunny1_walk%d\", 2);")
        .contains("this.setSize(80);");
    var entries = SpriteAssets.list(withAnimation);
    assertThat(entries).hasSize(4);
    assertThat(entries.get(0).managed()).isFalse();
    assertThat(entries.get(1).managed()).isTrue();
    assertThatThrownBy(() -> SpriteAssets.remove(withAnimation, entries.get(0)))
        .isInstanceOf(IllegalArgumentException.class);
    String removed = SpriteAssets.remove(withAnimation, entries.get(1));
    assertThat(removed).doesNotContain("running.png")
        .contains("this.addCostume(\"original\")")
        .contains("this.setSize(80);");
  }

  @Test
  void pathsTheCodeWorksOutAreListedReadOnly() {
    // the Spielwerkstatt's player: the sheet depends on a constructor parameter
    String source = """
        public class Spieler extends Sprite {
          public Spieler(String pFigur) {
            String bild = "assets/actor/character/" + pFigur + "/sprite-sheet.png";
            this.addAnimation("unten", bild, 16, 16, 0, 4, 8, 12);
            this.addAnimation("oben", bild, 16, 16, 1, 5, 9, 13);
            this.addSound("schritt", "assets/audio/step.ogg");
            this.addCostume("bild", pFigur + ".png");
          }
        }
        """;
    var entries = SpriteAssets.list(source);
    assertThat(entries).extracting(SpriteAssets.Entry::name)
        .containsExactly("unten", "oben", "schritt", "bild");
    var unten = entries.get(0);
    assertThat(unten.isComputed()).isTrue();
    assertThat(unten.computed()).isEqualTo("bild");
    assertThat(unten.reference()).isNull();
    assertThat(unten.managed()).isFalse();
    assertThat(unten.isSheetAnimation()).isTrue();
    assertThat(unten.frames()).isEqualTo(4);
    assertThat(unten.row()).isEqualTo(0);
    assertThat(unten.columns()).isTrue();
    assertThat(entries.get(1).row()).isEqualTo(1);
    assertThat(entries.get(2).isComputed()).isFalse();
    assertThat(entries.get(3).computed()).isEqualTo("pFigur + \".png\"");
    assertThatThrownBy(() -> SpriteAssets.remove(source, unten))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateNamesAndInvalidAnimations() {
    assertThatThrownBy(() -> SpriteAssets.add(SOURCE, SpriteAssets.Kind.COSTUME,
        "original", null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SpriteAssets.add(SOURCE, SpriteAssets.Kind.ANIMATION,
        "walk", "bunny1_walk", 2)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsAndRemovesLiteralHitbox() {
    String withHitbox = SpriteRegionWriter.setHitbox(SOURCE, 0, 0, 24, 0, 24, 30);
    assertThat(SpriteAssets.hitbox(withHitbox)).containsExactly(0, 0, 24, 0, 24, 30);
    assertThat(SpriteAssets.hasUnmanagedHitbox(withHitbox)).isFalse();
    assertThat(SpriteRegionWriter.removeHitbox(withHitbox)).doesNotContain("setHitbox")
        .contains("this.setSize(80);");
    String handwritten = SOURCE.replace("this.addCostume(\"original\");",
        "this.addCostume(\"original\");\n      this.setHitbox(0, 0, 24, 0, 24, 30);");
    assertThat(SpriteAssets.hasUnmanagedHitbox(handwritten)).isTrue();
  }

  @Test
  void readsHandwrittenPolygonAndOldVarargsHitboxes() {
    String polygon = SOURCE.replace("this.setSize(80);", "this.setSize(80);\n      "
        + "this.setHitbox(new Polygon(new double[] {1, 30, 30}, new double[] {31, 10, 31}));");
    assertThat(SpriteAssets.hitbox(polygon)).containsExactly(1, 31, 30, 10, 30, 31);
    String old = SOURCE.replace("this.setSize(80);",
        "this.setSize(80);\n      this.setHitbox(0, 0, 24, 0, 24, 30);");
    assertThat(SpriteAssets.hitbox(old)).containsExactly(0, 0, 24, 0, 24, 30);
    assertThat(SpriteAssets.literalHitbox("this.setHitbox(new Ellipse(0, 0, 10, 10));")).isNull();
  }

  @Test
  void sheetCostumesAndSheetAnimationsRoundTrip() {
    String sheet = SpriteAssets.addSheetCostumes(SOURCE, "tile", "assets/images/tiles.png", 32, 16);
    // a sheet of 8 columns: row 2 starts at frame 16 (Scratch for Java 7 names the frames)
    String animated = SpriteAssets.addSheetAnimation(sheet, "run", "assets/images/hero.png",
        6, 24, 32, 2, 8);
    String top = SpriteAssets.addSheetAnimation(animated, "idle", "assets/images/hero.png",
        4, 24, 32, 0, 8);
    assertThat(top).contains("this.addCostumes(\"tile\", \"assets/images/tiles.png\", 32, 16);")
        .contains("this.addAnimation(\"run\", \"assets/images/hero.png\", 24, 32, 16, 17, 18, 19, 20, 21);")
        .contains("this.addAnimation(\"idle\", \"assets/images/hero.png\", 24, 32, 0, 1, 2, 3);");
    var entries = SpriteAssets.list(top);
    var tiles = entries.stream().filter(e -> e.kind() == SpriteAssets.Kind.SHEET).findFirst()
        .orElseThrow();
    assertThat(tiles.tileWidth()).isEqualTo(32);
    assertThat(tiles.tileHeight()).isEqualTo(16);
    var run = entries.stream().filter(e -> e.name().equals("run")).findFirst().orElseThrow();
    assertThat(run.isSheetAnimation()).isTrue();
    assertThat(run.frames()).isEqualTo(6);
    assertThat(run.row()).as("the first frame").isEqualTo(16);
    assertThat(run.sheetFrames()).containsExactly(16, 17, 18, 19, 20, 21);
    assertThat(run.columns()).isFalse();
    String removed = SpriteAssets.remove(SpriteAssets.remove(top, tiles), run);
    assertThat(removed).doesNotContain("addCostumes").doesNotContain("\"run\"")
        .contains("\"idle\"");
    assertThatThrownBy(() -> SpriteAssets.addSheetCostumes(top, "tile", "x.png", 8, 8))
        .hasMessageContaining("already exists");
  }

  @Test
  void animationEditorReplacesItsAnimationAndInterval() {
    String first = SpriteAssets.putAnimation(SOURCE, "walk", "assets/images/walk%d.png", 2);
    String second = SpriteAssets.putAnimation(first, "walk", "assets/images/walk%d.png", 4);
    assertThat(second).contains("this.addAnimation(\"walk\", \"assets/images/walk%d.png\", 4);")
        .doesNotContain(", 2);");
    String timed = SpriteRegionWriter.setAnimationInterval(second, 80);
    String retimed = SpriteRegionWriter.setAnimationInterval(timed, 150);
    assertThat(retimed).contains("this.setAnimationInterval(0.15);")
        .doesNotContain("setAnimationInterval(0.08)");
    assertThat(SpriteRegionWriter.animationInterval(retimed)).isEqualTo(150);
    assertThat(SpriteRegionWriter.animationInterval(SOURCE)).isEqualTo(-1);
  }
}
