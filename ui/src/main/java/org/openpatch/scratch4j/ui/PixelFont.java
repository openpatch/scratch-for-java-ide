package org.openpatch.scratch4j.ui;

import javafx.scene.image.PixelWriter;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The image editor's text: a hand-drawn 5x7 pixel font ({@code pixel-font.txt}),
 * so text in pixel art is crisp pixels, not a blurred system font. Each dot
 * becomes a {@code scale} x {@code scale} square.
 */
final class PixelFont {

  /** Rows from the top of a line to the baseline, and the descenders below it. */
  static final int ASCENT = 7;
  static final int HEIGHT = 9;
  /** Dots between two characters. */
  private static final int GAP = 1;

  /** A glyph's dots, row by row from the top. */
  record Glyph(boolean[][] dots) {
    int width() {
      return dots.length == 0 ? 0 : dots[0].length;
    }
  }

  private static Map<Character, Glyph> glyphs;

  private PixelFont() {}

  static synchronized Map<Character, Glyph> glyphs() {
    if (glyphs == null) {
      try (InputStream in = PixelFont.class.getResourceAsStream("pixel-font.txt")) {
        glyphs = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return glyphs;
  }

  static Map<Character, Glyph> parse(String text) {
    Map<Character, Glyph> parsed = new HashMap<>();
    Character current = null;
    List<String> rows = new ArrayList<>();
    for (String line : (text + "\n@").split("\n", -1)) {
      if (line.startsWith("# ") && current == null) continue;
      if (line.startsWith("@")) {
        if (current != null) parsed.put(current, glyph(rows));
        current = line.length() > 1 ? line.charAt(1) : null;
        rows.clear();
      } else if (current != null && !line.isEmpty()) {
        rows.add(line);
      }
    }
    return parsed;
  }

  private static Glyph glyph(List<String> rows) {
    int width = rows.stream().mapToInt(String::length).max().orElse(0);
    boolean[][] dots = new boolean[HEIGHT][width];
    for (int y = 0; y < Math.min(HEIGHT, rows.size()); y++) {
      String row = rows.get(y);
      for (int x = 0; x < row.length(); x++) dots[y][x] = row.charAt(x) == '#';
    }
    return new Glyph(dots);
  }

  /** The glyph for {@code c}; "?" for a character the font lacks. */
  static Glyph glyph(char c) {
    Glyph glyph = glyphs().get(c);
    return glyph != null ? glyph : glyphs().get('?');
  }

  /** The text's width in pixels at {@code scale}. */
  static int width(String text, int scale) {
    int dots = 0;
    for (int i = 0; i < text.length(); i++) {
      dots += glyph(text.charAt(i)).width() + (i > 0 ? GAP : 0);
    }
    return dots * scale;
  }

  /**
   * Writes {@code text} with its top-left corner at ({@code left}, {@code top}),
   * each dot {@code scale} pixels square, clipped to {@code width} x {@code height}.
   */
  static void draw(PixelWriter writer, int left, int top, String text, int scale, Color color,
      int width, int height) {
    int x = left;
    for (int i = 0; i < text.length(); i++) {
      Glyph glyph = glyph(text.charAt(i));
      boolean[][] dots = glyph.dots();
      for (int row = 0; row < dots.length; row++) {
        for (int col = 0; col < dots[row].length; col++) {
          if (!dots[row][col]) continue;
          for (int dy = 0; dy < scale; dy++) {
            for (int dx = 0; dx < scale; dx++) {
              int px = x + col * scale + dx;
              int py = top + row * scale + dy;
              if (px >= 0 && py >= 0 && px < width && py < height) {
                writer.setColor(px, py, color);
              }
            }
          }
        }
      }
      x += (glyph.width() + GAP) * scale;
    }
  }
}
