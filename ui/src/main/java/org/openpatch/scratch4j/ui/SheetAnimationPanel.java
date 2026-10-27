package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.region.SpriteAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * The image editor's animation preview for a sprite sheet: the tile size,
 * the animation's frames as a strip in playback order (remove one with its
 * x, drag to reorder), a looping preview of the picture as painted so far,
 * and the {@code addAnimation(name, sheet, w, h, frames...)} line to copy
 * into a sprite, with the {@code setAnimationInterval} that plays it at the
 * preview's speed. The frames are picked on the editor's canvas (see
 * {@link #toggleTile} and {@link #setRun}); tiles are numbered row by row
 * from 0, as the library counts them. An animation the project's code
 * already makes of the sheet can be the starting point.
 */
final class SheetAnimationPanel extends HBox {

  private static final double PREVIEW = 96;
  private static final double FRAME = 44;
  private static final int UNDO_LIMIT = 100;

  /**
   * One animation of the sheet as the code has it (or as set up here);
   * {@code fps} is the speed the class's {@code setAnimationInterval} gives, 0 for none.
   */
  record Animation(String name, int width, int height, List<Integer> frames, int fps) {
    @Override
    public String toString() {
      return name + "  (" + width + "×" + height + ": "
          + String.join(", ", frames.stream().map(String::valueOf).toList()) + ")";
    }
  }

  private final String reference;
  private final Supplier<WritableImage> picture;
  private final Runnable onChanged;
  private final TextField name = new TextField("idle");
  private final Spinner<Integer> tileWidth = new Spinner<>(1, 4096, 16);
  private final Spinner<Integer> tileHeight = new Spinner<>(1, 4096, 16);
  private final TextField framesField = new TextField();
  private final ToggleButton pick = new ToggleButton(I18n.t("sheetanim.pick"),
      Icons.of("mdi2g-gesture-tap"));
  private final HBox strip = new HBox(6);
  // canvases, not ImageViews: an ImageView smooths when scaled even with setSmooth(false)
  private final javafx.scene.canvas.Canvas preview =
      new javafx.scene.canvas.Canvas(PREVIEW, PREVIEW);
  private final ToggleButton play = new ToggleButton(null, Icons.of("fth-pause"));
  private final Spinner<Integer> fps = new Spinner<>(1, 30, 8);
  private final javafx.scene.control.TextArea code = new javafx.scene.control.TextArea();
  private final Label message = new Label();
  private final Timeline player = new Timeline();
  private final Deque<List<Integer>> undoStack = new ArrayDeque<>();
  private final Deque<List<Integer>> redoStack = new ArrayDeque<>();
  private List<Integer> frames = new ArrayList<>();
  private int previewFrame;
  private boolean updating;

  /**
   * {@code reference} is the sheet's project-relative path, {@code picture}
   * gives it as painted so far, {@code known} are animations the code
   * already makes of it, {@code onChanged} redraws the canvas (grid, frames,
   * the frame playing).
   */
  SheetAnimationPanel(String reference, Supplier<WritableImage> picture, List<Animation> known,
      Runnable onChanged) {
    super(12);
    this.reference = reference;
    this.picture = picture;
    this.onChanged = onChanged;
    getStyleClass().add("sheet-animation");
    setPadding(new Insets(8, 10, 8, 10));

    // the starting point: an animation from the code, else a guess from the sheet's shape
    WritableImage sheet = picture.get();
    int[] guess = SheetGridDialog.guessTile((int) sheet.getWidth(), (int) sheet.getHeight());
    tileWidth.getValueFactory().setValue(guess[0]);
    tileHeight.getValueFactory().setValue(guess[1]);

    ComboBox<Animation> fromCode = new ComboBox<>();
    fromCode.getItems().setAll(known);
    fromCode.setPromptText(I18n.t("sheetanim.fromcode"));
    fromCode.setOnAction(e -> {
      if (fromCode.getValue() != null) {
        load(fromCode.getValue());
      }
    });
    fromCode.setVisible(!known.isEmpty());
    fromCode.setManaged(!known.isEmpty());
    fromCode.setMaxWidth(Double.MAX_VALUE);

    name.setPrefColumnCount(8);
    name.textProperty().addListener((o, a, b) -> updateCode());
    for (Spinner<Integer> spinner : List.of(tileWidth, tileHeight)) {
      spinner.setEditable(true);
      spinner.setPrefWidth(84);
      spinner.setMinWidth(84);
      spinner.valueProperty().addListener((o, a, b) -> {
        if (!updating) {
          framesChanged(true);
        }
      });
    }
    framesField.setPromptText("0, 4, 8, 12");
    framesField.setPrefColumnCount(12);
    framesField.setTooltip(new Tooltip(I18n.t("sheetanim.frames.tooltip")));
    framesField.textProperty().addListener((o, a, text) -> {
      if (!updating) {
        frames = new ArrayList<>(parseFrames(text));
        framesChanged(false);
      }
    });

    GridPane form = new GridPane();
    form.setHgap(6);
    form.setVgap(6);
    form.addRow(0, label("sheetanim.name"), name);
    form.addRow(1, label("sheetanim.tile"), new HBox(4, tileWidth, new Label("×"),
        tileHeight));
    form.addRow(2, label("sheetanim.frames"), framesField);
    VBox settings = new VBox(6, fromCode, form);

    // picking on the canvas is on while the panel shows; off, the tools paint again
    pick.setSelected(true);
    pick.setTooltip(new Tooltip(I18n.t("sheetanim.pick.tooltip")));
    pick.setOnAction(e -> onChanged.run());
    HBox stripActions = new HBox(2, pick,
        Icons.button("fth-corner-up-left", I18n.t("imageeditor.undo"), this::undo),
        Icons.button("fth-corner-up-right", I18n.t("imageeditor.redo"), this::redo),
        Icons.button("fth-x", I18n.t("sheetanim.clear"), () -> setFrames(List.of())));
    stripActions.setAlignment(Pos.CENTER_LEFT);
    ScrollPane stripScroll = new ScrollPane(strip);
    stripScroll.setFitToHeight(true);
    stripScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    stripScroll.setPrefViewportHeight(FRAME + 30);
    stripScroll.getStyleClass().add("edge-to-edge");
    strip.setPadding(new Insets(2));
    strip.setAlignment(Pos.CENTER_LEFT);
    Label hint = new Label(I18n.t("sheetanim.pick.hint"));
    hint.getStyleClass().add("text-muted");
    hint.setWrapText(true);
    VBox stripBox = new VBox(4, stripActions, stripScroll, hint);
    HBox.setHgrow(stripBox, Priority.ALWAYS);
    stripBox.setMinWidth(200);

    StackPane previewBox = new StackPane(preview);
    previewBox.setPrefSize(PREVIEW + 8, PREVIEW + 8);
    previewBox.setMinSize(PREVIEW + 8, PREVIEW + 8);
    previewBox.getStyleClass().add("frame-preview");
    play.getStyleClass().addAll("button-icon", "flat");
    play.setSelected(true);
    play.setTooltip(new Tooltip(I18n.t("frames.play")));
    play.setOnAction(e -> {
      play.setGraphic(Icons.of(play.isSelected() ? "fth-pause" : "fth-play"));
      restartPlayer();
    });
    fps.setPrefWidth(70);
    fps.setMinWidth(70);
    fps.setTooltip(new Tooltip(I18n.t("frames.fps")));
    fps.valueProperty().addListener((o, a, b) -> {
      restartPlayer();
      updateCode();
    });
    Label fpsLabel = new Label(I18n.t("frames.fps.short"));
    fpsLabel.getStyleClass().add("text-muted");
    fpsLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    HBox playback = new HBox(4, play, fps, fpsLabel);
    playback.setAlignment(Pos.CENTER_LEFT);
    VBox previewColumn = new VBox(6, previewBox, playback);

    code.setEditable(false);
    code.setWrapText(true);
    code.setPrefRowCount(4);
    code.getStyleClass().add("code-line");
    Button copy = Icons.labeled("fth-clipboard", I18n.t("sheetanim.copy"), this::copyCode);
    copy.getStyleClass().add("accent");
    message.getStyleClass().add("text-muted");
    message.setWrapText(true);
    VBox codeColumn = new VBox(6, label("sheetanim.code"), code, copy, message);
    codeColumn.setPrefWidth(300);
    // the strip grows, but never into the code
    codeColumn.setMinWidth(260);

    getChildren().addAll(settings, new Separator(javafx.geometry.Orientation.VERTICAL),
        stripBox, new Separator(javafx.geometry.Orientation.VERTICAL), previewColumn,
        new Separator(javafx.geometry.Orientation.VERTICAL), codeColumn);

    player.setCycleCount(Timeline.INDEFINITE);
    // a hidden panel or a closed tab stops the preview
    sceneProperty().addListener((o, a, scene) -> restartPlayer());
    visibleProperty().addListener((o, a, b) -> restartPlayer());
    if (!known.isEmpty()) {
      fromCode.getSelectionModel().selectFirst();
      load(known.getFirst());
    } else {
      framesChanged(true);
    }
  }

  private static Label label(String key) {
    Label label = new Label(I18n.t(key));
    label.getStyleClass().add("field-label");
    label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    return label;
  }

  int tileWidth() {
    return tileWidth.getValue();
  }

  int tileHeight() {
    return tileHeight.getValue();
  }

  /** The tiles of the animation in order (row by row from 0). */
  List<Integer> frames() {
    return List.copyOf(frames);
  }

  /** Whether clicks on the canvas pick frames (else the tools paint). */
  boolean picking() {
    return pick.isSelected();
  }

  void setPicking(boolean on) {
    pick.setSelected(on);
    onChanged.run();
  }

  /** The position in {@link #frames()} the preview shows, or -1. */
  int previewIndex() {
    return frames.isEmpty() ? -1 : Math.min(previewFrame, frames.size() - 1);
  }

  String code() {
    return code.getText();
  }

  void setFps(int value) {
    fps.getValueFactory().setValue(value);
  }

  int columns() {
    return columns((int) picture.get().getWidth(), tileWidth.getValue());
  }

  int rows() {
    return (int) picture.get().getHeight() / Math.max(1, tileHeight.getValue());
  }

  void load(Animation animation) {
    updating = true;
    name.setText(animation.name());
    tileWidth.getValueFactory().setValue(animation.width());
    tileHeight.getValueFactory().setValue(animation.height());
    if (animation.fps() > 0) {
      fps.getValueFactory().setValue(animation.fps());
    }
    updating = false;
    setFrames(animation.frames());
  }

  // --- picking (the editor's canvas calls these) ---------------------------------

  /**
   * A click on a tile: a tile not in the animation is added at the end, one
   * in it is taken out; with {@code repeat} it is added again (0, 1, 0, 2).
   */
  void toggleTile(int tile, boolean repeat) {
    List<Integer> next = new ArrayList<>(frames);
    if (!repeat && next.contains(tile)) {
      next.remove(Integer.valueOf(tile));
    } else {
      next.add(tile);
    }
    setFrames(next);
  }

  /** A drag along a row or column: {@code run} becomes the animation (or is added to it). */
  void setRun(List<Integer> run, boolean append) {
    List<Integer> next = new ArrayList<>(append ? frames : List.of());
    next.addAll(run);
    setFrames(next);
  }

  /** The frames as one undoable step. */
  void setFrames(List<Integer> next) {
    if (next.equals(frames)) {
      return;
    }
    undoStack.push(List.copyOf(frames));
    if (undoStack.size() > UNDO_LIMIT) {
      undoStack.removeLast();
    }
    redoStack.clear();
    frames = new ArrayList<>(next);
    framesChanged(true);
  }

  void undo() {
    if (!undoStack.isEmpty()) {
      redoStack.push(List.copyOf(frames));
      frames = new ArrayList<>(undoStack.pop());
      framesChanged(true);
    }
  }

  void redo() {
    if (!redoStack.isEmpty()) {
      undoStack.push(List.copyOf(frames));
      frames = new ArrayList<>(redoStack.pop());
      framesChanged(true);
    }
  }

  /** "Row 2, column 1 (tile 5)" for the status bar. */
  static String describe(int tile, int columns) {
    return I18n.t("sheetanim.tile.at", tile / Math.max(1, columns) + 1,
        tile % Math.max(1, columns) + 1, tile);
  }

  /**
   * The tiles from {@code from} to {@code to} along the row or the column
   * they share most: dragging mostly sideways stays in {@code from}'s row,
   * mostly up or down in its column. In drag order, ends included.
   */
  static List<Integer> run(int columns, int from, int to) {
    int r0 = from / columns;
    int c0 = from % columns;
    int r1 = to / columns;
    int c1 = to % columns;
    List<Integer> run = new ArrayList<>();
    if (Math.abs(c1 - c0) >= Math.abs(r1 - r0)) {
      int step = c1 >= c0 ? 1 : -1;
      for (int c = c0; ; c += step) {
        run.add(r0 * columns + c);
        if (c == c1) break;
      }
    } else {
      int step = r1 >= r0 ? 1 : -1;
      for (int r = r0; ; r += step) {
        run.add(r * columns + c0);
        if (r == r1) break;
      }
    }
    return run;
  }

  // --- the strip of chosen frames ------------------------------------------------

  private void framesChanged(boolean writeField) {
    if (writeField) {
      updating = true;
      framesField.setText(String.join(", ", frames.stream().map(String::valueOf).toList()));
      updating = false;
    }
    int count = tileCount();
    long outside = frames.stream().filter(f -> f >= count).count();
    message.setText(outside > 0 ? I18n.t("sheetanim.outside", count) : "");
    rebuildStrip();
    updateCode();
    previewFrame = 0;
    restartPlayer();
    onChanged.run();
  }

  private void rebuildStrip() {
    strip.getChildren().clear();
    for (int i = 0; i < frames.size(); i++) {
      strip.getChildren().add(frameCell(i));
    }
    if (frames.isEmpty()) {
      Label empty = new Label(I18n.t("sheetanim.empty"));
      empty.getStyleClass().add("text-muted");
      strip.getChildren().add(empty);
    }
    markPlaying();
  }

  /** Frame {@code index} of the animation: its tile, its place, an x to take it out. */
  private VBox frameCell(int index) {
    int tile = frames.get(index);
    javafx.scene.canvas.Canvas view = new javafx.scene.canvas.Canvas(FRAME, FRAME);
    if (tile < tileCount()) {
      drawTile(view, picture.get(), tileRect(tile, columns()));
    }
    Button remove = Icons.button("fth-x", I18n.t("sheetanim.remove"), () -> {
      List<Integer> next = new ArrayList<>(frames);
      next.remove(index);
      setFrames(next);
    });
    remove.getStyleClass().add("frame-remove");
    StackPane box = new StackPane(view, remove);
    StackPane.setAlignment(remove, Pos.TOP_RIGHT);
    box.setPrefSize(FRAME + 6, FRAME + 6);
    box.getStyleClass().add("frame-thumb");
    Label number = new Label((index + 1) + "  ·  #" + tile);
    number.getStyleClass().add("text-muted");
    VBox cell = new VBox(2, box, number);
    cell.setAlignment(Pos.CENTER);
    cell.getStyleClass().add("frame-cell");
    Tooltip.install(cell, new Tooltip(describe(tile, columns())));
    // a click shows this frame (the preview pauses); drag to move it
    cell.setOnMouseClicked(e -> {
      play.setSelected(false);
      play.setGraphic(Icons.of("fth-play"));
      previewFrame = index;
      restartPlayer();
      onChanged.run();
    });
    cell.setOnDragDetected(e -> {
      var board = cell.startDragAndDrop(TransferMode.MOVE);
      ClipboardContent content = new ClipboardContent();
      content.putString(Integer.toString(index));
      board.setContent(content);
      board.setDragView(view.snapshot(null, null));
      e.consume();
    });
    cell.setOnDragOver(e -> {
      if (e.getGestureSource() instanceof VBox source && source.getParent() == strip) {
        e.acceptTransferModes(TransferMode.MOVE);
      }
      e.consume();
    });
    cell.setOnDragDropped(e -> {
      move(Integer.parseInt(e.getDragboard().getString()), index);
      e.setDropCompleted(true);
      e.consume();
    });
    return cell;
  }

  /** Moves frame {@code from} to place {@code to}. */
  void move(int from, int to) {
    if (from == to || from < 0 || from >= frames.size() || to < 0 || to >= frames.size()) {
      return;
    }
    List<Integer> next = new ArrayList<>(frames);
    next.add(to, next.remove(from));
    setFrames(next);
  }

  /** Outlines the frame the preview shows. */
  private void markPlaying() {
    int playing = previewIndex();
    for (int i = 0; i < strip.getChildren().size(); i++) {
      strip.getChildren().get(i).pseudoClassStateChanged(PLAYING, i == playing);
    }
  }

  private static final javafx.css.PseudoClass PLAYING =
      javafx.css.PseudoClass.getPseudoClass("selected");

  private int tileCount() {
    return columns() * Math.max(0, rows());
  }

  private javafx.geometry.Rectangle2D tileRect(int index, int columns) {
    int w = tileWidth.getValue();
    int h = tileHeight.getValue();
    return new javafx.geometry.Rectangle2D(index % columns * w, index / columns * h, w, h);
  }

  private void updateCode() {
    code.setText(code(name.getText().strip(), reference, tileWidth.getValue(),
        tileHeight.getValue(), frames, fps.getValue()));
  }

  void copyCode() {
    ClipboardContent content = new ClipboardContent();
    content.putString(code.getText());
    Clipboard.getSystemClipboard().setContent(content);
    message.setText(I18n.t("sheetanim.copied"));
  }

  /** Redraws the strip and the preview (the picture changed). */
  void pictureChanged() {
    rebuildStrip();
    restartPlayer();
  }

  private void restartPlayer() {
    player.stop();
    player.getKeyFrames().clear();
    showPreviewFrame();
    if (!play.isSelected() || frames.isEmpty() || getScene() == null || !isVisible()) {
      return;
    }
    player.getKeyFrames().add(new KeyFrame(Duration.millis(1000.0 / fps.getValue()), e -> {
      previewFrame = (previewFrame + 1) % Math.max(1, frames.size());
      showPreviewFrame();
      onChanged.run();
    }));
    player.play();
  }

  /** The preview shows the sheet as painted so far, cropped to one frame. */
  private void showPreviewFrame() {
    markPlaying();
    int count = tileCount();
    if (frames.isEmpty() || previewFrame >= frames.size() || frames.get(previewFrame) >= count) {
      preview.getGraphicsContext2D().clearRect(0, 0, PREVIEW, PREVIEW);
      return;
    }
    drawTile(preview, picture.get(), tileRect(frames.get(previewFrame), columns()));
  }

  /** One tile, scaled to fit the canvas, crisp (pixel art stays pixel art). */
  private static void drawTile(javafx.scene.canvas.Canvas canvas, WritableImage sheet,
      javafx.geometry.Rectangle2D tile) {
    var g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    g.setImageSmoothing(false);
    double scale = Math.min(canvas.getWidth() / tile.getWidth(),
        canvas.getHeight() / tile.getHeight());
    double w = tile.getWidth() * scale;
    double h = tile.getHeight() * scale;
    g.drawImage(sheet, tile.getMinX(), tile.getMinY(), tile.getWidth(), tile.getHeight(),
        (canvas.getWidth() - w) / 2, (canvas.getHeight() - h) / 2, w, h);
  }

  boolean playing() {
    return player.getStatus() == javafx.animation.Animation.Status.RUNNING;
  }

  // --- without the toolkit (tests) ------------------------------------------------

  /** Tiles per row, as the library counts them. */
  static int columns(int sheetWidth, int tileWidth) {
    return Math.max(0, sheetWidth / Math.max(1, tileWidth));
  }

  /** The tile numbers in {@code text} ("0, 1 2;3"), skipping what is not a number. */
  static List<Integer> parseFrames(String text) {
    List<Integer> frames = new ArrayList<>();
    for (String part : text.split("[^0-9]+")) {
      if (!part.isEmpty() && part.length() < 6) {
        frames.add(Integer.parseInt(part));
      }
    }
    return frames;
  }

  /** {@code this.addAnimation("name", "sheet", w, h, 0, 1, ...);} and its interval. */
  static String code(String name, String reference, int width, int height,
      List<Integer> frames, int fps) {
    StringBuilder line = new StringBuilder("this.addAnimation(\"")
        .append(name.isEmpty() ? "animation" : name).append("\", \"").append(reference)
        .append("\", ").append(width).append(", ").append(height);
    for (int frame : frames) {
      line.append(", ").append(frame);
    }
    return line.append(");\n").append(interval(fps)).toString();
  }

  /** {@code this.setAnimationInterval(0.125);}: each frame shows for 1/fps seconds. */
  static String interval(int fps) {
    double seconds = Math.round(1000.0 / Math.max(1, fps)) / 1000.0;
    return "this.setAnimationInterval(" + seconds + ");";
  }

  private static final java.util.regex.Pattern INTERVAL = java.util.regex.Pattern.compile(
      "this\\.setAnimationInterval\\(\\s*([0-9]*\\.?[0-9]+)\\s*\\)");

  /** The preview speed a literal {@code setAnimationInterval} in {@code source} gives, or 0. */
  static int fps(String source) {
    java.util.regex.Matcher matcher = INTERVAL.matcher(source);
    if (!matcher.find()) {
      return 0;
    }
    double seconds = Double.parseDouble(matcher.group(1));
    return seconds <= 0 ? 0 : (int) Math.max(1, Math.min(30, Math.round(1 / seconds)));
  }

  /** The sheet animations the project's code makes of {@code reference} (literal calls). */
  static List<Animation> known(List<Path> sources, String reference) {
    List<Animation> found = new ArrayList<>();
    for (Path source : sources) {
      try {
        String text = Files.readString(source);
        for (SpriteAssets.Entry entry : SpriteAssets.list(text)) {
          if (entry.isSheetAnimation() && reference.equals(entry.reference())
              && entry.sheetFrames() != null && !entry.sheetFrames().isEmpty()) {
            found.add(new Animation(entry.name(), entry.tileWidth(), entry.tileHeight(),
                List.copyOf(entry.sheetFrames()), fps(text)));
          }
        }
      } catch (java.io.IOException | RuntimeException e) {
        // a class that does not parse has no animations to offer
      }
    }
    return found;
  }
}
