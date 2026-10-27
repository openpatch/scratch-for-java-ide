package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.assets.FrameSequence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The frames of a numbered sequence ({@code walk1.png}, {@code walk2.png},
 * ...) under the image editor's canvas: a thumbnail per frame to paint it
 * (the frame shown is saved first), buttons to add, duplicate, delete and
 * move frames, a looping preview, and how many frames before and after the
 * onion skin shows.
 */
final class FrameStrip extends HBox {

  /** What the strip needs from the image editor. */
  interface Host {
    /** The frame the editor shows. */
    Path file();

    /** Saves the frame shown if it has edits; false when that failed. */
    boolean saveFrame();

    /** Shows {@code frame}, read from disk, without saving the frame shown. */
    boolean showFrame(Path frame);

    /** The frame shown as painted so far (unsaved strokes included). */
    WritableImage picture();

    /** The onion skin shows {@code before} frames before and {@code after} after. */
    void onionChanged(int before, int after);

    /** Frame files were added, removed or renamed. */
    void framesChanged();
  }

  private static final double THUMB = 48;

  private final FrameSequence sequence;
  private final Host host;
  private final HBox thumbs = new HBox(6);
  private final ImageView preview = new ImageView();
  private final ToggleButton play = new ToggleButton(null, Icons.of("fth-play"));
  private final Spinner<Integer> fps = new Spinner<>(1, 30, 8);
  private final Spinner<Integer> before = new Spinner<>(0, 3, 1);
  private final Spinner<Integer> after = new Spinner<>(0, 3, 1);
  private final Label message = new Label();
  private final Timeline player = new Timeline();
  private List<Path> frames = List.of();
  private final List<Image> images = new ArrayList<>();
  private int previewFrame;

  FrameStrip(FrameSequence sequence, Host host) {
    super(10);
    this.sequence = sequence;
    this.host = host;
    getStyleClass().add("frame-strip");
    setPadding(new Insets(6, 10, 6, 10));
    setAlignment(Pos.CENTER_LEFT);

    ScrollPane scroll = new ScrollPane(thumbs);
    scroll.setFitToHeight(true);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.getStyleClass().add("edge-to-edge");
    HBox.setHgrow(scroll, Priority.ALWAYS);
    thumbs.setAlignment(Pos.CENTER_LEFT);
    thumbs.setPadding(new Insets(2));

    HBox actions = new HBox(2,
        Icons.button("fth-plus", I18n.t("frames.add"), this::addBlank),
        Icons.button("fth-copy", I18n.t("frames.duplicate"), this::duplicate),
        Icons.button("fth-trash-2", I18n.t("frames.delete"), this::delete),
        Icons.button("fth-arrow-left", I18n.t("frames.left"), () -> move(-1)),
        Icons.button("fth-arrow-right", I18n.t("frames.right"), () -> move(1)));
    actions.setAlignment(Pos.CENTER_LEFT);

    play.getStyleClass().addAll("button-icon", "flat");
    play.setTooltip(new Tooltip(I18n.t("frames.play")));
    play.setOnAction(e -> restartPlayer());
    fps.setPrefWidth(70);
    fps.setTooltip(new Tooltip(I18n.t("frames.fps")));
    fps.valueProperty().addListener((o, a, b) -> restartPlayer());
    preview.setFitWidth(THUMB + 16);
    preview.setFitHeight(THUMB + 16);
    preview.setPreserveRatio(true);
    preview.setSmooth(false);
    StackPane previewBox = new StackPane(preview);
    previewBox.setPrefSize(THUMB + 20, THUMB + 20);
    previewBox.getStyleClass().add("frame-preview");
    Label fpsLabel = new Label(I18n.t("frames.fps.short"));
    fpsLabel.getStyleClass().add("text-muted");
    HBox playback = new HBox(4, play, fps, fpsLabel);
    playback.setAlignment(Pos.CENTER_LEFT);

    for (Spinner<Integer> spinner : List.of(before, after)) {
      spinner.setPrefWidth(64);
      spinner.valueProperty().addListener((o, a, b) ->
          host.onionChanged(before.getValue(), after.getValue()));
    }
    before.setTooltip(new Tooltip(I18n.t("frames.onion.before")));
    after.setTooltip(new Tooltip(I18n.t("frames.onion.after")));
    Label beforeLabel = new Label(I18n.t("frames.onion.before.short"));
    beforeLabel.getStyleClass().add("onion-before");
    Label afterLabel = new Label(I18n.t("frames.onion.after.short"));
    afterLabel.getStyleClass().add("onion-after");
    javafx.scene.layout.GridPane onion = new javafx.scene.layout.GridPane();
    onion.setHgap(6);
    onion.setVgap(2);
    onion.addRow(0, beforeLabel, before);
    onion.addRow(1, afterLabel, after);

    message.getStyleClass().add("text-muted");
    message.setWrapText(true);
    message.setMaxWidth(180);
    getChildren().addAll(scroll, actions, new Separator(javafx.geometry.Orientation.VERTICAL),
        previewBox, playback, new Separator(javafx.geometry.Orientation.VERTICAL), onion,
        message);

    player.setCycleCount(Timeline.INDEFINITE);
    // a closed tab stops the preview
    sceneProperty().addListener((o, a, scene) -> {
      if (scene == null) {
        player.stop();
      }
    });
    refresh();
  }

  int onionBefore() {
    return before.getValue();
  }

  int onionAfter() {
    return after.getValue();
  }

  List<Path> frames() {
    return frames;
  }

  /** Re-reads the frames from disk and marks the one shown. */
  void refresh() {
    frames = sequence.frames().stream().map(p -> p.toAbsolutePath().normalize()).toList();
    images.clear();
    for (Path frame : frames) {
      images.add(isShown(frame) ? host.picture() : load(frame));
    }
    thumbs.getChildren().clear();
    for (int i = 0; i < frames.size(); i++) {
      thumbs.getChildren().add(thumb(i));
    }
    previewFrame = Math.max(0, current());
    preview.setImage(images.isEmpty() ? null : images.get(previewFrame));
    restartPlayer();
  }

  /** Redraws the thumbnail of the frame shown (after a stroke). */
  void updateShown() {
    int at = current();
    if (at < 0) {
      return;
    }
    WritableImage picture = host.picture();
    images.set(at, picture);
    if (thumbs.getChildren().get(at) instanceof VBox cell
        && cell.getChildren().getFirst() instanceof StackPane box
        && box.getChildren().getFirst() instanceof ImageView view) {
      view.setImage(picture);
    }
    if (!play.isSelected()) {
      preview.setImage(picture);
    }
  }

  /** The index of the frame shown, or -1. */
  int current() {
    Path shown = host.file().toAbsolutePath().normalize();
    return frames.indexOf(shown);
  }

  private boolean isShown(Path frame) {
    return frame.equals(host.file().toAbsolutePath().normalize());
  }

  private Node thumb(int index) {
    ImageView view = new ImageView(images.get(index));
    view.setFitWidth(THUMB);
    view.setFitHeight(THUMB);
    view.setPreserveRatio(true);
    view.setSmooth(false);
    StackPane box = new StackPane(view);
    box.setPrefSize(THUMB + 6, THUMB + 6);
    box.getStyleClass().add("frame-thumb");
    if (index == current()) {
      box.getStyleClass().add("selected");
    }
    Label number = new Label(Integer.toString(index + 1));
    number.getStyleClass().add("text-muted");
    VBox cell = new VBox(2, box, number);
    cell.setAlignment(Pos.CENTER);
    cell.setCursor(javafx.scene.Cursor.HAND);
    Tooltip.install(cell, new Tooltip(frames.get(index).getFileName().toString()));
    cell.setOnMouseClicked(e -> select(index));
    return cell;
  }

  /** Paints frame {@code index} (0-based), saving the frame shown first. */
  void select(int index) {
    if (index == current() || index < 0 || index >= frames.size()) {
      return;
    }
    if (host.saveFrame()) {
      host.showFrame(frames.get(index));
    }
  }

  void addBlank() {
    WritableImage picture = host.picture();
    change(() -> sequence.addBlank((int) picture.getWidth(), (int) picture.getHeight()));
  }

  void duplicate() {
    int number = current() + 1;
    change(() -> sequence.duplicate(number));
  }

  /** Moves the frame shown to the project's trash; its unsaved strokes go with it. */
  void delete() {
    int number = current() + 1;
    if (frames.size() <= 1 || number < 1) {
      message.setText(I18n.t("frames.delete.last"));
      return;
    }
    try {
      sequence.delete(number);
      host.framesChanged();
      host.showFrame(sequence.frame(Math.min(number, frames.size() - 1)));
      message.setText(I18n.t("frames.deleted", number));
    } catch (IOException | RuntimeException e) {
      message.setText(I18n.t("frames.error", e.getMessage()));
    }
  }

  /** Swaps the frame shown with its neighbour and keeps painting it there. */
  void move(int by) {
    int number = current() + 1;
    int target = number + by;
    if (number < 1 || target < 1 || target > frames.size()) {
      return;
    }
    change(() -> {
      sequence.swap(number, target);
      return sequence.frame(target);
    });
  }

  private interface FileChange {
    Path apply() throws IOException;
  }

  /** Saves the frame shown, changes the files, then shows the frame the change returns. */
  private void change(FileChange change) {
    if (!host.saveFrame()) {
      return;
    }
    try {
      Path next = change.apply();
      host.framesChanged();
      host.showFrame(next);
      message.setText("");
    } catch (IOException | RuntimeException e) {
      message.setText(I18n.t("frames.error", e.getMessage()));
    }
  }

  private void restartPlayer() {
    player.stop();
    player.getKeyFrames().clear();
    if (!play.isSelected() || images.isEmpty()) {
      int at = current();
      preview.setImage(images.isEmpty() ? null : images.get(Math.max(0, at)));
      return;
    }
    player.getKeyFrames().add(new KeyFrame(Duration.millis(1000.0 / fps.getValue()), e -> {
      previewFrame = (previewFrame + 1) % images.size();
      // the frame shown plays as painted so far
      preview.setImage(previewFrame == current() ? host.picture() : images.get(previewFrame));
    }));
    player.play();
  }

  private static Image load(Path frame) {
    try (InputStream in = Files.newInputStream(frame)) {
      return new Image(in);
    } catch (IOException e) {
      return null;
    }
  }
}
