package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpriteAssetsTest {

  private static final String SOURCE = """
      public class Player extends AnimatedSprite {
        public Player() {
          this.addCostume("original");
          // scratch4j:begin setup
          this.setSize(80);
          // scratch4j:end setup
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
  void sheetCostumesAndSheetAnimationsRoundTrip() {
    String sheet = SpriteAssets.addSheetCostumes(SOURCE, "tile", "assets/images/tiles.png", 32, 16);
    String animated = SpriteAssets.addSheetAnimation(sheet, "run", "assets/images/hero.png",
        6, 24, 32, 2);
    String top = SpriteAssets.addSheetAnimation(animated, "idle", "assets/images/hero.png",
        4, 24, 32, 0);
    assertThat(top).contains("this.addCostumes(\"tile\", \"assets/images/tiles.png\", 32, 16);")
        .contains("this.addAnimation(\"run\", \"assets/images/hero.png\", 6, 24, 32, 2);")
        .contains("this.addAnimation(\"idle\", \"assets/images/hero.png\", 4, 24, 32);");
    var entries = SpriteAssets.list(top);
    var tiles = entries.stream().filter(e -> e.kind() == SpriteAssets.Kind.SHEET).findFirst()
        .orElseThrow();
    assertThat(tiles.tileWidth()).isEqualTo(32);
    assertThat(tiles.tileHeight()).isEqualTo(16);
    var run = entries.stream().filter(e -> e.name().equals("run")).findFirst().orElseThrow();
    assertThat(run.isSheetAnimation()).isTrue();
    assertThat(run.frames()).isEqualTo(6);
    assertThat(run.row()).isEqualTo(2);
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
    assertThat(retimed).contains("this.setAnimationInterval(150);")
        .doesNotContain("setAnimationInterval(80)");
    assertThat(SpriteRegionWriter.animationInterval(retimed)).isEqualTo(150);
    assertThat(SpriteRegionWriter.animationInterval(SOURCE)).isEqualTo(-1);
  }
}
