package org.openpatch.scratch4j.ui;

import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.TextBoundsType;
import javafx.stage.Screen;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.HashMap;
import java.util.Map;

/**
 * Mouse cursors that show the tool, as paint programs do, drawn at the size
 * the system shows cursors so they stay sharp. A tool that works at a tip
 * (pencil, eraser, bucket, eyedropper) is its icon, the hot spot at the tip;
 * a tool that works at a point (shapes, text, select, markers) is a small
 * crosshair at the point with the tool's icon beside it. Both are dark with a
 * white rim, so they show on any picture.
 */
final class ToolCursors {

  /** The corner of the icon where the tool works. */
  enum Tip { BOTTOM_LEFT, BOTTOM_RIGHT }

  static final String PENCIL = "mdi2p-pencil";
  static final String ERASER = "mdi2e-eraser";
  static final String FILL = "mdi2f-format-color-fill";
  static final String PICKER = "mdi2e-eyedropper";

  private static final Color DARK = Color.rgb(25, 25, 30);
  private static final Map<String, Cursor> CACHE = new HashMap<>();

  private ToolCursors() {}

  static Cursor pencil() {
    return of(PENCIL, Tip.BOTTOM_LEFT);
  }

  static Cursor eraser() {
    return of(ERASER, Tip.BOTTOM_LEFT);
  }

  static Cursor fill() {
    return of(FILL, Tip.BOTTOM_RIGHT);
  }

  static Cursor picker() {
    return of(PICKER, Tip.BOTTOM_LEFT);
  }

  /** Turning something: a round arrow, working at its middle. */
  static Cursor rotate() {
    return CACHE.computeIfAbsent("rotate", key -> {
      try {
        int size = size();
        return new ImageCursor(image("mdi2r-rotate-right", size), size / 2.0, size / 2.0);
      } catch (RuntimeException e) {
        return Cursor.CROSSHAIR;
      }
    });
  }


  /** The icon {@code literal} as a cursor working at its {@code tip}; a crosshair if it can't be drawn. */
  static Cursor of(String literal, Tip tip) {
    return CACHE.computeIfAbsent(literal + "@" + tip, key -> {
      try {
        Image image = image(literal, size());
        Point2D hot = hotSpot(image, tip);
        return new ImageCursor(image, hot.getX(), hot.getY());
      } catch (RuntimeException e) {
        return Cursor.CROSSHAIR;
      }
    });
  }

  /** A crosshair working at its centre, with the icon {@code literal} beside it. */
  static Cursor badge(String literal) {
    return CACHE.computeIfAbsent(literal + "@badge", key -> {
      try {
        int size = size();
        return new ImageCursor(badgeImage(literal, size), crossCentre(size), crossCentre(size));
      } catch (RuntimeException e) {
        return Cursor.CROSSHAIR;
      }
    });
  }

  /**
   * The side of a cursor image in pixels: the system's cursor size, so it is
   * not scaled up (and blurred into blocks); on a HiDPI screen at least its
   * scale times the usual 32.
   */
  static int size() {
    Dimension2D best = ImageCursor.getBestSize(32, 32);
    double scale = Screen.getPrimary().getOutputScaleX();
    int side = (int) Math.max(Math.max(best.getWidth(), best.getHeight()), 32 * scale);
    return Math.max(16, Math.min(128, side));
  }

  /** The white rim's width for a cursor {@code size} pixels wide. */
  private static int rim(int size) {
    return Math.max(2, Math.round(size / 16f));
  }

  private static double crossCentre(int size) {
    return Math.round(size * 0.25);
  }

  /** The icon filling most of a {@code size} square, dark with a white rim. */
  static Image image(String literal, int size) {
    int rim = rim(size);
    Image glyph = glyph(literal, size - 2 * rim - Math.round(size / 8f));
    return rimmed(glyph, size, rim);
  }

  /** A crosshair towards the top left, the icon in the lower right. */
  static Image badgeImage(String literal, int size) {
    int rim = rim(size);
    double c = crossCentre(size);
    double arm = size * 0.19;
    Canvas canvas = new Canvas(size, size);
    GraphicsContext g = canvas.getGraphicsContext2D();
    double line = Math.max(1, Math.round(size / 32.0));
    for (int pass = 0; pass < 2; pass++) {
      g.setStroke(pass == 0 ? Color.WHITE : DARK);
      g.setLineWidth(pass == 0 ? line + 2 * rim : line);
      g.strokeLine(c - arm, c + 0.5 * (line % 2), c + arm, c + 0.5 * (line % 2));
      g.strokeLine(c + 0.5 * (line % 2), c - arm, c + 0.5 * (line % 2), c + arm);
    }
    int iconSide = (int) Math.round(size * 0.64);
    Image icon = rimmed(glyph(literal, iconSide - 2 * rim), iconSide, rim);

    g.drawImage(icon, size - iconSide, size - iconSide);
    SnapshotParameters parameters = new SnapshotParameters();
    parameters.setFill(Color.TRANSPARENT);
    return canvas.snapshot(parameters, null);
  }

  /** The bare glyph, dark, cropped to what it draws. */
  private static Image glyph(String literal, int size) {
    FontIcon icon = Icons.of(literal);
    // the glyph's own outline: a text's line box clips some icons and pads others
    icon.setBoundsType(TextBoundsType.VISUAL);
    // Ikonli sets the icon font by CSS, which only runs in a scene; inline, the size
    // and colour win over the theme's icon rules
    icon.setStyle("-fx-icon-size: " + Math.max(8, size) + "px; -fx-icon-color: #19191e;");
    new javafx.scene.Scene(new javafx.scene.Group(icon));
    icon.applyCss();


    SnapshotParameters parameters = new SnapshotParameters();
    parameters.setFill(Color.TRANSPARENT);
    return icon.snapshot(parameters, null);
  }

  /** {@code glyph} centred in a {@code side} square, with a white rim {@code rim} wide. */
  private static Image rimmed(Image glyph, int side, int rim) {
    PixelReader in = glyph.getPixelReader();
    int w = Math.min((int) glyph.getWidth(), side - 2 * rim);
    int h = Math.min((int) glyph.getHeight(), side - 2 * rim);
    int left = (side - w) / 2;
    int top = (side - h) / 2;
    WritableImage square = new WritableImage(side, side);
    PixelWriter out = square.getPixelWriter();
    // the rim: white wherever the glyph is within rim pixels, fading at its edge
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        double alpha = in.getColor(x, y).getOpacity();
        if (alpha < 0.3) continue;
        for (int dy = -rim; dy <= rim; dy++) {
          for (int dx = -rim; dx <= rim; dx++) {
            if (dx * dx + dy * dy <= rim * rim + 1) {
              out.setColor(left + x + dx, top + y + dy, Color.WHITE);
            }
          }
        }
      }
    }
    // the glyph over it, its soft edges blending into the rim
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        double alpha = in.getColor(x, y).getOpacity();
        if (alpha > 0) {
          out.setColor(left + x, top + y, Color.WHITE.interpolate(DARK, alpha));
        }
      }
    }
    return square;
  }

  /** The icon's own (dark) pixel nearest the {@code tip} corner of the image. */
  static Point2D hotSpot(Image image, Tip tip) {
    PixelReader pixels = image.getPixelReader();
    int w = (int) image.getWidth();
    int h = (int) image.getHeight();
    double cornerX = tip == Tip.BOTTOM_LEFT ? 0 : w - 1;
    double cornerY = h - 1;
    Point2D best = new Point2D(w / 2.0, h / 2.0);
    double bestDistance = Double.MAX_VALUE;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        Color c = pixels.getColor(x, y);
        if (c.getOpacity() < 0.5 || c.getBrightness() > 0.5) continue;
        double distance = Math.hypot(x - cornerX, y - cornerY);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = new Point2D(x, y);
        }
      }
    }
    return best;
  }
}
