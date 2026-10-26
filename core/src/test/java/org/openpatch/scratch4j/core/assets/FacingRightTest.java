package org.openpatch.scratch4j.core.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.openpatch.scratch.internal.image.Image;

/** The IDE turns pictures exactly as the library does when it loads them. */
class FacingRightTest {

  @Test
  void matchesTheLibraryForEveryWayAPictureIsDrawn() throws Exception {
    // Scratch for Java 7: Images.turnToFaceRight(Image, double), package-private in the library
    Method turn = Class.forName("org.openpatch.scratch.internal.image.Images")
        .getDeclaredMethod("turnToFaceRight", Image.class, double.class);
    turn.setAccessible(true);
    Random random = new Random(7);
    int w = 5;
    int h = 3;
    int[] argb = new int[w * h];
    for (int i = 0; i < argb.length; i++) argb[i] = random.nextInt();
    for (double direction : new double[] {0, 90, 180, -90, 270}) {
      Image library = (Image) turn.invoke(null, new Image(w, h, argb.clone()), direction);

      FacingRight.Pixels ours = FacingRight.turn(new FacingRight.Pixels(w, h, argb), direction);
      assertThat(ours.width()).as("width, facing %s", direction).isEqualTo(library.getWidth());
      assertThat(ours.height()).as("height, facing %s", direction).isEqualTo(library.getHeight());
      assertThat(ours.argb()).as("pixels, facing %s", direction).containsExactly(library.getArgb());
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
