package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.assets.FrameSequence;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.SpriteAssets;
import org.openpatch.scratch4j.core.region.SpriteRegionWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The animation editor for an {@code AnimatedSprite}: the frames of
 * {@code addAnimation("walk", "assets/images/walk%d.png", n)} as a strip
 * (add, duplicate, delete, reorder, paint), the selected frame with an onion
 * skin of the one before, and a live preview at the animation interval.
 * "Apply" writes the {@code addAnimation} line (with the current frame count)
 * and {@code setAnimationInterval} into the sprite's setup region.
 */
final class AnimationEditorView extends BorderPane {

  private static final double FRAME_VIEW = 320;
  private static final double PREVIEW = 160;

  private final ScratchProject project;
  private final Path spriteFile;
  private final String name;
  private final FrameSequence sequence;
  private final Consumer<Path> onEditFrame;
  private final Consumer<Path> onWritten;

  private final HBox strip = new HBox(6);
  private final Canvas frameView = new Canvas(FRAME_VIEW, FRAME_VIEW);
  private final Canvas preview = new Canvas(PREVIEW, PREVIEW);
  private final Spinner<Integer> interval = new Spinner<>(10, 5000, 120, 10);
  private final ToggleButton onion = new ToggleButton(null, Icons.of("fth-copy"));
  private final ToggleButton playing = new ToggleButton(null, Icons.of("fth-pause"));
  private final Label status = new Label();
  private final Timeline player = new Timeline();
  private final List<Image> images = new ArrayList<>();
  private int selected = 1;
  private int previewFrame;

  AnimationEditorView(ScratchProject project, Path spriteFile, String name, String pattern,
      Consumer<Path> onEditFrame, Consumer<Path> onWritten) {
    this.project = project;
    this.spriteFile = spriteFile;
    this.name = name;
    this.sequence = new FrameSequence(project.root(), pattern);
    this.onEditFrame = onEditFrame;
    this.onWritten = onWritten;
    getStyleClass().add("animation-editor");

    try {
      int existing = SpriteRegionWriter.animationInterval(
          Files.readString(spriteFile, StandardCharsets.UTF_8));
      if (existing > 0) {
        interval.getValueFactory().setValue(existing);
      }
    } catch (IOException | RuntimeException ignored) {
      // the default 120 ms is the library's default too
    }
    interval.setEditable(true);
    interval.setPrefWidth(100);
    interval.valueProperty().addListener((o, a, b) -> restartPlayer());

    onion.setSelected(true);
    onion.getStyleClass().addAll("button-icon", "flat");
    onion.setTooltip(new javafx.scene.control.Tooltip(I18n.t("imageeditor.onion.tooltip")));
    onion.setOnAction(e -> drawFrameView());
    playing.setSelected(true);
    playing.getStyleClass().addAll("button-icon", "flat");
    playing.setOnAction(e -> {
      playing.setGraphic(Icons.of(playing.isSelected() ? "fth-pause" : "fth-play"));
      if (playing.isSelected()) {
        player.play();
      } else {
        player.pause();
      }
    });
    Button apply = new Button(I18n.t("animation.apply",
        spriteFile.getFileName().toString().replaceFirst("\\.java$", "")),
        Icons.of("fth-check"));
    apply.getStyleClass().add("accent");
    apply.setOnAction(e -> apply());
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    Label title = new Label(I18n.t("animation.title", name), Icons.of("fth-film"));
    title.getStyleClass().add("card-title");
    HBox top = new HBox(8, title, spacer, new Label(I18n.t("animation.interval")), interval,
        playing, onion, apply);
    top.setAlignment(Pos.CENTER_LEFT);
    top.getStyleClass().add("tool-bar-row");
    setTop(top);

    VBox previewBox = new VBox(6, new Label(I18n.t("animation.preview")), preview);
    VBox frameBox = new VBox(6, new Label(I18n.t("animation.frame")), frameView);
    HBox center = new HBox(24, frameBox, previewBox);
    center.setPadding(new Insets(16));
    setCenter(center);

    HBox actions = new HBox(4,
        Icons.button("fth-plus", I18n.t("animation.add"), this::addBlank),
        Icons.button("fth-copy", I18n.t("animation.duplicate"), () -> change(() ->
            selected = indexOf(sequence.duplicate(selected)))),
        Icons.button("fth-arrow-left", I18n.t("animation.left"), () -> move(-1)),
        Icons.button("fth-arrow-right", I18n.t("animation.right"), () -> move(1)),
        Icons.button("fth-edit-2", I18n.t("animation.paint"), () -> {
          if (selected <= images.size()) onEditFrame.accept(sequence.frame(selected));
        }),
        Icons.button("fth-trash-2", I18n.t("animation.delete"), () -> change(() -> {
          sequence.delete(selected);
          selected = Math.max(1, selected - 1);
        })));
    actions.setAlignment(Pos.CENTER_LEFT);
    ScrollPane stripScroll = new ScrollPane(strip);
    stripScroll.setFitToHeight(true);
    stripScroll.setPrefHeight(96);
    strip.setPadding(new Insets(4));
    VBox bottom = new VBox(6, actions, stripScroll, status);
    bottom.setPadding(new Insets(8));
    setBottom(bottom);

    player.setCycleCount(Timeline.INDEFINITE);
    // a closed tab stops its preview timeline
    sceneProperty().addListener((o, old, scene) -> {
      if (scene == null) {
        player.stop();
      } else if (playing.isSelected()) {
        player.play();
      }
    });
    reload();
    restartPlayer();
  }

  Path spriteFile() {
    return spriteFile;
  }

  /** Re-reads the frame files (after painting a frame elsewhere). */
  void reload() {
    images.clear();
    for (Path frame : sequence.frames()) {
      images.add(load(frame));
    }
    selected = Math.max(1, Math.min(selected, images.size()));
    rebuildStrip();
    drawFrameView();
    status.setText(I18n.t("animation.status", images.size(), sequence.pattern()));
  }

  void stop() {
    player.stop();
  }

  private static Image load(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return new Image(in);
    } catch (IOException e) {
      return null;
    }
  }

  private int indexOf(Path frame) {
    List<Path> frames = sequence.frames();
    return Math.max(1, frames.indexOf(frame) + 1);
  }

  private interface FileChange {
    void run() throws IOException;
  }

  private void change(FileChange change) {
    if (images.isEmpty()) {
      return;
    }
    try {
      change.run();
    } catch (IOException | RuntimeException e) {
      Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage(), I18n.ok());
      alert.setHeaderText(null);
      Theme.style(alert);
      alert.showAndWait();
    }
    reload();
  }

  void addBlank() {
    Image first = images.isEmpty() ? null : images.get(0);
    try {
      Path added = sequence.addBlank(first == null ? 64 : (int) first.getWidth(),
          first == null ? 64 : (int) first.getHeight());
      selected = indexOf(added);
    } catch (IOException e) {
      status.setText(e.getMessage());
    }
    reload();
  }

  private void move(int direction) {
    int target = selected + direction;
    if (target < 1 || target > images.size()) {
      return;
    }
    change(() -> {
      sequence.swap(selected, target);
      selected = target;
    });
  }

  private void rebuildStrip() {
    strip.getChildren().clear();
    ToggleGroup group = new ToggleGroup();
    for (int i = 0; i < images.size(); i++) {
      int number = i + 1;
      ImageView thumb = new ImageView(images.get(i));
      thumb.setFitWidth(56);
      thumb.setFitHeight(56);
      thumb.setPreserveRatio(true);
      thumb.setSmooth(false);
      ToggleButton tile = new ToggleButton(String.valueOf(number), thumb);
      tile.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
      tile.getStyleClass().add("frame-tile");
      tile.setToggleGroup(group);
      tile.setSelected(number == selected);
      tile.setOnAction(e -> {
        selected = number;
        tile.setSelected(true);
        drawFrameView();
      });
      tile.setOnMouseClicked(e -> {
        if (e.getClickCount() == 2) onEditFrame.accept(sequence.frame(number));
      });
      strip.getChildren().add(tile);
    }
  }

  /** The selected frame, with the previous one faintly behind it (onion skin). */
  private void drawFrameView() {
    GraphicsContext g = frameView.getGraphicsContext2D();
    g.setFill(Color.web("#f3f4f8"));
    g.fillRect(0, 0, FRAME_VIEW, FRAME_VIEW);
    if (images.isEmpty() || images.get(selected - 1) == null) {
      g.setFill(Color.GRAY);
      g.fillText(I18n.t("animation.empty"), 16, FRAME_VIEW / 2);
      return;
    }
    Image current = images.get(selected - 1);
    double scale = Math.min(FRAME_VIEW / current.getWidth(), FRAME_VIEW / current.getHeight());
    g.setImageSmoothing(false);
    if (onion.isSelected() && selected > 1 && images.get(selected - 2) != null) {
      Image previous = images.get(selected - 2);
      g.setGlobalAlpha(0.3);
      g.drawImage(previous, (FRAME_VIEW - previous.getWidth() * scale) / 2,
          (FRAME_VIEW - previous.getHeight() * scale) / 2,
          previous.getWidth() * scale, previous.getHeight() * scale);
      g.setGlobalAlpha(1);
    }
    g.drawImage(current, (FRAME_VIEW - current.getWidth() * scale) / 2,
        (FRAME_VIEW - current.getHeight() * scale) / 2,
        current.getWidth() * scale, current.getHeight() * scale);
  }

  private void restartPlayer() {
    player.stop();
    player.getKeyFrames().setAll(new KeyFrame(Duration.millis(interval.getValue()), e -> {
      if (images.isEmpty()) {
        return;
      }
      previewFrame = (previewFrame + 1) % images.size();
      Image image = images.get(previewFrame);
      GraphicsContext g = preview.getGraphicsContext2D();
      g.clearRect(0, 0, PREVIEW, PREVIEW);
      if (image != null) {
        double scale = Math.min(PREVIEW / image.getWidth(), PREVIEW / image.getHeight());
        g.setImageSmoothing(false);
        g.drawImage(image, (PREVIEW - image.getWidth() * scale) / 2,
            (PREVIEW - image.getHeight() * scale) / 2,
            image.getWidth() * scale, image.getHeight() * scale);
      }
    }));
    if (playing.isSelected()) {
      player.play();
    }
  }

  /** Writes addAnimation(name, pattern, frames) and setAnimationInterval(ms). */
  void apply() {
    if (images.isEmpty()) {
      status.setText(I18n.t("animation.empty"));
      return;
    }
    try {
      String source = Files.readString(spriteFile, StandardCharsets.UTF_8);
      String updated = SpriteAssets.putAnimation(source, name, sequence.pattern(), images.size());
      updated = SpriteRegionWriter.setAnimationInterval(updated, interval.getValue());
      LocalHistory.writeString(project.root(), spriteFile, updated);
      onWritten.accept(spriteFile);
      status.setText(I18n.t("animation.applied", images.size(), spriteFile.getFileName()));
    } catch (IOException | RuntimeException e) {
      status.setText(e.getMessage());
    }
  }
}
