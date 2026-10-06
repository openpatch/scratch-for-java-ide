package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpriteRegionWriterTest {

  private static final String PLAYER = """
      import org.openpatch.scratch.Sprite;

      public class Player extends Sprite {

        public Player() {
          this.addCostume("bunny1_stand");

          // scratch4j:begin setup (managed by the stage designer)
          this.setSize(50);
          this.setRotationStyle(RotationStyle.LEFT_RIGHT);
          // scratch4j:end setup
        }

        public void run() {
        }
      }
      """;

  @Test
  void insertsAndReplacesRotationCenterKeepingForeignLines() {
    String withCenter = SpriteRegionWriter.setRotationCenter(PLAYER, 12, 34);
    assertThat(withCenter).contains("this.setRotationCenter(12, 34);")
        .contains("this.setSize(50);")            // hand-written lines survive
        .contains("this.setRotationStyle(RotationStyle.LEFT_RIGHT);");

    String updated = SpriteRegionWriter.setRotationCenter(withCenter, 56, 78);
    assertThat(updated).contains("this.setRotationCenter(56, 78);")
        .doesNotContain("this.setRotationCenter(12, 34);")
        .containsOnlyOnce("setRotationCenter");

    String removed = SpriteRegionWriter.removeRotationCenter(updated);
    assertThat(removed).doesNotContain("setRotationCenter")
        .contains("this.setSize(50);");
  }

  @Test
  void hitboxNeedsThreePairsAndWritesAllNumbers() {
    assertThatThrownBy(() -> SpriteRegionWriter.setHitbox(PLAYER, 0, 0, 10, 10))
        .isInstanceOf(IllegalArgumentException.class);
    String withHitbox = SpriteRegionWriter.setHitbox(PLAYER, 0, 0, 32.5, 0, 32.5, 48);
    assertThat(withHitbox).contains("this.setHitbox(0, 0, 32.5, 0, 32.5, 48);")
        .contains("this.setSize(50);");
  }

  @Test
  void nineSliceRoundTrips() {
    String withSlice = SpriteRegionWriter.setNineSlice(PLAYER, 4, 6, 8, 6);
    assertThat(withSlice).contains("this.setNineSlice(4, 6, 8, 6);");
    assertThat(SpriteRegionWriter.setNineSlice(withSlice, 1, 2, 3, 4))
        .contains("this.setNineSlice(1, 2, 3, 4);")
        .doesNotContain("setNineSlice(4, 6, 8, 6)");
    assertThatThrownBy(() -> SpriteRegionWriter.setNineSlice(PLAYER, -1, 2, 3, 4))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripIsLosslessWithoutEdits() {
    String once = SpriteRegionWriter.setRotationCenter(PLAYER, 1, 2);
    String twice = SpriteRegionWriter.setRotationCenter(once, 1, 2);
    assertThat(twice).isEqualTo(once);
  }
}
