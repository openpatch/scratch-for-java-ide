package org.openpatch.scratch4j.ui;

import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.openpatch.scratch4j.sound.SoundIO;
import org.openpatch.scratch4j.sound.SoundPlayer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small previews in the code's gutter: the image a line adds (costume,
 * backdrop, the first frame of an animation; large on hover), a play button
 * for a sound, a map icon for {@code new TiledMap(...)}. A click opens the
 * project file in its editor (or plays the sound).
 */
final class CodePreviews {

  private static final SoundPlayer PLAYER = new SoundPlayer();
  private static final String S = "\"([^\"]*)\"";
  private static final String N = "\\s*(-?\\d+)\\s*";
  private static final Pattern CROP = Pattern.compile(
      "\\badd(?:Costume)\\(\\s*" + S + "\\s*,\\s*" + S + "\\s*," + N + "," + N + "," + N + ","
          + N + "\\)");
  private static final Pattern SHEET_ANIMATION = Pattern.compile(
      "\\baddAnimation\\(\\s*" + S + "\\s*,\\s*" + S + "\\s*," + N + "," + N + "," + N
          + "(?:," + N + "(?:,\\s*(true|false)\\s*)?)?\\)");
  private static final Pattern PATTERN_ANIMATION = Pattern.compile(
      "\\baddAnimation\\(\\s*" + S + "\\s*,\\s*" + S + "\\s*," + N + "\\)");
  private static final Pattern IMAGE = Pattern.compile(
      "\\badd(?:Costume|Backdrop)\\(\\s*" + S + "(?:\\s*,\\s*" + S + ")?");
  private static final Pattern SOUND = Pattern.compile(
      "\\baddSound\\(\\s*" + S + "(?:\\s*,\\s*" + S + ")?");
  private static final Pattern MAP = Pattern.compile("\\bnew\\s+TiledMap\\(\\s*" + S);
  /**
   * A colour as the library takes it: setTint/setColor/setTextColor/... or
   * new Color with a hue (0..255), red/green/blue (0..255) or "#rrggbb".
   */
  static final Pattern COLOR = Pattern.compile(
      "\\b(setTint|set\\w*Color|new\\s+Color)\\(\\s*(?:\"(#?[0-9a-fA-F]{6})\"|"
          + "(-?\\d+(?:\\.\\d+)?)\\s*(?:,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?))?)"
          + "\\s*\\)");

  /** The colour a COLOR match means (as the game draws it). */
  static Color colorOf(Matcher m) {
    if (m.group(2) != null) {
      return Color.web(m.group(2).startsWith("#") ? m.group(2) : "#" + m.group(2));
    }
    double first = Double.parseDouble(m.group(3));
    if (m.group(4) == null) {
      // a hue: the colour wheel as 0..255, full saturation and brightness
      double hue = ((first % 255) + 255) % 255;
      return Color.hsb(hue / 255 * 360, 1, 1);
    }
    return Color.rgb(clamp(first), clamp(Double.parseDouble(m.group(4))),
        clamp(Double.parseDouble(m.group(5))));
  }

  /** The same call with {@code color} written the way it was written (hue, rgb or hex). */
  static String withColor(String line, Matcher m, Color color) {
    String args;
    if (m.group(2) != null) {
      args = "\"#" + String.format("%02x%02x%02x", Math.round(color.getRed() * 255),
          Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255)) + "\"";
    } else if (m.group(4) == null) {
      args = String.valueOf(Math.round(color.getHue() / 360 * 255) % 255);
    } else {
      args = Math.round(color.getRed() * 255) + ", " + Math.round(color.getGreen() * 255) + ", "
          + Math.round(color.getBlue() * 255);
    }
    return line.substring(0, m.start()) + m.group(1) + "(" + args + ")"
        + line.substring(m.end());
  }

  private static int clamp(double v) {
    return (int) Math.max(0, Math.min(255, Math.round(v)));
  }

  private CodePreviews() {}

  /** The preview for one line of code, or null. {@code open} gets a project file. */
  static Node forLine(String line, Path root, Consumer<Path> open) {
    return forLine(line, root, open, null);
  }

  /** {@code replaceLine} (may be null) takes the line rewritten with a newly picked colour. */
  static Node forLine(String line, Path root, Consumer<Path> open, Consumer<String> replaceLine) {
    if (root == null || line.strip().startsWith("//")) return null;
    Matcher m;
    if ((m = COLOR.matcher(line)).find()) {
      Matcher match = m;
      Color color = colorOf(m);
      javafx.scene.shape.Rectangle chip = new javafx.scene.shape.Rectangle(12, 12, color);
      chip.setArcWidth(4);
      chip.setArcHeight(4);
      chip.setStroke(Color.gray(0.45));
      Label holder = new Label(null, chip);
      String values = m.group(2) != null ? m.group(2) : m.group(4) == null
          ? I18n.t("codepreview.hue", m.group(3)) : m.group(3) + ", " + m.group(4) + ", "
              + m.group(5);
      holder.setTooltip(tooltip(values + (replaceLine == null ? ""
          : "  \u00b7  " + I18n.t("codepreview.color.pick")), null));
      if (replaceLine != null) {
        holder.setOnMouseClicked(e -> {
          ScratchColorPicker picker = new ScratchColorPicker(color);
          picker.openAt(holder, picked -> replaceLine.accept(withColor(line, match, picked)));
          e.consume();
        });
      }
      return decorate(holder);
    }
    if ((m = MAP.matcher(line)).find()) {
      Path map = root.resolve(m.group(1)).normalize();
      if (!Files.isRegularFile(map)) return null;
      Label icon = new Label(null, Icons.of("fth-map", 13));
      icon.setTooltip(tooltip(I18n.t("codepreview.map", m.group(1)), null));
      icon.setOnMouseClicked(e -> {
        open.accept(map);
        e.consume();
      });
      return decorate(icon);
    }
    if ((m = SOUND.matcher(line)).find()) {
      String name = m.group(1);
      String path = m.group(2);
      Label play = new Label(null, Icons.of("fth-play-circle", 13));
      play.setTooltip(tooltip(I18n.t("codepreview.sound", path != null ? path : name), null));
      play.setOnMouseClicked(e -> {
        playSound(root, name, path);
        e.consume();
      });
      return decorate(play);
    }
    String ref = null;
    if ((m = CROP.matcher(line)).find()) {
      ref = m.group(2) + "#" + m.group(3).trim() + "," + m.group(4).trim() + ","
          + m.group(5).trim() + "," + m.group(6).trim();
    } else if ((m = SHEET_ANIMATION.matcher(line)).find()) {
      int w = Integer.parseInt(m.group(4).trim());
      int h = Integer.parseInt(m.group(5).trim());
      int index = m.group(6) == null ? 0 : Integer.parseInt(m.group(6).trim());
      boolean columns = "true".equals(m.group(7));
      ref = m.group(2) + "#" + (columns ? index * w : 0) + "," + (columns ? 0 : index * h)
          + "," + w + "," + h;
    } else if ((m = PATTERN_ANIMATION.matcher(line)).find()) {
      try {
        ref = String.format(m.group(2), 1);
      } catch (RuntimeException e) {
        return null;
      }
    } else if ((m = IMAGE.matcher(line)).find()) {
      ref = m.group(2) != null ? m.group(2) : m.group(1);
    }
    if (ref == null) return null;
    Image image = CostumeView.costume(ref, root);
    if (image == null) return null;
    Canvas thumb = new Canvas(16, 16);
    GraphicsContext g = thumb.getGraphicsContext2D();
    double s = Math.min(16 / image.getWidth(), 16 / image.getHeight());
    g.setImageSmoothing(s < 1);
    g.drawImage(image, (16 - image.getWidth() * s) / 2, (16 - image.getHeight() * s) / 2,
        image.getWidth() * s, image.getHeight() * s);
    Label holder = new Label(null, thumb);
    ImageView large = new ImageView(image);
    large.setPreserveRatio(true);
    large.setSmooth(image.getWidth() > 128);
    double big = Math.min(160, Math.max(64, Math.max(image.getWidth(), image.getHeight()) * 4));
    large.setFitWidth(Math.min(big, image.getWidth() * 8));
    holder.setTooltip(tooltip((int) image.getWidth() + " × " + (int) image.getHeight()
        + " px", large));
    String file = ref.replaceFirst("#.*$", "");
    Path inProject = root.resolve(file).normalize();
    if (Files.isRegularFile(inProject)) {
      holder.setOnMouseClicked(e -> {
        open.accept(inProject);
        e.consume();
      });
    }
    return decorate(holder);
  }

  private static Node decorate(Label node) {
    node.getStyleClass().add("code-preview");
    node.setMinWidth(18);
    node.setCursor(javafx.scene.Cursor.HAND);
    return node;
  }

  private static Tooltip tooltip(String text, Node graphic) {
    Tooltip t = new Tooltip(text);
    t.setGraphic(graphic);
    t.setShowDelay(Duration.millis(200));
    return t;
  }

  private static void playSound(Path root, String name, String path) {
    try {
      if (path != null) {
        Path file = root.resolve(path).normalize();
        if (Files.isRegularFile(file)) PLAYER.play(SoundIO.decode(file));
        return;
      }
      String resource = org.openpatch.scratch.internal.BuiltinSounds.get(name);
      if (resource == null) return;
      try (InputStream in = org.openpatch.scratch.internal.BuiltinSounds.class
          .getResourceAsStream("/" + resource)) {
        if (in != null) PLAYER.play(SoundIO.decode(in));
      }
    } catch (java.io.IOException | RuntimeException ignored) {
      // no sound: nothing to play
    }
  }
}
