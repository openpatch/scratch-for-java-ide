package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.region.SpriteAssets;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundIO;
import org.openpatch.scratch4j.sound.SoundPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The sprite editor's preview of the selected asset: a costume large and
 * pixel-sharp, an animation playing, a sound as a waveform with Play/Stop.
 * Also the small thumbnails of the asset lists.
 */
final class AssetPreview extends VBox {

  private static final double SIZE = 220;
  private static final SoundPlayer PLAYER = new SoundPlayer();

  private final Path root;
  private final Canvas canvas = new Canvas(SIZE, SIZE);
  private final Label caption = new Label();
  private final HBox controls = new HBox(6);
  private final Timeline animation = new Timeline();
  private List<Image> frames = List.of();
  private int frame;
  private SoundClip clip;

  AssetPreview(Path root) {
    super(8);
    this.root = root;
    getStyleClass().add("asset-preview");
    setPadding(new Insets(8));
    setAlignment(Pos.TOP_CENTER);
    setPrefWidth(SIZE + 24);
    setMinWidth(SIZE + 24);
    caption.setWrapText(true);
    caption.getStyleClass().add("text-muted");
    controls.setAlignment(Pos.CENTER);
    getChildren().addAll(canvas, caption, controls);
    animation.setCycleCount(Timeline.INDEFINITE);
    sceneProperty().addListener((o, old, scene) -> {
      if (scene == null) stop();
    });
    show(null);
  }

  /** Shows the entry (null: an empty preview). */
  void show(SpriteAssets.Entry entry) {
    stop();
    controls.getChildren().clear();
    frames = List.of();
    clip = null;
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, SIZE, SIZE);
    checkerboard(g);
    if (entry == null) {
      caption.setText(I18n.t("spriteassets.preview.none"));
      return;
    }
    switch (entry.kind()) {
      case COSTUME, SHEET -> {
        Image image = CostumeView.costume(costumeRef(entry), root);
        draw(image);
        caption.setText(image == null ? I18n.t("spriteassets.preview.missing")
            : (int) image.getWidth() + " × " + (int) image.getHeight() + " px");
      }
      case ANIMATION -> {
        List<Image> loaded = new ArrayList<>();
        for (String ref : frameRefs(entry)) {
          Image image = CostumeView.costume(ref, root);
          if (image != null) loaded.add(image);
        }
        frames = loaded;
        caption.setText(loaded.isEmpty() ? I18n.t("spriteassets.preview.missing")
            : I18n.t("spriteassets.frames", loaded.size()));
        if (!loaded.isEmpty()) {
          draw(loaded.get(0));
          animation.getKeyFrames().setAll(new KeyFrame(Duration.millis(140), e -> {
            frame = (frame + 1) % frames.size();
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.clearRect(0, 0, SIZE, SIZE);
            checkerboard(gc);
            draw(frames.get(frame));
          }));
          animation.play();
        }
      }
      case SOUND -> {
        clip = loadSound(entry);
        if (clip == null) {
          caption.setText(I18n.t("spriteassets.preview.missing"));
          return;
        }
        waveform(g, clip);
        caption.setText(String.format("%.1f s", clip.duration()));
        Button play = Icons.labeled("fth-play", I18n.t("spriteassets.play"), () -> {
          if (!PLAYER.play(clip)) caption.setText(I18n.t("library.noaudio"));
        });
        Button stopButton = Icons.labeled("fth-square", I18n.t("spriteassets.stop"),
            PLAYER::stop);
        controls.getChildren().addAll(play, stopButton);
      }
      default -> caption.setText("");
    }
  }

  void stop() {
    animation.stop();
    frame = 0;
    if (clip != null) PLAYER.stop();
  }

  /** Frames being played (tests). */
  int frameCount() {
    return frames.size();
  }

  SoundClip clip() {
    return clip;
  }

  // --- what an entry shows --------------------------------------------------------------

  static String costumeRef(SpriteAssets.Entry entry) {
    if (entry.kind() == SpriteAssets.Kind.SHEET) {
      return entry.reference();
    }
    if (entry.isSheetCostume()) {
      return entry.reference() + "#" + entry.cropX() + "," + entry.cropY() + ","
          + entry.tileWidth() + "," + entry.tileHeight();
    }
    return entry.reference() != null ? entry.reference() : entry.name();
  }

  /** The frames of an animation as costume references (sheet parts as path#x,y,w,h). */
  static List<String> frameRefs(SpriteAssets.Entry entry) {
    List<String> out = new ArrayList<>();
    String ref = entry.reference();
    if (ref == null || entry.frames() < 1) return out;
    for (int i = 0; i < entry.frames(); i++) {
      if (entry.isSheetAnimation()) {
        int w = entry.tileWidth();
        int h = entry.tileHeight();
        // (…, column, true) runs down a column, (…, row) along a row
        out.add(entry.columns()
            ? ref + "#" + entry.row() * w + "," + i * h + "," + w + "," + h
            : ref + "#" + i * w + "," + entry.row() * h + "," + w + "," + h);
      } else {
        try {
          out.add(String.format(ref, i + 1));
        } catch (RuntimeException e) {
          return out;
        }
      }
    }
    return out;
  }

  /** A small picture for the asset lists. */
  static Node thumbnail(SpriteAssets.Entry entry, Path root) {
    if (entry.kind() == SpriteAssets.Kind.SOUND) {
      return Icons.of("fth-music", 18);
    }
    String ref = entry.kind() == SpriteAssets.Kind.ANIMATION
        ? frameRefs(entry).stream().findFirst().orElse(null) : costumeRef(entry);
    Image image = CostumeView.costume(ref, root);
    Canvas small = new Canvas(32, 32);
    if (image != null) {
      double s = Math.min(32 / image.getWidth(), 32 / image.getHeight());
      GraphicsContext g = small.getGraphicsContext2D();
      g.setImageSmoothing(s < 1);
      g.drawImage(image, (32 - image.getWidth() * s) / 2, (32 - image.getHeight() * s) / 2,
          image.getWidth() * s, image.getHeight() * s);
    }
    return small;
  }

  private SoundClip loadSound(SpriteAssets.Entry entry) {
    try {
      if (entry.reference() != null) {
        Path file = root.resolve(entry.reference()).normalize();
        return Files.isRegularFile(file) ? SoundIO.decode(file) : null;
      }
      String resource = org.openpatch.scratch.internal.BuiltinSounds.get(entry.name());
      if (resource == null) return null;
      try (InputStream in = org.openpatch.scratch.internal.BuiltinSounds.class
          .getResourceAsStream("/" + resource)) {
        return in == null ? null : SoundIO.decode(in);
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  // --- drawing --------------------------------------------------------------------------

  private void draw(Image image) {
    if (image == null) return;
    GraphicsContext g = canvas.getGraphicsContext2D();
    double s = Math.min((SIZE - 16) / image.getWidth(), (SIZE - 16) / image.getHeight());
    // small pixel art is scaled up crisp, large pictures smoothly down
    g.setImageSmoothing(s < 1);
    double w = image.getWidth() * s;
    double h = image.getHeight() * s;
    g.drawImage(image, (SIZE - w) / 2, (SIZE - h) / 2, w, h);
  }

  private static void checkerboard(GraphicsContext g) {
    for (int y = 0; y < SIZE; y += 12) {
      for (int x = 0; x < SIZE; x += 12) {
        g.setFill((x / 12 + y / 12) % 2 == 0 ? Color.web("#f4f4f7") : Color.web("#e8e8ee"));
        g.fillRect(x, y, 12, 12);
      }
    }
  }

  private static void waveform(GraphicsContext g, SoundClip clip) {
    g.setFill(Color.web("#f7f5ff"));
    g.fillRect(0, 0, SIZE, SIZE);
    g.setStroke(Color.web("#855cd6"));
    g.setLineWidth(1);
    int n = clip.sampleCount();
    if (n == 0) return;
    float peak = Math.max(0.0001f, clip.peak());
    for (int x = 0; x < SIZE; x++) {
      int from = (int) ((long) n * x / (int) SIZE);
      int to = Math.max(from + 1, (int) ((long) n * (x + 1) / (int) SIZE));
      float max = 0;
      for (int i = from; i < to && i < n; i++) {
        max = Math.max(max, Math.abs(clip.sample(0, i)));
      }
      double h = max / peak * (SIZE / 2 - 10);
      g.strokeLine(x + 0.5, SIZE / 2 - h, x + 0.5, SIZE / 2 + h);
    }
  }
}
