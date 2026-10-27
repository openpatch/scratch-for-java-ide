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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.region.SpriteAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The image editor's animation preview for a sprite sheet: the tile size,
 * which tiles make the animation (numbered row by row from 0, as the library
 * counts them; click a tile to add it), a looping preview of the picture as
 * painted so far, and the {@code addAnimation(name, sheet, w, h, frames...)}
 * line to copy into a sprite. An animation that already uses the sheet in
 * the project's code can be loaded as the starting point.
 */
final class SheetAnimationPanel extends HBox {

  private static final double PREVIEW = 96;
  private static final double TILE = 36;

  /** One animation of the sheet as the code has it (or as set up here). */
  record Animation(String name, int width, int height, List<Integer> frames) {
    @Override
    public String toString() {
      return name + "  (" + width + "×" + height + ": "
          + String.join(", ", frames.stream().map(String::valueOf).toList()) + ")";
    }
  }

  private final String reference;
  private final Supplier<WritableImage> picture;
  private final Runnable onGridChanged;
  private final TextField name = new TextField("idle");
  private final Spinner<Integer> tileWidth = new Spinner<>(1, 4096, 16);
  private final Spinner<Integer> tileHeight = new Spinner<>(1, 4096, 16);
  private final TextField framesField = new TextField();
  private final FlowPane tiles = new FlowPane(4, 4);
  // canvases, not ImageViews: an ImageView smooths when scaled even with setSmooth(false)
  private final javafx.scene.canvas.Canvas preview =
      new javafx.scene.canvas.Canvas(PREVIEW, PREVIEW);
  private final ToggleButton play = new ToggleButton(null, Icons.of("fth-pause"));
  private final Spinner<Integer> fps = new Spinner<>(1, 30, 8);
  private final javafx.scene.control.TextArea code = new javafx.scene.control.TextArea();
  private final Label message = new Label();
  private final Timeline player = new Timeline();
  private List<Integer> frames = new ArrayList<>();
  private int previewFrame;
  private boolean updating;

  /**
   * {@code reference} is the sheet's project-relative path, {@code picture}
   * gives it as painted so far, {@code known} are animations the code
   * already makes of it, {@code onGridChanged} redraws the canvas's grid.
   */
  SheetAnimationPanel(String reference, Supplier<WritableImage> picture, List<Animation> known,
      Runnable onGridChanged) {
    super(12);
    this.reference = reference;
    this.picture = picture;
    this.onGridChanged = onGridChanged;
    getStyleClass().add("sheet-animation");
    setPadding(new Insets(8, 10, 8, 10));

    // the starting point: an animation from the code, else a guess from the sheet's shape
    WritableImage sheet = picture.get();
    int[] guess = guessTile((int) sheet.getWidth(), (int) sheet.getHeight());
    tileWidth.getValueFactory().setValue(guess[0]);
    tileHeight.getValueFactory().setValue(guess[1]);
    frames = new ArrayList<>(firstRow((int) sheet.getWidth(), guess[0]));

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
      spinner.valueProperty().addListener((o, a, b) -> {
        if (!updating) {
          gridChanged();
        }
      });
    }
    framesField.setPromptText("0, 1, 2, 3");
    framesField.setPrefColumnCount(14);
    framesField.setTooltip(new Tooltip(I18n.t("sheetanim.frames.tooltip")));
    framesField.textProperty().addListener((o, a, text) -> {
      if (!updating) {
        frames = new ArrayList<>(parseFrames(text));
        framesChanged(false);
      }
    });
    Button clear = Icons.button("fth-x", I18n.t("sheetanim.clear"), () -> {
      frames.clear();
      framesChanged(true);
    });
    Button row = Icons.button("fth-more-horizontal", I18n.t("sheetanim.row"), () -> {
      frames = new ArrayList<>(firstRow((int) picture.get().getWidth(), tileWidth.getValue()));
      framesChanged(true);
    });

    GridPane form = new GridPane();
    form.setHgap(6);
    form.setVgap(6);
    form.addRow(0, label("sheetanim.name"), name);
    form.addRow(1, label("sheetanim.tile"), new HBox(4, tileWidth, new Label("×"),
        tileHeight));
    HBox framesRow = new HBox(2, framesField, row, clear);
    framesRow.setAlignment(Pos.CENTER_LEFT);
    form.addRow(2, label("sheetanim.frames"), framesRow);
    VBox settings = new VBox(6, fromCode, form);

    ScrollPane tileScroll = new ScrollPane(tiles);
    tileScroll.setFitToWidth(true);
    tileScroll.setPrefViewportHeight(2 * TILE + 26);
    tileScroll.getStyleClass().add("edge-to-edge");
    tiles.setPadding(new Insets(2));
    Label tilesHint = new Label(I18n.t("sheetanim.tiles.hint"));
    tilesHint.getStyleClass().add("text-muted");
    VBox tileBox = new VBox(4, tilesHint, tileScroll);
    HBox.setHgrow(tileBox, Priority.ALWAYS);
    tileBox.setMinWidth(160);

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
    fps.valueProperty().addListener((o, a, b) -> restartPlayer());
    Label fpsLabel = new Label(I18n.t("frames.fps.short"));
    fpsLabel.getStyleClass().add("text-muted");
    fpsLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    HBox playback = new HBox(4, play, fps, fpsLabel);
    playback.setAlignment(Pos.CENTER_LEFT);
    VBox previewColumn = new VBox(6, previewBox, playback);

    code.setEditable(false);
    code.setWrapText(true);
    code.setPrefRowCount(3);
    code.getStyleClass().add("code-line");
    Button copy = Icons.labeled("fth-clipboard", I18n.t("sheetanim.copy"), this::copyCode);
    copy.getStyleClass().add("accent");
    message.getStyleClass().add("text-muted");
    message.setWrapText(true);
    VBox codeColumn = new VBox(6, label("sheetanim.code"), code, copy, message);
    codeColumn.setPrefWidth(300);

    getChildren().addAll(settings, new Separator(javafx.geometry.Orientation.VERTICAL), tileBox,
        new Separator(javafx.geometry.Orientation.VERTICAL), previewColumn,
        new Separator(javafx.geometry.Orientation.VERTICAL), codeColumn);

    player.setCycleCount(Timeline.INDEFINITE);
    // a hidden panel or a closed tab stops the preview
    sceneProperty().addListener((o, a, scene) -> restartPlayer());
    visibleProperty().addListener((o, a, b) -> restartPlayer());
    if (!known.isEmpty()) {
      fromCode.getSelectionModel().selectFirst();
      load(known.getFirst());
    } else {
      gridChanged();
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

  String code() {
    return code.getText();
  }

  void load(Animation animation) {
    updating = true;
    name.setText(animation.name());
    tileWidth.getValueFactory().setValue(animation.width());
    tileHeight.getValueFactory().setValue(animation.height());
    updating = false;
    frames = new ArrayList<>(animation.frames());
    gridChanged();
  }

  /** Tile size changed: new tile buttons; the frames stay as numbers. */
  private void gridChanged() {
    rebuildTiles();
    framesChanged(true);
    onGridChanged.run();
  }

  private void framesChanged(boolean writeField) {
    if (writeField) {
      updating = true;
      framesField.setText(String.join(", ", frames.stream().map(String::valueOf).toList()));
      updating = false;
    }
    int count = tileCount();
    long outside = frames.stream().filter(f -> f >= count).count();
    message.setText(outside > 0 ? I18n.t("sheetanim.outside", count) : "");
    markTiles();
    updateCode();
    previewFrame = 0;
    restartPlayer();
    onGridChanged.run();
  }

  /** The sheet's tiles as buttons; a click appends the tile to the animation. */
  private void rebuildTiles() {
    tiles.getChildren().clear();
    WritableImage sheet = picture.get();
    int columns = columns((int) sheet.getWidth(), tileWidth.getValue());
    int count = tileCount();
    for (int i = 0; i < count; i++) {
      int index = i;
      javafx.scene.canvas.Canvas view = new javafx.scene.canvas.Canvas(TILE, TILE);
      drawTile(view, sheet, tileRect(i, columns));
      Label number = new Label(Integer.toString(i));
      number.getStyleClass().add("tile-number");
      StackPane cell = new StackPane(view, number);
      StackPane.setAlignment(number, Pos.TOP_LEFT);
      cell.setPrefSize(TILE + 6, TILE + 6);
      cell.getStyleClass().add("frame-thumb");
      cell.setCursor(javafx.scene.Cursor.HAND);
      Tooltip.install(cell, new Tooltip(I18n.t("sheetanim.tile.add", i)));
      cell.setOnMouseClicked(e -> {
        frames.add(index);
        framesChanged(true);
      });
      tiles.getChildren().add(cell);
    }
  }

  /** Outlines the tiles the animation uses. */
  private void markTiles() {
    for (int i = 0; i < tiles.getChildren().size(); i++) {
      tiles.getChildren().get(i).pseudoClassStateChanged(USED, frames.contains(i));
    }
  }

  private static final javafx.css.PseudoClass USED =
      javafx.css.PseudoClass.getPseudoClass("selected");

  private int tileCount() {
    WritableImage sheet = picture.get();
    return columns((int) sheet.getWidth(), tileWidth.getValue())
        * Math.max(0, (int) sheet.getHeight() / tileHeight.getValue());
  }

  private javafx.geometry.Rectangle2D tileRect(int index, int columns) {
    int w = tileWidth.getValue();
    int h = tileHeight.getValue();
    return new javafx.geometry.Rectangle2D(index % columns * w, index / columns * h, w, h);
  }

  private void updateCode() {
    code.setText(code(name.getText().strip(), reference, tileWidth.getValue(),
        tileHeight.getValue(), frames));
  }

  void copyCode() {
    ClipboardContent content = new ClipboardContent();
    content.putString(code.getText());
    Clipboard.getSystemClipboard().setContent(content);
    message.setText(I18n.t("sheetanim.copied"));
  }

  /** Redraws the preview (the picture changed). */
  void pictureChanged() {
    rebuildTiles();
    markTiles();
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
    }));
    player.play();
  }

  /** The preview shows the sheet as painted so far, cropped to one frame. */
  private void showPreviewFrame() {
    int count = tileCount();
    if (frames.isEmpty() || previewFrame >= frames.size() || frames.get(previewFrame) >= count) {
      preview.getGraphicsContext2D().clearRect(0, 0, PREVIEW, PREVIEW);
      return;
    }
    WritableImage sheet = picture.get();
    drawTile(preview, sheet, tileRect(frames.get(previewFrame),
        columns((int) sheet.getWidth(), tileWidth.getValue())));
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

  /**
   * A first guess of the tile size: square tiles as tall as a strip that is
   * wider than tall (or as wide as one that is taller than wide), else the
   * whole picture.
   */
  static int[] guessTile(int width, int height) {
    if (width >= height && width % height == 0) {
      return new int[] {height, height};
    }
    if (height > width && height % width == 0) {
      return new int[] {width, width};
    }
    return new int[] {width, height};
  }

  /** The tiles of the first row: 0, 1, ... */
  static List<Integer> firstRow(int sheetWidth, int tileWidth) {
    List<Integer> row = new ArrayList<>();
    for (int i = 0; i < columns(sheetWidth, tileWidth); i++) {
      row.add(i);
    }
    return row;
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

  /** {@code this.addAnimation("name", "sheet", w, h, 0, 1, ...);} */
  static String code(String name, String reference, int width, int height,
      List<Integer> frames) {
    StringBuilder line = new StringBuilder("this.addAnimation(\"")
        .append(name.isEmpty() ? "animation" : name).append("\", \"").append(reference)
        .append("\", ").append(width).append(", ").append(height);
    for (int frame : frames) {
      line.append(", ").append(frame);
    }
    return line.append(");").toString();
  }

  /** The sheet animations the project's code makes of {@code reference} (literal calls). */
  static List<Animation> known(List<Path> sources, String reference) {
    List<Animation> found = new ArrayList<>();
    for (Path source : sources) {
      try {
        for (SpriteAssets.Entry entry : SpriteAssets.list(Files.readString(source))) {
          if (entry.isSheetAnimation() && reference.equals(entry.reference())
              && entry.sheetFrames() != null && !entry.sheetFrames().isEmpty()) {
            found.add(new Animation(entry.name(), entry.tileWidth(), entry.tileHeight(),
                List.copyOf(entry.sheetFrames())));
          }
        }
      } catch (java.io.IOException | RuntimeException e) {
        // a class that does not parse has no animations to offer
      }
    }
    return found;
  }
}
