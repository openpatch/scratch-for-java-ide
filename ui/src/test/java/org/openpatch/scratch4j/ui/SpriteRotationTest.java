package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.ui.SpriteRotation.Algorithm;
import org.openpatch.scratch4j.ui.SpriteRotation.Pixels;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpriteRotationTest {

  private static final int R = 0xffff0000;
  private static final int G = 0xff00ff00;
  private static final int B = 0xff0000ff;

  /** 3 x 2: R G B / B . R */
  private static Pixels sample() {
    return new Pixels(new int[] {R, G, B, B, 0, R}, 3, 2);
  }

  @Test
  void aQuarterTurnMovesWholePixelsWithEitherAlgorithm() {
    for (Algorithm algorithm : Algorithm.values()) {
      Pixels turned = SpriteRotation.rotate(sample(), 90, algorithm);
      assertThat(turned.width()).isEqualTo(2);
      assertThat(turned.height()).isEqualTo(3);
      // clockwise: the left column, bottom up, becomes the top row
      assertThat(turned.argb()).containsExactly(B, R, 0, G, R, B);
      assertThat(SpriteRotation.rotate(sample(), 0, algorithm).argb())
          .containsExactly(sample().argb());
      assertThat(SpriteRotation.rotate(sample(), 360, algorithm).argb())
          .containsExactly(sample().argb());
      assertThat(SpriteRotation.rotate(sample(), -90, algorithm).argb())
          .containsExactly(SpriteRotation.rotate(sample(), 270, algorithm).argb());
    }
  }

  @Test
  void anyAngleGrowsTheBoxAndAddsNoColours() {
    int[] square = new int[16 * 16];
    for (int y = 3; y < 13; y++) {
      for (int x = 3; x < 13; x++) square[y * 16 + x] = (x + y) % 2 == 0 ? R : B;
    }
    for (Algorithm algorithm : Algorithm.values()) {
      Pixels turned = SpriteRotation.rotate(new Pixels(square, 16, 16), 45, algorithm);
      // 16 * sqrt(2) = 22.6
      assertThat(turned.width()).isEqualTo(23);
      assertThat(turned.height()).isEqualTo(23);
      Set<Integer> colours = new HashSet<>();
      for (int argb : turned.argb()) colours.add(argb);
      assertThat(colours).isSubsetOf(0, R, B);
      assertThat(colours).contains(R, B);
    }
  }

  @Test
  void scale2xRoundsAStaircaseAndKeepsFlatAreas() {
    // a diagonal step: X . / . X  (X red): its inner corners fill in
    Pixels step = new Pixels(new int[] {R, 0, 0, R}, 2, 2);
    Pixels big = SpriteRotation.scale2x(step);
    assertThat(big.width()).isEqualTo(4);
    // the empty top-right pixel takes red in its quarter between the two reds
    // (lower left), and stays empty in the far corner (upper right)
    assertThat(big.argb()[1 * 4 + 2]).isEqualTo(R);
    assertThat(big.argb()[0 * 4 + 3]).isZero();

    Pixels flat = SpriteRotation.scale2x(new Pixels(new int[] {G, G, G, G}, 2, 2));
    for (int argb : flat.argb()) assertThat(argb).isEqualTo(G);
  }
}
