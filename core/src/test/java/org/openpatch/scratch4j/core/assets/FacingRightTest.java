package org.openpatch.scratch4j.core.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;
import processing.core.PConstants;
import processing.core.PImage;

/** The IDE turns pictures exactly as the library does when it loads them. */
class FacingRightTest {

  @Test
  void matchesTheLibraryForEveryWayAPictureIsDrawn() {
    Random random = new Random(7);
    int w = 5;
    int h = 3;
    int[] argb = new int[w * h];
    for (int i = 0; i < argb.length; i++) argb[i] = random.nextInt();
    for (double direction : new double[] {0, 90, 180, -90, 270}) {
      PImage picture = new PImage(w, h, PConstants.ARGB);
      picture.loadPixels();
      System.arraycopy(argb, 0, picture.pixels, 0, argb.length);
      picture.updatePixels();
      PImage library = org.openpatch.scratch.internal.Image.turnToFaceRight(picture, direction);
      library.loadPixels();

      FacingRight.Pixels ours = FacingRight.turn(new FacingRight.Pixels(w, h, argb), direction);
      assertThat(ours.width()).as("width, facing %s", direction).isEqualTo(library.width);
      assertThat(ours.height()).as("height, facing %s", direction).isEqualTo(library.height);
      assertThat(ours.argb()).as("pixels, facing %s", direction).containsExactly(library.pixels);
    }
  }

  @Test
  void facingLeftIsAMirror() {
    var turned = FacingRight.turn(new FacingRight.Pixels(3, 1, new int[] {1, 2, 3}), -90);
    assertThat(turned.argb()).containsExactly(3, 2, 1);
  }

  @Test
  void onlyQuarterTurnsAreAWayAPictureIsDrawn() {
    assertThatThrownBy(() -> FacingRight.turn(new FacingRight.Pixels(1, 1, new int[1]), 45))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
