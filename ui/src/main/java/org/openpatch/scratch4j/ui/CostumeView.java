package org.openpatch.scratch4j.ui;

import javafx.scene.image.Image;
import org.openpatch.scratch4j.core.assets.BuiltinAssetIndex;
import org.openpatch.scratch4j.core.assets.BuiltinImage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders a costume reference (built-in name or project file) as a JavaFX
 * image. Built-in costumes are cropped from the Kenney atlases inside the
 * library jar; entries whose atlas {@code direction} says they were drawn
 * facing another way are pre-rotated to face right, exactly like the
 * library's {@code Image} loader does.
 */
final class CostumeView {

  private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();
  private static final Map<String, Image> SHEETS = new ConcurrentHashMap<>();

  private CostumeView() {}

  /** The costume image, or null when the reference cannot be resolved. */
  static Image costume(String costumeRef) {
    if (costumeRef == null || costumeRef.isBlank()) {
      return null;
    }
    return CACHE.computeIfAbsent(costumeRef, CostumeView::load);
  }

  /**
   * Like {@link #costume(String)}, but file references ("assets/images/a.png")
   * resolve against the project root, as the library does at run time. File
   * images are re-read when they change on disk (the paint editor saved).
   */
  static Image costume(String costumeRef, Path projectRoot) {
    if (costumeRef == null || costumeRef.isBlank()) {
      return null;
    }
    // a part of a sprite sheet: "assets/Skeleton.png#0,0,32,32"
    java.util.regex.Matcher part = java.util.regex.Pattern
        .compile("^(.+)#(\\d+),(\\d+),(\\d+),(\\d+)$").matcher(costumeRef);
    if (part.matches()) {
      Image sheet = costume(part.group(1), projectRoot);
      if (sheet == null) return null;
      int x = Integer.parseInt(part.group(2));
      int y = Integer.parseInt(part.group(3));
      int w = Integer.parseInt(part.group(4));
      int h = Integer.parseInt(part.group(5));
      if (w < 1 || h < 1 || x + w > sheet.getWidth() || y + h > sheet.getHeight()) {
        return sheet;
      }
      return CACHE.computeIfAbsent(costumeRef + "@" + System.identityHashCode(sheet),
          k -> new javafx.scene.image.WritableImage(sheet.getPixelReader(), x, y, w, h));
    }
    if (projectRoot != null && costumeRef.matches("(?i).*\\.(png|jpg|jpeg|gif)$")
        && !Path.of(costumeRef).isAbsolute()) {
      Path file = projectRoot.resolve(costumeRef).normalize();
      try {
        String key = file + "@" + Files.getLastModifiedTime(file).toMillis();
        return CACHE.computeIfAbsent(key, k -> load(file.toString()));
      } catch (java.io.IOException e) {
        return null;
      }
    }
    return costume(costumeRef);
  }

  private static Image load(String ref) {
    try {
      if (ref.matches("(?i).*\\.(png|jpg|jpeg|gif)$")) {
        Path file = Path.of(ref);
        if (!file.isAbsolute()) {
          // resolved against the project root by the caller pre-2: here refs
          // from project files arrive as "assets/x.png" - the designer passes
          // the project root separately
          return null;
        }
        if (!Files.isRegularFile(file)) {
          return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
          return new Image(in);
        }
      }
      BuiltinImage entry = BuiltinAssetIndex.get().image(ref).orElse(null);
      if (entry == null) {
        return null;
      }
      Image sheet = SHEETS.computeIfAbsent(entry.sheetPath(), path -> {
        try (InputStream in = CostumeView.class.getResourceAsStream("/" + path)) {
          return in == null ? null : new Image(in);
        } catch (Exception e) {
          return null;
        }
      });
      return sheet == null ? null : crop(sheet, entry);
    } catch (Exception e) {
      return null;
    }
  }

  /** Cropped, pre-rotated to face right (atlas direction honoured). */
  static Image crop(Image sheet, BuiltinImage entry) {
    if (entry.direction() == 90) {
      javafx.scene.image.WritableImage out =
          new javafx.scene.image.WritableImage(entry.width(), entry.height());
      out.getPixelWriter().setPixels(0, 0, entry.width(), entry.height(),
          sheet.getPixelReader(), entry.x(), entry.y());
      return out;
    }
    // rotate by (90 - drawn direction) clockwise around the crop centre,
    // sampling from the sheet pixel reader
    double rad = Math.toRadians(-(90 - entry.direction()));
    double w = entry.width();
    double h = entry.height();
    int outW = (int) Math.ceil(Math.abs(w * Math.cos(rad)) + Math.abs(h * Math.sin(rad)));
    int outH = (int) Math.ceil(Math.abs(w * Math.sin(rad)) + Math.abs(h * Math.cos(rad)));
    javafx.scene.image.WritableImage out =
        new javafx.scene.image.WritableImage(outW, outH);
    double cx = entry.x() + w / 2.0;
    double cy = entry.y() + h / 2.0;
    javafx.scene.image.PixelReader reader = sheet.getPixelReader();
    for (int y = 0; y < outH; y++) {
      for (int x = 0; x < outW; x++) {
        double dx = x - outW / 2.0;
        double dy = y - outH / 2.0;
        int sx = (int) Math.round(cx + dx * Math.cos(rad) - dy * Math.sin(rad));
        int sy = (int) Math.round(cy + dx * Math.sin(rad) + dy * Math.cos(rad));
        if (sx >= 0 && sy >= 0 && sx < (int) sheet.getWidth()
            && sy < (int) sheet.getHeight()) {
          out.getPixelWriter().setColor(x, y, reader.getColor(sx, sy));
        }
      }
    }
    return out;
  }
}
