package org.openpatch.scratch4j.core.assets;

/**
 * Turns a built-in picture drawn facing another way so it faces right, the
 * way a Scratch sprite faces at direction 90 - exactly as the library's
 * {@code Image.turnToFaceRight} does when it loads the costume: a picture
 * drawn facing up (0) or down (180) gets a quarter turn, one drawn facing
 * left (-90) is mirrored, not turned upside down.
 */
public final class FacingRight {

  /** ARGB pixels, row by row. */
  public record Pixels(int width, int height, int[] argb) {}

  private FacingRight() {}

  /** {@code pixels} turned to face right; the same pixels when they already do. */
  public static Pixels turn(Pixels pixels, double direction) {
    int facing = facing(direction);
    if (facing == 90) {
      return pixels;
    }
    int w = pixels.width();
    int h = pixels.height();
    boolean quarter = facing != 270;
    int[] out = new int[w * h];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int pixel = pixels.argb()[y * w + x];
        switch (facing) {
          // up: the top edge becomes the right edge
          case 0 -> out[x * h + (h - 1 - y)] = pixel;
          // down: the bottom edge becomes the right edge
          case 180 -> out[(w - 1 - x) * h + y] = pixel;
          // left: mirrored
          default -> out[y * w + (w - 1 - x)] = pixel;
        }
      }
    }
    return new Pixels(quarter ? h : w, quarter ? w : h, out);
  }

  /** The direction as 0, 90, 180 or 270; others are not a way a picture is drawn. */
  static int facing(double direction) {
    double degrees = ((direction % 360) + 360) % 360;
    if (degrees % 90 != 0) {
      throw new IllegalArgumentException(
          "A picture can only be drawn facing up (0), right (90), down (180) or left (-90), not "
              + direction + ".");
    }
    return (int) degrees;
  }
}
