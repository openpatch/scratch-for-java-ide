package org.openpatch.scratch4j.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Rotating pixel art by any angle, as Aseprite does. {@link Algorithm#FAST}
 * takes the nearest source pixel for each new one. {@link Algorithm#ROTSPRITE}
 * first enlarges the pixels eight times with Scale2x (which rounds off
 * staircase edges instead of blurring them), rotates that, and samples it back
 * down: lines and outlines stay clean, without new colours.
 *
 * <p>Pictures are ARGB ints, row by row; the result is big enough for the
 * rotated picture, its centre where the source's centre was.
 */
final class SpriteRotation {

  enum Algorithm { FAST, ROTSPRITE }

  /** The rotated pixels and their size. */
  record Pixels(int[] argb, int width, int height) {}

  private SpriteRotation() {}

  /** {@code source} turned clockwise by {@code degrees}. */
  static WritableImage rotate(Image source, double degrees, Algorithm algorithm) {
    int w = (int) source.getWidth();
    int h = (int) source.getHeight();
    int[] argb = new int[w * h];
    source.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
    Pixels rotated = rotate(new Pixels(argb, w, h), degrees, algorithm);
    WritableImage result = new WritableImage(rotated.width(), rotated.height());
    result.getPixelWriter().setPixels(0, 0, rotated.width(), rotated.height(),
        PixelFormat.getIntArgbInstance(), rotated.argb(), 0, rotated.width());
    return result;
  }

  static Pixels rotate(Pixels source, double degrees, Algorithm algorithm) {
    double radians = Math.toRadians(normalized(degrees));
    int[] size = rotatedSize(source.width(), source.height(), radians);
    // a quarter turn moves whole pixels: exact, nothing to smooth
    boolean quarter = Math.abs(normalized(degrees) % 90) < 1e-9;
    if (algorithm == Algorithm.FAST || quarter) {
      return nearest(source, radians, size[0], size[1]);
    }

    Pixels big = scale2x(scale2x(scale2x(source)));
    Pixels turned = nearest(big, radians, size[0] * 8, size[1] * 8);
    // back down: the middle of each 8x8 block
    int[] out = new int[size[0] * size[1]];
    for (int y = 0; y < size[1]; y++) {
      for (int x = 0; x < size[0]; x++) {
        out[y * size[0] + x] = turned.argb()[(y * 8 + 4) * turned.width() + x * 8 + 4];
      }
    }
    return new Pixels(out, size[0], size[1]);
  }

  /** The angle in [0, 360). */
  private static double normalized(double degrees) {
    double d = degrees % 360;
    return d < 0 ? d + 360 : d;
  }

  /** The width and height that hold a w x h picture turned by {@code radians}. */
  static int[] rotatedSize(int w, int h, double radians) {
    double cos = Math.abs(Math.cos(radians));
    double sin = Math.abs(Math.sin(radians));
    // a hair of slack, so 90 degrees gives exactly h x w
    int rw = (int) Math.ceil(w * cos + h * sin - 1e-6);
    int rh = (int) Math.ceil(w * sin + h * cos - 1e-6);
    return new int[] {Math.max(1, rw), Math.max(1, rh)};
  }

  /**
   * Each pixel of a {@code width} x {@code height} result takes the source pixel its
   * centre falls on when turned back; the two centres coincide.
   */
  private static Pixels nearest(Pixels source, double radians, int width, int height) {

    int[] out = new int[width * height];
    double cos = Math.cos(radians);
    double sin = Math.sin(radians);
    double scx = source.width() / 2.0;
    double scy = source.height() / 2.0;
    double dcx = width / 2.0;
    double dcy = height / 2.0;
    for (int y = 0; y < height; y++) {
      double dy = y + 0.5 - dcy;
      for (int x = 0; x < width; x++) {
        double dx = x + 0.5 - dcx;
        // clockwise on screen (y down): turn the result's point back
        double sx = dx * cos + dy * sin + scx;
        double sy = -dx * sin + dy * cos + scy;
        int ix = (int) Math.floor(sx + 1e-9);
        int iy = (int) Math.floor(sy + 1e-9);
        if (ix >= 0 && iy >= 0 && ix < source.width() && iy < source.height()) {
          out[y * width + x] = source.argb()[iy * source.width() + ix];
        }
      }
    }
    return new Pixels(out, width, height);
  }

  /**
   * Scale2x (EPX): every pixel becomes four, each corner taking a neighbour's
   * colour where two neighbours agree, so diagonal steps become smooth lines.
   */
  static Pixels scale2x(Pixels source) {
    int w = source.width();
    int h = source.height();
    int[] in = source.argb();
    int[] out = new int[w * 2 * h * 2];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int e = colour(in[y * w + x]);
        int b = y > 0 ? colour(in[(y - 1) * w + x]) : e;
        int d = x > 0 ? colour(in[y * w + x - 1]) : e;
        int f = x < w - 1 ? colour(in[y * w + x + 1]) : e;
        int hh = y < h - 1 ? colour(in[(y + 1) * w + x]) : e;
        int e0 = e;
        int e1 = e;
        int e2 = e;
        int e3 = e;
        if (b != hh && d != f) {
          e0 = d == b ? d : e;
          e1 = b == f ? f : e;
          e2 = d == hh ? d : e;
          e3 = hh == f ? f : e;
        }
        int row = y * 2 * w * 2;
        out[row + x * 2] = e0;
        out[row + x * 2 + 1] = e1;
        out[row + w * 2 + x * 2] = e2;
        out[row + w * 2 + x * 2 + 1] = e3;
      }
    }
    return new Pixels(out, w * 2, h * 2);
  }

  /** Every fully transparent pixel is the same colour, whatever its RGB. */
  private static int colour(int argb) {
    return (argb >>> 24) == 0 ? 0 : argb;
  }
}
