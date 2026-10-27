package org.openpatch.scratch4j.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Background;
import javafx.scene.control.Separator;
import javafx.geometry.Pos;
import javafx.scene.Node;
import org.openpatch.scratch4j.core.io.EditorHistory;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.assets.FrameSequence;
import org.openpatch.scratch4j.core.region.SpriteRegionWriter;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The paint editor (costumes and backdrops): pencil, eraser, fill, line,
 * rectangle, ellipse, text, eyedropper, colour picker with alpha, zoom,
 * flip/rotate, canvas resize, undo/redo, PNG save - plus the two Scratch for
 * Java tools: the rotation centre and the hitbox polygon, which write their
 * result into a sprite class's setup region.
 */
final class ImageEditorView extends BorderPane implements SavableTool {

  private final ScratchProject project;
  /** The picture shown; the frame strip switches it among a sequence's frames. */
  private Path file;
  private final javafx.beans.property.StringProperty fileName =
      new javafx.beans.property.SimpleStringProperty();
  /** The frames of the numbered sequence {@code file} belongs to, or null. */
  private FrameStrip frameStrip;
  private java.util.function.Predicate<Path> mayShowFrame = frame -> true;
  private java.util.function.BiConsumer<Path, Path> onFrameShown = (from, to) -> { };
  private Runnable onFramesChanged = () -> { };
  /** The animation preview of a sprite sheet, made when first shown; null until then. */
  private SheetAnimationPanel sheetPanel;
  private ToggleButton sheetToggle;
  private final VBox bottomBox = new VBox();
  private WritableImage image;
  /** One view per layer, bottom first; the overlay sits on top. */
  // layers are painted on a canvas: an ImageView of a WritableImage smooths
  // when scaled even with setSmooth(false), a canvas follows setImageSmoothing
  private final javafx.scene.canvas.Canvas layerCanvas = new javafx.scene.canvas.Canvas();
  private final List<WritableImage> layers = new ArrayList<>();
  private final List<Boolean> layerVisible = new ArrayList<>();
  private int activeLayer;
  private final javafx.scene.control.ListView<Integer> layerList =
      new javafx.scene.control.ListView<>();
  private final javafx.scene.canvas.Canvas overlay = new javafx.scene.canvas.Canvas();
  private final StackPane stack = new StackPane(layerCanvas, overlay);
  private final ScratchColorPicker colorPicker;
  private PalettePanel palettePanel;
  private final ComboBox<Integer> brushSize = new ComboBox<>();

  private enum Tool { SELECT, PENCIL, ERASER, FILL, LINE, RECT, ELLIPSE, TEXT, PICKER,
    CENTER, HITBOX, NINE_SLICE }

  private enum SelectDrag { NONE, AREA, MOVE, SCALE }

  private Tool tool = Tool.PENCIL;
  private final java.util.Map<Tool, ToggleButton> toolButtons = new java.util.EnumMap<>(Tool.class);
  private double zoom = 4;
  private Point2D dragStart;
  private Point2D rotationCenter;
  private final List<Point2D> hitboxPoints = new ArrayList<>();
  /** Margins in the library's top, right, bottom, left order. */
  private final int[] nineSlice = {8, 8, 8, 8};
  private int draggedSlice = -1;
  // select tool: a pixel rectangle; once moved or scaled its pixels float above
  // the image (the hole is already cleared) until committed
  private javafx.geometry.Rectangle2D selection;
  private WritableImage floating;
  private double floatX;
  private double floatY;
  private double floatW;
  private double floatH;
  private SelectDrag selectDrag = SelectDrag.NONE;
  private double moveDX;
  private boolean areaDragged;
  private double moveDY;
  private final Button cropButton = new Button();
  private java.util.function.Consumer<Path> onSpriteWritten = file -> { };
  private java.util.function.Consumer<Path> onSaved = file -> { };
  // edits since the last save: the tab shows a marker
  private final javafx.beans.property.BooleanProperty dirty =
      new javafx.beans.property.SimpleBooleanProperty();
  /**
   * The onion skin: frames before the one shown (tinted red) and after it
   * (tinted blue), nearest first, drawn faintly over the picture.
   */
  private List<Image> onionBefore = List.of();
  private List<Image> onionAfter = List.of();
  private int onionBeforeCount = 1;
  private int onionAfterCount = 1;
  private boolean showOnionSkin;
  private ToggleButton onionToggle;
  private static final double ONION_ALPHA = 0.35;
  private static final Color BEFORE_TINT = Color.rgb(230, 60, 60);
  private static final Color AFTER_TINT = Color.rgb(60, 120, 240);

  /** Undo entries hold every layer (strokes and layer edits undo alike). */
  private record Snapshot(List<WritableImage> layers, List<Boolean> visible, int active) {}

  private final Deque<Snapshot> undoStack = new ArrayDeque<>();
  private final Deque<Snapshot> redoStack = new ArrayDeque<>();

  private final ScrollPane scroll = new ScrollPane();
  private final Label zoomLabel = new Label();
  private final Label status = new Label();
  private final Button applyToSprite = new Button();
  private boolean showPixelGrid;
  // smooth zoomed pixels like the game window does, unless it is set to pixel art
  private boolean smooth;
  private boolean showFacingGuide;
  private boolean fitted;

  ImageEditorView(ScratchProject project, Path file) throws IOException {
    this.project = project;
    this.file = file;
    this.fileName.set(file.getFileName().toString());
    this.colorPicker = new ScratchColorPicker(Color.BLACK, project == null ? null : project.root());
    getStyleClass().add("paint-editor");
    Image loaded = loadImage(file);
    this.image = new WritableImage(loaded.getPixelReader(),
        (int) Math.max(1, loaded.getWidth()), (int) Math.max(1, loaded.getHeight()));
    layers.add(image);
    layerVisible.add(true);
    smooth = !windowUsesPixelArt(project);

    stack.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    stack.setBackground(new Background(new BackgroundFill(checkerboard(), null, null)));
    stack.getStyleClass().add("paint-surface");
    stack.setOnMousePressed(e -> {
      stack.requestFocus();
      if (tool == Tool.SELECT) {
        selectPress(e.getX() / zoom, e.getY() / zoom);
      } else {
        press(toPixel(e.getX(), e.getY()));
        redrawOverlay();
      }
    });
    stack.setOnMouseDragged(e -> {
      if (tool == Tool.SELECT) {
        selectDrag(e.getX() / zoom, e.getY() / zoom, e.isShiftDown());
      } else {
        drag(toPixel(e.getX(), e.getY()));
      }
    });
    stack.setOnMouseReleased(e -> {
      if (tool == Tool.SELECT) {
        selectRelease();
      } else {
        release(toPixel(e.getX(), e.getY()));
      }
      updateFrameThumb();
    });
    stack.setFocusTraversable(true);
    stack.setCursor(javafx.scene.Cursor.CROSSHAIR);

    StackPane holder = new StackPane(stack);
    holder.setPadding(new Insets(24));
    holder.getStyleClass().add("paint-holder");
    scroll.setContent(holder);
    scroll.setFitToWidth(true);
    scroll.setFitToHeight(true);
    scroll.getStyleClass().add("paint-scroll");
    scroll.viewportBoundsProperty().addListener((o, old, bounds) -> {
      if (!fitted && bounds.getWidth() > 50) {
        fitted = true;
        fitZoom();
      }
    });
    applyZoom();

    setTop(topBar());
    setLeft(toolRail());
    setCenter(scroll);
    palettePanel = new PalettePanel(project == null ? null : project.root(),
        colorPicker.valueProperty());
    palettePanel.setPicture(this::flattenedPicture,
        file.getFileName().toString().replaceFirst("\\.[^.]+$", ""));
    Node layers = layerPanel();
    VBox.setVgrow(layers, Priority.ALWAYS);
    VBox side = new VBox(palettePanel, new Separator(), layers);
    side.getStyleClass().add("paint-side");
    setRight(side);
    Label credits = new Label(I18n.t("library.credits"));
    credits.getStyleClass().add("text-muted");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bottom = new HBox(8, status, spacer, credits);
    bottom.getStyleClass().add("editor-status");
    FrameSequence sequence = project == null ? null : sequenceOf(project.root(), file);
    if (sequence != null) {
      frameStrip = new FrameStrip(sequence, frameHost());
      bottomBox.getChildren().add(frameStrip);
    }
    bottomBox.getChildren().add(bottom);
    setBottom(bottomBox);
    updateOnionToggle();
    // the usual shortcuts anywhere in the editor (Ctrl is Cmd on macOS)
    addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
      if (EditorKeys.isEditorKey(e)
          && shortcut(e.getCode(), e.isShortcutDown(), e.isShiftDown())) {
        e.consume();
      }
    });
    loadHistory();
    updateApplyButton();
    updateStatus();
    redrawOverlay();
    warnIfFacingUp();
  }

  /**
   * Scratch for Java turns a sprite by its costume facing right (direction
   * 90). A costume whose visible pixels are much taller than wide was most
   * likely drawn facing up: say so once, with a rotate button.
   */
  private void warnIfFacingUp() {
    if (!looksFacingUp(image)) {
      return;
    }
    Label text = new Label(I18n.t("imageeditor.facing.warning"));
    text.setWrapText(true);
    Button rotate = new Button(I18n.t("imageeditor.rotate"), Icons.of("fth-rotate-cw"));
    HBox bar = new HBox(10, Icons.of("fth-alert-triangle"), text, rotate);
    bar.getStyleClass().add("facing-warning");
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(6, 10, 6, 10));
    HBox.setHgrow(text, Priority.ALWAYS);
    Node top = getTop();
    VBox stacked = new VBox(top, bar);
    rotate.setOnAction(e -> {
      transform(ImageEditorView::rotate90);
      stacked.getChildren().remove(bar);
    });
    setTop(stacked);
  }

  /** Visible (non-transparent) pixels at least 1.6x taller than wide. */
  static boolean looksFacingUp(Image image) {
    PixelReader reader = image.getPixelReader();
    int w = (int) image.getWidth();
    int h = (int) image.getHeight();
    int minX = w;
    int maxX = -1;
    int minY = h;
    int maxY = -1;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if ((reader.getArgb(x, y) >>> 24) > 24) {
          minX = Math.min(minX, x);
          maxX = Math.max(maxX, x);
          minY = Math.min(minY, y);
          maxY = Math.max(maxY, y);
        }
      }
    }
    if (maxX < 0) {
      return false;
    }
    int width = maxX - minX + 1;
    int height = maxY - minY + 1;
    return height >= 16 && height >= 1.6 * width;
  }

  Path file() {
    return file;
  }

  /** The name of the picture shown (the tab's title follows it between frames). */
  javafx.beans.property.ReadOnlyStringProperty fileNameProperty() {
    return fileName;
  }

  FrameStrip frameStrip() {
    return frameStrip;
  }

  /** Asked before the frame strip shows {@code frame} (e.g. it is open in another tab). */
  void setMayShowFrame(java.util.function.Predicate<Path> check) {
    mayShowFrame = check;
  }

  /** Called with the old and new file after the frame strip switched frames. */
  void setOnFrameShown(java.util.function.BiConsumer<Path, Path> action) {
    onFrameShown = action;
  }

  /** Called after the frame strip added, removed or renamed frame files. */
  void setOnFramesChanged(Runnable action) {
    onFramesChanged = action;
  }

  /**
   * The numbered sequence {@code file} is a frame of ({@code walk2.png} in
   * {@code walk1.png, walk2.png, ...}, numbered from 1), or null.
   */
  static FrameSequence sequenceOf(Path root, Path file) {
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("(.*?)(\\d+)(\\.[A-Za-z]+)$").matcher(file.getFileName().toString());
    Path folder = file.toAbsolutePath().normalize().getParent();
    Path base = root.toAbsolutePath().normalize();
    if (!m.matches() || m.group(1).contains("%") || m.group(2).startsWith("0")
        || folder == null || !folder.startsWith(base)) {
      return null;
    }
    String dir = base.relativize(folder).toString().replace('\\', '/');
    FrameSequence sequence = new FrameSequence(base,
        (dir.isEmpty() ? "" : dir + "/") + m.group(1) + "%d" + m.group(3));
    Path self = file.toAbsolutePath().normalize();
    return sequence.frames().stream().anyMatch(f -> f.toAbsolutePath().normalize().equals(self))
        ? sequence : null;
  }

  private FrameStrip.Host frameHost() {
    return new FrameStrip.Host() {
      @Override
      public Path file() {
        return file;
      }

      @Override
      public boolean saveFrame() {
        commitFloating();
        if (dirty.get()) {
          save();
        }
        return !dirty.get();
      }

      @Override
      public boolean showFrame(Path frame) {
        return ImageEditorView.this.showFrame(frame);
      }

      @Override
      public WritableImage picture() {
        return layers.size() == 1 ? image : flattened();
      }

      @Override
      public void onionChanged(int before, int after) {
        onionBeforeCount = before;
        onionAfterCount = after;
        loadOnion();
        redrawOverlay();
      }

      @Override
      public void framesChanged() {
        onFramesChanged.run();
      }
    };
  }

  /**
   * Shows {@code frame} in this editor, read from disk with its own undo
   * history; the frame shown before must be saved already (the strip does).
   */
  boolean showFrame(Path frame) {
    if (!mayShowFrame.test(frame)) {
      return false;
    }
    Image loaded;
    try {
      loaded = loadImage(frame);
    } catch (IOException e) {
      alert(I18n.t("error.openfile", e.getMessage()));
      return false;
    }
    Path previous = file;
    file = frame;
    fileName.set(frame.getFileName().toString());
    if (sheetPanel != null) {
      bottomBox.getChildren().remove(sheetPanel);
      sheetPanel = null;
      sheetToggle.setSelected(false);
    }
    selection = null;
    floating = null;
    selectDrag = SelectDrag.NONE;
    image = new WritableImage(loaded.getPixelReader(),
        (int) Math.max(1, loaded.getWidth()), (int) Math.max(1, loaded.getHeight()));
    layers.clear();
    layerVisible.clear();
    layers.add(image);
    layerVisible.add(true);
    activeLayer = 0;
    undoStack.clear();
    redoStack.clear();
    encoded.clear();
    loadHistory();
    dirty.set(false);
    loadOnion();
    refreshLayerList();
    applyZoom();
    updateApplyButton();
    updateStatus();
    redrawOverlay();
    if (frameStrip != null) {
      frameStrip.refresh();
    }
    onFrameShown.accept(previous, frame);
    return true;
  }

  private void updateFrameThumb() {
    if (frameStrip != null) {
      frameStrip.updateShown();
    }
    if (sheetPanel != null && sheetPanel.isVisible()) {
      sheetPanel.pictureChanged();
    }
  }

  /** Shows or hides the sprite sheet's animation preview under the canvas. */
  void showSheetAnimation(boolean show) {
    sheetToggle.setSelected(show);
    if (show && sheetPanel == null) {
      List<SheetAnimationPanel.Animation> known;
      try {
        known = SheetAnimationPanel.known(project.javaSources(), reference());
      } catch (IOException e) {
        known = List.of();
      }
      sheetPanel = new SheetAnimationPanel(reference(), () -> layers.size() == 1 ? image
          : flattened(), known, this::redrawOverlay);
      bottomBox.getChildren().add(bottomBox.getChildren().size() - 1, sheetPanel);
    }
    if (sheetPanel != null) {
      sheetPanel.setVisible(show);
      sheetPanel.setManaged(show);
    }
    redrawOverlay();
  }

  SheetAnimationPanel sheetPanel() {
    return sheetPanel;
  }

  /** The picture's path as code names it. */
  private String reference() {
    return project.root().toAbsolutePath().normalize()
        .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  /** The sheet's tile grid, and the animation's tiles numbered in their order. */
  private void drawSheetGrid(javafx.scene.canvas.GraphicsContext g) {
    int w = sheetPanel.tileWidth();
    int h = sheetPanel.tileHeight();
    int columns = SheetAnimationPanel.columns((int) image.getWidth(), w);
    int rows = (int) image.getHeight() / Math.max(1, h);
    g.setStroke(Color.rgb(133, 92, 214, 0.55));
    g.setLineWidth(1);
    for (int x = 0; x <= columns; x++) {
      g.strokeLine(x * w * zoom + 0.5, 0, x * w * zoom + 0.5, rows * h * zoom);
    }
    for (int y = 0; y <= rows; y++) {
      g.strokeLine(0, y * h * zoom + 0.5, columns * w * zoom, y * h * zoom + 0.5);
    }
    List<Integer> frames = sheetPanel.frames();
    g.setLineWidth(2);
    g.setFont(javafx.scene.text.Font.font(11));
    for (int i = 0; i < frames.size(); i++) {
      int tile = frames.get(i);
      if (columns == 0 || tile >= columns * rows) {
        continue;
      }
      double x = tile % columns * w * zoom;
      double y = tile / columns * h * zoom;
      g.setStroke(Color.rgb(133, 92, 214));
      g.strokeRect(x + 1, y + 1, w * zoom - 2, h * zoom - 2);
      g.setFill(Color.rgb(133, 92, 214));
      g.fillRect(x + 1, y + 1, 18, 14);
      g.setFill(Color.WHITE);
      g.fillText(Integer.toString(i + 1), x + 4, y + 12);
    }
  }

  /** The onion skin button shows when there are frames around this one. */
  private void updateOnionToggle() {
    boolean frames = frameStrip != null || previousFrame(file) != null;
    onionToggle.setVisible(frames);
    onionToggle.setManaged(frames);
  }

  /** The frames around the one shown, as the onion skin draws them (tinted). */
  private void loadOnion() {
    onionBefore = List.of();
    onionAfter = List.of();
    if (!showOnionSkin) {
      return;
    }
    List<Path> frames;
    if (frameStrip != null) {
      frames = frameStrip.frames();
    } else {
      Path previous = previousFrame(file);
      frames = previous == null ? List.of() : List.of(previous.toAbsolutePath().normalize(),
          file.toAbsolutePath().normalize());
    }
    int at = frames.indexOf(file.toAbsolutePath().normalize());
    if (at < 0) {
      return;
    }
    onionBefore = tintedFrames(frames, at, -1, onionBeforeCount, BEFORE_TINT);
    onionAfter = tintedFrames(frames, at, 1, onionAfterCount, AFTER_TINT);
  }

  private static List<Image> tintedFrames(List<Path> frames, int at, int step, int count,
      Color tint) {
    List<Image> tinted = new ArrayList<>();
    for (int i = at + step; tinted.size() < count && i >= 0 && i < frames.size(); i += step) {
      try {
        tinted.add(tint(loadImage(frames.get(i)), tint));
      } catch (IOException e) {
        break;
      }
    }
    return tinted;
  }

  /** {@code source} with its colours mixed half-way to {@code tint}, alpha kept. */
  static WritableImage tint(Image source, Color tint) {
    int w = (int) source.getWidth();
    int h = (int) source.getHeight();
    WritableImage tinted = new WritableImage(Math.max(1, w), Math.max(1, h));
    PixelReader reader = source.getPixelReader();
    var writer = tinted.getPixelWriter();
    int tr = (int) Math.round(tint.getRed() * 255);
    int tg = (int) Math.round(tint.getGreen() * 255);
    int tb = (int) Math.round(tint.getBlue() * 255);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int argb = reader.getArgb(x, y);
        int r = ((argb >> 16 & 0xff) + tr) / 2;
        int g = ((argb >> 8 & 0xff) + tg) / 2;
        int b = ((argb & 0xff) + tb) / 2;
        writer.setArgb(x, y, argb & 0xff000000 | r << 16 | g << 8 | b);
      }
    }
    return tinted;
  }

  /** The farthest frame first and faintest, the nearest last. */
  private void drawOnion(javafx.scene.canvas.GraphicsContext g, List<Image> frames) {
    for (int i = frames.size() - 1; i >= 0; i--) {
      Image frame = frames.get(i);
      g.setGlobalAlpha(ONION_ALPHA / (i + 1));
      g.drawImage(frame, 0, 0, frame.getWidth() * zoom, frame.getHeight() * zoom);
    }
  }

  /** The pixels being edited (tests read them). */
  WritableImage image() {
    return image;
  }

  /** Paints one pixel of the active layer as one stroke (tests). */
  void paintPixelForTest(int x, int y, Color color) {
    pushUndo();
    image.getPixelWriter().setColor(x, y, color);
    redrawOverlay();
    updateFrameThumb();
  }

  /** Turns the onion skin on or off (tests; the toolbar button does this for users). */
  void setOnionForTest(boolean on) {
    onionToggle.setSelected(on);
    showOnionSkin = on;
    loadOnion();
    redrawOverlay();
  }

  /** Switches to the select tool (tests; the rail does this for users). */
  void useSelectTool() {
    tool = Tool.SELECT;
    updateApplyButton();
  }

  @Override
  public javafx.beans.property.ReadOnlyBooleanProperty dirtyProperty() {
    return dirty;
  }

  @Override
  public String unsavedCloseQuestion() {
    return I18n.t("imageeditor.close.unsaved");
  }

  /** Called after the picture was written to its file (previews elsewhere re-read it). */
  void setOnSaved(java.util.function.Consumer<Path> action) {
    onSaved = action;
  }

  /** The palette panel's pencil opens a {@code .gpl} file with this. */
  void setOnOpenFile(java.util.function.Consumer<Path> action) {
    palettePanel.setOnOpen(action);
  }

  /** Called after the palette panel wrote a {@code .gpl} file. */
  void setOnPaletteWritten(java.util.function.Consumer<Path> action) {
    palettePanel.setOnWritten(action);
  }

  /** Shows palette changes made elsewhere (the palette editor, another image). */
  void reloadPalettes() {
    palettePanel.reload();
  }

  PalettePanel palettePanel() {
    return palettePanel;
  }

  /** The colour the tools paint with. */
  Color paintColour() {
    return colorPicker.getValue();
  }

  /** The visible layers as an AWT image, for "palette from this picture". */
  private java.awt.image.BufferedImage flattenedPicture() {
    WritableImage flat = flattened();
    int w = (int) flat.getWidth();
    int h = (int) flat.getHeight();
    java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(w, h,
        java.awt.image.BufferedImage.TYPE_INT_ARGB);
    javafx.scene.image.PixelReader reader = flat.getPixelReader();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        picture.setRGB(x, y, reader.getArgb(x, y));
      }
    }
    return picture;
  }

  /** Called after a tool wrote into a sprite class (the host reloads code and designers). */
  void setOnSpriteWritten(java.util.function.Consumer<Path> action) {
    this.onSpriteWritten = action;
  }

  /** The frame before this one in a numbered sequence ({@code walk2.png} -> {@code walk1.png}). */
  static Path previousFrame(Path file) {
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("(.*?)(\\d+)(\\.[A-Za-z]+)$").matcher(file.getFileName().toString());
    if (!m.matches()) {
      return null;
    }
    int index = Integer.parseInt(m.group(2));
    if (index <= 0) {
      return null;
    }
    String previous = m.group(1) + (index - 1) + m.group(3);
    Path candidate = file.resolveSibling(previous);
    return Files.isRegularFile(candidate) ? candidate : null;
  }

  /** Whether the game window draws pictures crisp ({@code Window.usePixelArt()}). */
  private static boolean windowUsesPixelArt(ScratchProject project) {
    if (project == null) {
      return false;
    }
    try {
      String window = project.windowClass();
      if (window != null) {
        return org.openpatch.scratch4j.core.region.WindowDocument.read(
            Files.readString(project.sourceOf(window), StandardCharsets.UTF_8)).pixelArt();
      }
    } catch (IOException | RuntimeException ignored) {
      // hand-written window regions: fall back to the project setting
    }
    return project.settings().pixelArt;
  }

  private static Image loadImage(Path file) throws IOException {
    try (var in = Files.newInputStream(file)) {
      Image image = new Image(in);
      if (image.isError()) {
        throw new IOException(image.getException());
      }
      return image;
    }
  }

  /** A grey/white checkerboard: transparent pixels stay visible as such. */
  private static ImagePattern checkerboard() {
    WritableImage tile = new WritableImage(16, 16);
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        tile.getPixelWriter().setColor(x, y,
            (x < 8) == (y < 8) ? Color.web("#ffffff") : Color.web("#e6e8ee"));
      }
    }
    return new ImagePattern(tile, 0, 0, 16, 16, false);
  }

  private void applyZoom() {
    layerCanvas.setWidth(Math.max(1, image.getWidth() * zoom));
    layerCanvas.setHeight(Math.max(1, image.getHeight() * zoom));
    stack.setPrefSize(image.getWidth() * zoom, image.getHeight() * zoom);
    zoomLabel.setText(Math.round(zoom * 100) + "%");
    syncOverlay();
  }

  private void fitZoom() {
    var bounds = scroll.getViewportBounds();
    double fit = Math.min((bounds.getWidth() - 60) / image.getWidth(),
        (bounds.getHeight() - 60) / image.getHeight());
    setZoom(Math.max(1, Math.min(16, Math.floor(fit * 4) / 4)));
  }

  private void setZoom(double next) {
    zoom = Math.max(0.25, Math.min(32, next));
    applyZoom();
    redrawOverlay();
  }

  /** Paints the visible layers, bottom first, at the current zoom. */
  private void paintLayers() {
    var g = layerCanvas.getGraphicsContext2D();
    g.clearRect(0, 0, layerCanvas.getWidth(), layerCanvas.getHeight());
    g.setImageSmoothing(smooth);
    for (int i = 0; i < layers.size(); i++) {
      if (layerVisible.get(i)) {
        g.drawImage(layers.get(i), 0, 0, image.getWidth() * zoom, image.getHeight() * zoom);
      }
    }
  }

  private void syncOverlay() {
    overlay.setWidth(Math.max(1, image.getWidth() * zoom));
    overlay.setHeight(Math.max(1, image.getHeight() * zoom));
  }

  private Node toolRail() {
    ToggleGroup group = new ToggleGroup();
    toolButtons.clear();
    VBox rail = new VBox(4);
    rail.getStyleClass().add("tool-rail");
    for (Tool t : Tool.values()) {
      ToggleButton button = new ToggleButton(null, Icons.of(toolIcon(t), 18));
      javafx.scene.input.KeyCode key = toolKey(t);
      button.setTooltip(new javafx.scene.control.Tooltip(
          toolLabel(t) + (key == null ? "" : " (" + key.getName() + ")")));
      button.getStyleClass().add("tool-button");
      button.setToggleGroup(group);
      button.setUserData(t);
      button.setSelected(t == Tool.PENCIL);
      button.setOnAction(e -> chooseTool(t));
      toolButtons.put(t, button);
      if (t == Tool.CENTER) {
        rail.getChildren().add(new Separator());
      }
      rail.getChildren().add(button);
    }
    group.selectedToggleProperty().addListener((o, old, toggle) -> {
      if (toggle == null) {
        old.setSelected(true);
      }
    });
    // a short window scrolls the tools instead of pushing the frame strip off
    ScrollPane railScroll = new ScrollPane(rail);
    railScroll.setFitToWidth(true);
    railScroll.setFitToHeight(true);
    railScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    railScroll.setMinHeight(0);
    railScroll.getStyleClass().addAll("edge-to-edge", "tool-rail-scroll");
    return railScroll;
  }

  private void chooseTool(Tool t) {
    if (t != Tool.SELECT) {
      commitFloating();
      selection = null;
    }
    tool = t;
    ToggleButton button = toolButtons.get(t);
    if (button != null) button.setSelected(true);
    updateApplyButton();
    redrawOverlay();
  }

  /** The letter that picks a tool, as in most paint programs; null for none. */
  private static javafx.scene.input.KeyCode toolKey(Tool tool) {
    return switch (tool) {
      case SELECT -> javafx.scene.input.KeyCode.M;
      case PENCIL -> javafx.scene.input.KeyCode.B;
      case ERASER -> javafx.scene.input.KeyCode.E;
      case FILL -> javafx.scene.input.KeyCode.G;
      case LINE -> javafx.scene.input.KeyCode.L;
      case RECT -> javafx.scene.input.KeyCode.R;
      case ELLIPSE -> javafx.scene.input.KeyCode.O;
      case TEXT -> javafx.scene.input.KeyCode.T;
      case PICKER -> javafx.scene.input.KeyCode.I;
      default -> null;
    };
  }

  /** What the image editor copied last (also put on the system clipboard). */
  private static WritableImage clipboard;

  /**
   * One shortcut (Ctrl is Cmd on macOS); returns whether it was handled.
   * Save, undo/redo, select all, copy/cut/paste, zoom; Delete, Enter/Escape and
   * the arrow keys for the selection; a letter per tool; [ and ] for the brush.
   */
  boolean shortcut(javafx.scene.input.KeyCode code, boolean ctrl, boolean shift) {
    if (ctrl) {
      switch (code) {
        case S -> save();
        case Z -> {
          if (shift) redo(); else undo();
        }
        case Y -> redo();
        case A -> selectAll();
        case C -> copySelection();
        case X -> {
          if (copySelection()) deleteSelection();
        }
        case V -> paste();
        case PLUS, EQUALS, ADD -> setZoom(zoom * 1.5);
        case MINUS, SUBTRACT -> setZoom(zoom / 1.5);
        case DIGIT0, NUMPAD0 -> fitZoom();
        default -> {
          return false;
        }
      }
      return true;
    }
    switch (code) {
      case DELETE, BACK_SPACE -> {
        if (tool != Tool.SELECT || selection == null) return false;
        deleteSelection();
      }
      case ENTER, ESCAPE -> {
        if (tool != Tool.SELECT || selection == null) return false;
        commitFloating();
        selection = null;
        updateApplyButton();
        redrawOverlay();
      }
      case LEFT, RIGHT, UP, DOWN -> {
        if (tool != Tool.SELECT || selection == null) return false;
        int step = shift ? 10 : 1;
        lift();
        switch (code) {
          case LEFT -> floatX -= step;
          case RIGHT -> floatX += step;
          case UP -> floatY -= step;
          default -> floatY += step;
        }
        redrawOverlay();
      }
      case OPEN_BRACKET, CLOSE_BRACKET -> {
        List<Integer> sizes = brushSize.getItems();
        int at = Math.max(0, sizes.indexOf(brushSize.getValue()));
        at = Math.max(0, Math.min(sizes.size() - 1,
            at + (code == javafx.scene.input.KeyCode.OPEN_BRACKET ? -1 : 1)));
        brushSize.setValue(sizes.get(at));
      }
      default -> {
        for (Tool t : Tool.values()) {
          if (toolKey(t) == code) {
            chooseTool(t);
            return true;
          }
        }
        return false;
      }
    }
    return true;
  }

  /** Ctrl+A: the whole picture becomes the selection. */
  private void selectAll() {
    chooseTool(Tool.SELECT);
    commitFloating();
    selection = new javafx.geometry.Rectangle2D(0, 0, image.getWidth(), image.getHeight());
    updateApplyButton();
    redrawOverlay();
  }

  /** Ctrl+C: the selected pixels (as moved or scaled); false without a selection. */
  private boolean copySelection() {
    if (tool != Tool.SELECT || selection == null) {
      status.setText(I18n.t("imageeditor.copy.noselection"));
      return false;
    }
    WritableImage copied;
    if (floating != null) {
      // as it shows: scaled nearest neighbour like commitFloating stamps it
      int w = (int) floatW;
      int h = (int) floatH;
      copied = new WritableImage(w, h);
      PixelReader reader = floating.getPixelReader();
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          copied.getPixelWriter().setArgb(x, y, reader.getArgb(
              (int) Math.min(floating.getWidth() - 1, x * floating.getWidth() / w),
              (int) Math.min(floating.getHeight() - 1, y * floating.getHeight() / h)));
        }
      }
    } else {
      copied = new WritableImage(image.getPixelReader(), (int) selection.getMinX(),
          (int) selection.getMinY(), (int) selection.getWidth(), (int) selection.getHeight());
    }
    clipboard = copied;
    var content = new javafx.scene.input.ClipboardContent();
    content.putImage(copied);
    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    return true;
  }

  /** Ctrl+V: the copied picture floats at the top left (or the selection) to be placed. */
  private void paste() {
    Image pasted = null;
    var system = javafx.scene.input.Clipboard.getSystemClipboard();
    if (system.hasImage()) {
      pasted = system.getImage();
    }
    if (pasted == null || pasted.getWidth() < 1) {
      pasted = clipboard;
    }
    if (pasted == null) {
      return;
    }
    double x = selection == null ? 0 : selection.getMinX();
    double y = selection == null ? 0 : selection.getMinY();
    chooseTool(Tool.SELECT);
    commitFloating();
    pushUndo();
    floating = new WritableImage(pasted.getPixelReader(),
        (int) pasted.getWidth(), (int) pasted.getHeight());
    floatX = x;
    floatY = y;
    floatW = floating.getWidth();
    floatH = floating.getHeight();
    selection = new javafx.geometry.Rectangle2D(x, y, floatW, floatH);
    updateApplyButton();
    redrawOverlay();
  }

  private Node topBar() {
    colorPicker.setTooltip(new javafx.scene.control.Tooltip(I18n.t("imageeditor.color")));
    brushSize.getItems().addAll(1, 2, 4, 8, 16);
    brushSize.getSelectionModel().selectFirst();
    brushSize.setPrefWidth(76);
    brushSize.setMinWidth(Region.USE_PREF_SIZE);
    colorPicker.setMinWidth(Region.USE_PREF_SIZE);
    brushSize.setTooltip(new javafx.scene.control.Tooltip(I18n.t("imageeditor.brush")));
    applyToSprite.getStyleClass().add("accent");
    applyToSprite.setOnAction(e -> saveHitbox());
    Button save = new Button(I18n.t("imageeditor.save"), Icons.of("fth-save"));
    save.getStyleClass().add("success");
    save.setOnAction(e -> save());
    save.setTooltip(new javafx.scene.control.Tooltip(I18n.t("editor.save.tooltip")));
    save.setMinWidth(Region.USE_PREF_SIZE);
    applyToSprite.setMinWidth(Region.USE_PREF_SIZE);
    ToggleButton pixelGrid = new ToggleButton(null, Icons.of("fth-grid"));
    pixelGrid.getStyleClass().addAll("button-icon", "flat");
    pixelGrid.setTooltip(new javafx.scene.control.Tooltip(I18n.t("imageeditor.grid.tooltip")));
    pixelGrid.setOnAction(e -> {
      showPixelGrid = pixelGrid.isSelected();
      redrawOverlay();
    });
    ToggleButton facesRight = new ToggleButton(null, Icons.of("fth-arrow-right"));
    facesRight.getStyleClass().addAll("button-icon", "flat");
    facesRight.setTooltip(new javafx.scene.control.Tooltip(I18n.t("imageeditor.facing.tooltip")));
    facesRight.setOnAction(e -> {
      showFacingGuide = facesRight.isSelected();
      redrawOverlay();
    });
    ToggleButton smoothing = new ToggleButton(null, Icons.of("fth-aperture"));
    smoothing.getStyleClass().addAll("button-icon", "flat");
    smoothing.setTooltip(new javafx.scene.control.Tooltip(
        I18n.t("imageeditor.smooth.tooltip")));
    smoothing.setSelected(smooth);
    smoothing.setOnAction(e -> {
      smooth = smoothing.isSelected();
      applyZoom();
      redrawOverlay();
    });
    ToggleButton onion = new ToggleButton(null, Icons.of("mdi2l-layers-outline"));
    onionToggle = onion;
    onion.getStyleClass().addAll("button-icon", "flat");
    onion.setTooltip(new javafx.scene.control.Tooltip(I18n.t("imageeditor.onion.tooltip")));
    onion.setOnAction(e -> {
      showOnionSkin = onion.isSelected();
      loadOnion();
      redrawOverlay();
    });
    Button slice = Icons.button("fth-grid", I18n.t("slicer.title"), this::sliceSheet);
    sheetToggle = new ToggleButton(null, Icons.of("mdi2f-filmstrip"));
    sheetToggle.getStyleClass().addAll("button-icon", "flat");
    sheetToggle.setTooltip(new javafx.scene.control.Tooltip(I18n.t("sheetanim.tooltip")));
    sheetToggle.setVisible(project != null);
    sheetToggle.setManaged(project != null);
    sheetToggle.setOnAction(e -> showSheetAnimation(sheetToggle.isSelected()));
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar = new HBox(4,
        colorPicker, brushSize, new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-corner-up-left", I18n.t("imageeditor.undo"), this::undo),
        Icons.button("fth-corner-up-right", I18n.t("imageeditor.redo"), this::redo),
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("mdi2f-flip-horizontal", I18n.t("imageeditor.flipH"),
            () -> transform(ImageEditorView::flipHorizontal)),
        Icons.button("mdi2f-flip-vertical", I18n.t("imageeditor.flipV"),
            () -> transform(ImageEditorView::flipVertical)),
        Icons.button("fth-rotate-cw", I18n.t("imageeditor.rotate"),
            () -> transform(ImageEditorView::rotate90)),
        Icons.button("fth-crop", I18n.t("imageeditor.resize"), this::resize),
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-zoom-out", I18n.t("designer.zoomout"), () -> setZoom(zoom / 1.5)),
        zoomLabel,
        Icons.button("fth-zoom-in", I18n.t("designer.zoomin"), () -> setZoom(zoom * 1.5)),
        Icons.button("fth-maximize", I18n.t("designer.fit"), this::fitZoom),
        pixelGrid, smoothing, facesRight, onion, slice, sheetToggle,
        spacer, cropButton, applyToSprite, save);
    cropButton.setText(I18n.t("imageeditor.crop"));
    cropButton.setGraphic(Icons.of("fth-crop"));
    cropButton.setOnAction(e -> cropToSelection());
    cropButton.setMinWidth(Region.USE_PREF_SIZE);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    zoomLabel.getStyleClass().add("zoom-label");
    return bar;
  }

  /** Sprite-specific guides write into a class's managed setup region. */
  private void updateApplyButton() {
    boolean select = tool == Tool.SELECT;
    cropButton.setVisible(select && selection != null);
    cropButton.setManaged(select && selection != null);
    boolean center = tool == Tool.CENTER;
    boolean hitbox = tool == Tool.HITBOX;
    boolean slice = tool == Tool.NINE_SLICE;
    applyToSprite.setVisible(center || hitbox || slice);
    applyToSprite.setManaged(center || hitbox || slice);
    applyToSprite.setText(center ? I18n.t("imageeditor.center.apply")
        : hitbox ? I18n.t("imageeditor.hitbox.apply")
        : I18n.t("imageeditor.nineslice.apply"));
    applyToSprite.setGraphic(Icons.of(toolIcon(center ? Tool.CENTER
        : hitbox ? Tool.HITBOX : Tool.NINE_SLICE)));
    if (select) {
      status.setText(I18n.t("imageeditor.select.help"));
    } else if (slice) {
      status.setText(I18n.t("imageeditor.nineslice.help"));
    } else if (hitbox) {
      status.setText(I18n.t("imageeditor.hitbox.click"));
    } else if (center) {
      status.setText(I18n.t("imageeditor.center.click"));
    } else {
      updateStatus();
    }
    redrawOverlay();
  }

  private void updateStatus() {
    status.setText((int) image.getWidth() + " \u00D7 " + (int) image.getHeight() + " px");
  }

  private static String toolIcon(Tool tool) {
    return switch (tool) {
      case SELECT -> "mdi2s-selection";
      case PENCIL -> "mdi2p-pencil";
      case ERASER -> "mdi2e-eraser";
      case FILL -> "mdi2f-format-color-fill";
      case LINE -> "mdi2v-vector-line";
      case RECT -> "mdi2s-square-outline";
      case ELLIPSE -> "mdi2c-circle-outline";
      case TEXT -> "mdi2f-format-text";
      case PICKER -> "mdi2e-eyedropper";
      case CENTER -> "mdi2c-crosshairs";
      case HITBOX -> "mdi2v-vector-polygon";
      case NINE_SLICE -> "mdi2b-border-all";
    };
  }

  private static String toolLabel(Tool tool) {
    return switch (tool) {
      case SELECT -> I18n.t("imageeditor.select");
      case PENCIL -> I18n.t("imageeditor.pencil");
      case ERASER -> I18n.t("imageeditor.eraser");
      case FILL -> I18n.t("imageeditor.fill");
      case LINE -> I18n.t("imageeditor.line");
      case RECT -> I18n.t("imageeditor.rect");
      case ELLIPSE -> I18n.t("imageeditor.ellipse");
      case TEXT -> I18n.t("imageeditor.text");
      case PICKER -> I18n.t("imageeditor.picker");
      case CENTER -> I18n.t("imageeditor.center");
      case HITBOX -> I18n.t("imageeditor.hitbox");
      case NINE_SLICE -> I18n.t("imageeditor.nineslice");
    };
  }

  // --- pointer handling ----------------------------------------------------

  private Point2D toPixel(double sceneX, double sceneY) {
    double px = sceneX / zoom;
    double py = sceneY / zoom;
    return new Point2D(
        Math.min(image.getWidth() - 1, Math.max(0, Math.floor(px))),
        Math.min(image.getHeight() - 1, Math.max(0, Math.floor(py))));
  }

  private void press(Point2D p) {
    dragStart = p;
    switch (tool) {
      case PENCIL, ERASER -> {
        pushUndo();
        stroke(p, p);
      }
      case FILL -> {
        pushUndo();
        floodFill((int) p.getX(), (int) p.getY(), colorPicker.getValue());
      }
      case PICKER -> {
        colorPicker.setValue(image.getPixelReader()
            .getColor((int) p.getX(), (int) p.getY()));
      }
      case TEXT -> {
        TextInputDialog dialog = new TextInputDialog("Hello");
        dialog.getDialogPane().getButtonTypes().setAll(I18n.ok(), I18n.cancel());
        dialog.setHeaderText(null);
        dialog.showAndWait().ifPresent(text -> {
          pushUndo();
          drawText((int) p.getX(), (int) p.getY(), text);
        });
      }
      case CENTER -> {
        rotationCenter = p;
        redrawOverlay();
      }
      case HITBOX -> {
        hitboxPoints.add(p);
        redrawOverlay();
      }
      case NINE_SLICE -> draggedSlice = nearestSlice(p);
      default -> { }
    }
  }

  private void drag(Point2D p) {
    if (dragStart == null) {
      return;
    }
    switch (tool) {
      case PENCIL, ERASER -> stroke(dragStart, p);
      case LINE, RECT, ELLIPSE -> {
        // live preview on the overlay
        previewShape(dragStart, p);
      }
      case NINE_SLICE -> {
        moveSlice(p);
        redrawOverlay();
      }
      default -> { }
    }
    if (tool == Tool.PENCIL || tool == Tool.ERASER) {
      dragStart = p;
      redrawOverlay();
    }
  }

  private void release(Point2D p) {
    if (dragStart == null) {
      return;
    }
    switch (tool) {
      case LINE -> line(dragStart, p);
      case RECT -> rect(dragStart, p);
      case ELLIPSE -> ellipse(dragStart, p);
      default -> { }
    }
    dragStart = null;
    draggedSlice = -1;
    overlay.getGraphicsContext2D().clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
    redrawOverlay();
  }

  /** The sheet slicer works on the saved file (unsaved strokes are saved first). */
  private void sliceSheet() {
    commitFloating();
    if (!undoStack.isEmpty()) {
      save();
    }
    String reference = project.root().relativize(file).toString().replace('\\', '/');
    SheetSlicerDialog.show(project, copy(image), reference, classFile -> {
      onSpriteWritten.accept(classFile);
      status.setText(I18n.t("slicer.saved", classFile.getFileName()));
    });
  }

  // --- select / move / scale ----------------------------------------------

  /** The selection, or the floating pixels' box while they are lifted. */
  private javafx.geometry.Rectangle2D currentSelectionBox() {
    return floating != null
        ? new javafx.geometry.Rectangle2D(floatX, floatY, floatW, floatH) : selection;
  }

  private boolean onScaleHandle(double x, double y) {
    if (selection == null) {
      return false;
    }
    var r = currentSelectionBox();
    double tolerance = 8 / zoom;
    if (r.contains(x, y) && (r.getMaxX() - x >= Math.min(tolerance, r.getWidth() / 2)
        || r.getMaxY() - y >= Math.min(tolerance, r.getHeight() / 2))) {
      return false; // well inside a small selection: that is a move
    }
    return Math.abs(x - r.getMaxX()) <= tolerance && Math.abs(y - r.getMaxY()) <= tolerance;
  }

  void selectPress(double x, double y) {
    if (selection != null && onScaleHandle(x, y)) {
      lift();
      selectDrag = SelectDrag.SCALE;
    } else if (selection != null && currentSelectionBox().contains(x, y)) {
      lift();
      selectDrag = SelectDrag.MOVE;
      moveDX = x - floatX;
      moveDY = y - floatY;
    } else {
      commitFloating();
      selection = null;
      dragStart = new Point2D(clampX(Math.floor(x)), clampY(Math.floor(y)));
      selectDrag = SelectDrag.AREA;
      areaDragged = false;
    }
    updateApplyButton();
  }

  void selectDrag(double x, double y, boolean keepAspect) {
    switch (selectDrag) {
      case AREA -> {
        areaDragged = true;
        double x1 = clampX(Math.floor(x));
        double y1 = clampY(Math.floor(y));
        double minX = Math.min(dragStart.getX(), x1);
        double minY = Math.min(dragStart.getY(), y1);
        double w = Math.abs(x1 - dragStart.getX()) + 1;
        double h = Math.abs(y1 - dragStart.getY()) + 1;
        selection = new javafx.geometry.Rectangle2D(minX, minY, w, h);
      }
      case MOVE -> {
        floatX = Math.round(x - moveDX);
        floatY = Math.round(y - moveDY);
      }
      case SCALE -> {
        double w = Math.max(1, Math.round(x - floatX));
        double h = Math.max(1, Math.round(y - floatY));
        if (keepAspect) {
          double ratio = floating.getWidth() / floating.getHeight();
          h = Math.max(1, Math.round(w / ratio));
        }
        floatW = w;
        floatH = h;
      }
      default -> { }
    }
    redrawOverlay();
  }

  void selectRelease() {
    if (selectDrag == SelectDrag.AREA && !areaDragged) {
      selection = null; // a click outside deselects
    }
    selectDrag = SelectDrag.NONE;
    updateApplyButton();
    redrawOverlay();
  }

  private double clampX(double x) {
    return Math.max(0, Math.min(image.getWidth() - 1, x));
  }

  private double clampY(double y) {
    return Math.max(0, Math.min(image.getHeight() - 1, y));
  }

  /** Cuts the selected pixels out (the hole becomes transparent) so they can move. */
  private void lift() {
    if (floating != null || selection == null) {
      return;
    }
    pushUndo();
    int x0 = (int) selection.getMinX();
    int y0 = (int) selection.getMinY();
    int w = (int) selection.getWidth();
    int h = (int) selection.getHeight();
    floating = new WritableImage(image.getPixelReader(), x0, y0, w, h);
    PixelWriter writer = image.getPixelWriter();
    for (int y = y0; y < y0 + h; y++) {
      for (int x = x0; x < x0 + w; x++) {
        writer.setColor(x, y, Color.TRANSPARENT);
      }
    }
    floatX = x0;
    floatY = y0;
    floatW = w;
    floatH = h;
  }

  /** Stamps the floating pixels (nearest neighbour, so pixel art stays crisp). */
  void commitFloating() {
    if (floating == null) {
      return;
    }
    PixelReader reader = floating.getPixelReader();
    PixelWriter writer = image.getPixelWriter();
    int w = (int) floatW;
    int h = (int) floatH;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int tx = (int) floatX + x;
        int ty = (int) floatY + y;
        if (tx < 0 || ty < 0 || tx >= image.getWidth() || ty >= image.getHeight()) {
          continue;
        }
        int sx = (int) Math.min(floating.getWidth() - 1, x * floating.getWidth() / w);
        int sy = (int) Math.min(floating.getHeight() - 1, y * floating.getHeight() / h);
        Color c = reader.getColor(sx, sy);
        if (c.getOpacity() > 0) {
          writer.setColor(tx, ty, c);
        }
      }
    }
    double x0 = Math.max(0, floatX);
    double y0 = Math.max(0, floatY);
    double x1 = Math.min(image.getWidth(), floatX + w);
    double y1 = Math.min(image.getHeight(), floatY + h);
    selection = x1 > x0 && y1 > y0
        ? new javafx.geometry.Rectangle2D(x0, y0, x1 - x0, y1 - y0) : null;
    floating = null;
    redrawOverlay();
  }

  private void deleteSelection() {
    if (floating != null) {
      floating = null; // the hole was cleared when the pixels were lifted
    } else if (selection != null) {
      pushUndo();
      PixelWriter writer = image.getPixelWriter();
      for (int y = (int) selection.getMinY(); y < selection.getMaxY(); y++) {
        for (int x = (int) selection.getMinX(); x < selection.getMaxX(); x++) {
          writer.setColor(x, y, Color.TRANSPARENT);
        }
      }
    }
    selection = null;
    updateApplyButton();
    redrawOverlay();
  }

  /** Canvas crop: the image becomes exactly the selection. */
  void cropToSelection() {
    commitFloating();
    if (selection == null) {
      return;
    }
    pushUndo();
    javafx.geometry.Rectangle2D area = selection;
    layers.replaceAll(layer -> copy(new WritableImage(layer.getPixelReader(),
        (int) area.getMinX(), (int) area.getMinY(), (int) area.getWidth(),
        (int) area.getHeight())));
    selection = null;
    rebuildWith(layers.get(activeLayer));
    updateApplyButton();
  }

  // --- pixel drawing -------------------------------------------------------

  private void stroke(Point2D from, Point2D to) {
    Color color = tool == Tool.ERASER ? Color.TRANSPARENT : colorPicker.getValue();
    line(from, to, color);
  }

  private void line(Point2D from, Point2D to, Color color) {
    int x0 = (int) from.getX();
    int y0 = (int) from.getY();
    int x1 = (int) to.getX();
    int y1 = (int) to.getY();
    int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
    PixelWriter writer = image.getPixelWriter();
    int radius = brushSize.getValue() == null ? 1 : brushSize.getValue();
    for (int i = 0; i <= Math.max(0, steps); i++) {
      int x = steps == 0 ? x0 : x0 + (x1 - x0) * i / steps;
      int y = steps == 0 ? y0 : y0 + (y1 - y0) * i / steps;
      plot(writer, x, y, radius, color);
    }
  }

  private void line(Point2D from, Point2D to) {
    line(from, to, colorPicker.getValue());
  }

  private void rect(Point2D from, Point2D to) {
    int x0 = Math.min((int) from.getX(), (int) to.getX());
    int y0 = Math.min((int) from.getY(), (int) to.getY());
    int x1 = Math.max((int) from.getX(), (int) to.getX());
    int y1 = Math.max((int) from.getY(), (int) to.getY());
    line(new Point2D(x0, y0), new Point2D(x1, y0));
    line(new Point2D(x0, y1), new Point2D(x1, y1));
    line(new Point2D(x0, y0), new Point2D(x0, y1));
    line(new Point2D(x1, y0), new Point2D(x1, y1));
  }

  private void ellipse(Point2D from, Point2D to) {
    double cx = (from.getX() + to.getX()) / 2;
    double cy = (from.getY() + to.getY()) / 2;
    double rx = Math.abs(to.getX() - from.getX()) / 2;
    double ry = Math.abs(to.getY() - from.getY()) / 2;
    PixelWriter writer = image.getPixelWriter();
    for (int y = (int) Math.max(0, cy - ry); y <= Math.min(image.getHeight() - 1, cy + ry); y++) {
      for (int x = (int) Math.max(0, cx - rx); x <= Math.min(image.getWidth() - 1, cx + rx); x++) {
        double nx = (x - cx) / (rx == 0 ? 1 : rx);
        double ny = (y - cy) / (ry == 0 ? 1 : ry);
        double d = nx * nx + ny * ny;
        double innerX = ((x - 1) - cx) / (rx == 0 ? 1 : rx);
        double innerY = ((y - 1) - cy) / (ry == 0 ? 1 : ry);
        double dInner = innerX * innerX + innerY * innerY;
        if (d <= 1 && dInner >= 1 || d <= 1 && (x == cx - rx + 1 || y == cy - ry + 1)) {
          writer.setColor(x, y, colorPicker.getValue());
        }
      }
    }
  }

  private void plot(PixelWriter writer, int x, int y, int radius, Color color) {
    int r = radius - 1;
    for (int dy = -r; dy <= r; dy++) {
      for (int dx = -r; dx <= r; dx++) {
        int px = x + dx;
        int py = y + dy;
        if (px >= 0 && py >= 0 && px < image.getWidth() && py < image.getHeight()) {
          writer.setColor(px, py, color);
        }
      }
    }
  }

  private void floodFill(int x, int y, Color color) {
    PixelReader reader = image.getPixelReader();
    Color target = reader.getColor(x, y);
    if (target.equals(color)) {
      return;
    }
    boolean[] visited = new boolean[(int) image.getWidth() * (int) image.getHeight()];
    Deque<int[]> queue = new ArrayDeque<>();
    queue.add(new int[] {x, y});
    PixelWriter writer = image.getPixelWriter();
    while (!queue.isEmpty()) {
      int[] p = queue.poll();
      int px = p[0];
      int py = p[1];
      if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()) {
        continue;
      }
      if (visited[py * (int) image.getWidth() + px]) {
        continue;
      }
      visited[py * (int) image.getWidth() + px] = true;
      if (!reader.getColor(px, py).equals(target)) {
        continue;
      }
      writer.setColor(px, py, color);
      queue.add(new int[] {px + 1, py});
      queue.add(new int[] {px - 1, py});
      queue.add(new int[] {px, py + 1});
      queue.add(new int[] {px, py - 1});
    }
  }

  private void drawText(int x, int y, String text) {
    // rasterize text through a temporary canvas snapshot
    javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(
        image.getWidth(), image.getHeight());
    var g = canvas.getGraphicsContext2D();
    g.setFill(colorPicker.getValue());
    g.setFont(javafx.scene.text.Font.font("SansSerif", 20));
    g.fillText(text, x, y + 16);
    SnapshotParameters params = new SnapshotParameters();
    WritableImage snapshot = canvas.snapshot(params, null);
    PixelReader reader = snapshot.getPixelReader();
    PixelWriter writer = image.getPixelWriter();
    for (int py = 0; py < image.getHeight(); py++) {
      for (int px = 0; px < image.getWidth(); px++) {
        Color c = reader.getColor(px, py);
        if (c.getOpacity() > 0.5) {
          writer.setColor(px, py, colorPicker.getValue());
        }
      }
    }
  }

  private void previewShape(Point2D from, Point2D to) {
    redrawOverlay();
    var g = overlay.getGraphicsContext2D();
    g.setStroke(javafx.scene.paint.Color.ORANGE);
    g.setLineWidth(1);
    switch (tool) {
      case LINE -> g.strokeLine(from.getX() * zoom, from.getY() * zoom,
          to.getX() * zoom, to.getY() * zoom);
      case RECT -> g.strokeRect(Math.min(from.getX(), to.getX()) * zoom,
          Math.min(from.getY(), to.getY()) * zoom,
          Math.abs(to.getX() - from.getX()) * zoom,
          Math.abs(to.getY() - from.getY()) * zoom);
      case ELLIPSE -> g.strokeOval(Math.min(from.getX(), to.getX()) * zoom,
          Math.min(from.getY(), to.getY()) * zoom,
          Math.abs(to.getX() - from.getX()) * zoom,
          Math.abs(to.getY() - from.getY()) * zoom);
      default -> { }
    }
  }

  private void redrawOverlay() {
    paintLayers();
    var g = overlay.getGraphicsContext2D();
    g.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
    if (showOnionSkin) {
      g.setImageSmoothing(smooth);
      drawOnion(g, onionBefore);
      drawOnion(g, onionAfter);
      g.setGlobalAlpha(1);
    }
    if (sheetPanel != null && sheetPanel.isVisible()) {
      drawSheetGrid(g);
    }
    if (showPixelGrid && zoom >= 6 && image.getWidth() <= 1024
        && image.getHeight() <= 1024) {
      g.setStroke(Color.rgb(35, 45, 60, 0.28));
      g.setLineWidth(1);
      for (int x = 1; x < image.getWidth(); x++) {
        double at = x * zoom + 0.5;
        g.strokeLine(at, 0, at, overlay.getHeight());
      }
      for (int y = 1; y < image.getHeight(); y++) {
        double at = y * zoom + 0.5;
        g.strokeLine(0, at, overlay.getWidth(), at);
      }
    }
    if (showFacingGuide && overlay.getWidth() >= 24 && overlay.getHeight() >= 16) {
      double y = image.getHeight() * zoom / 2;
      double x = image.getWidth() * zoom / 2;
      double end = Math.min(overlay.getWidth() - 8, x + Math.max(16, 8 * zoom));
      g.setStroke(Color.rgb(255, 125, 0, 0.9));
      g.setLineWidth(2);
      g.strokeLine(x, y, end, y);
      g.strokeLine(end, y, end - 9, y - 6);
      g.strokeLine(end, y, end - 9, y + 6);
    }
    g.setLineWidth(1);
    if (tool == Tool.NINE_SLICE) {
      clampSlice();
      int width = (int) image.getWidth();
      int height = (int) image.getHeight();
      g.setStroke(Color.rgb(0, 120, 255, 0.9));
      g.setLineWidth(2);
      g.setLineDashes(6, 4);
      g.strokeLine(0, nineSlice[0] * zoom, overlay.getWidth(), nineSlice[0] * zoom);
      g.strokeLine((width - nineSlice[1]) * zoom, 0,
          (width - nineSlice[1]) * zoom, overlay.getHeight());
      g.strokeLine(0, (height - nineSlice[2]) * zoom,
          overlay.getWidth(), (height - nineSlice[2]) * zoom);
      g.strokeLine(nineSlice[3] * zoom, 0,
          nineSlice[3] * zoom, overlay.getHeight());
      g.setLineDashes();
      g.setLineWidth(1);
    }
    if (floating != null) {
      g.setImageSmoothing(smooth);
      g.drawImage(floating, floatX * zoom, floatY * zoom, floatW * zoom, floatH * zoom);
    }
    if (selection != null && tool == Tool.SELECT) {
      javafx.geometry.Rectangle2D r = currentSelectionBox();
      double x = r.getMinX() * zoom;
      double y = r.getMinY() * zoom;
      double w = r.getWidth() * zoom;
      double h = r.getHeight() * zoom;
      g.setLineWidth(1);
      g.setStroke(Color.WHITE);
      g.strokeRect(x + 0.5, y + 0.5, w, h);
      g.setStroke(Color.rgb(20, 20, 20));
      g.setLineDashes(4, 4);
      g.strokeRect(x + 0.5, y + 0.5, w, h);
      g.setLineDashes();
      g.setFill(Color.web("#855cd6"));
      g.fillRect(x + w - 5, y + h - 5, 10, 10);
    }
    if (rotationCenter != null) {
      g.setStroke(javafx.scene.paint.Color.RED);
      g.strokeLine((rotationCenter.getX() - 6) * zoom, rotationCenter.getY() * zoom,
          (rotationCenter.getX() + 6) * zoom, rotationCenter.getY() * zoom);
      g.strokeLine(rotationCenter.getX() * zoom, (rotationCenter.getY() - 6) * zoom,
          rotationCenter.getX() * zoom, (rotationCenter.getY() + 6) * zoom);
    }
    if (hitboxPoints.size() > 0) {
      g.setStroke(javafx.scene.paint.Color.rgb(0, 120, 255));
      double[] xs = new double[hitboxPoints.size()];
      double[] ys = new double[hitboxPoints.size()];
      for (int i = 0; i < hitboxPoints.size(); i++) {
        xs[i] = hitboxPoints.get(i).getX() * zoom;
        ys[i] = hitboxPoints.get(i).getY() * zoom;
      }
      g.strokePolygon(xs, ys, hitboxPoints.size());
    }
  }

  private int nearestSlice(Point2D point) {
    double width = image.getWidth(), height = image.getHeight();
    double[] distances = {Math.abs(point.getY() - nineSlice[0]),
        Math.abs(point.getX() - (width - nineSlice[1])),
        Math.abs(point.getY() - (height - nineSlice[2])),
        Math.abs(point.getX() - nineSlice[3])};
    int nearest = 0;
    for (int i = 1; i < distances.length; i++) {
      if (distances[i] < distances[nearest]) nearest = i;
    }
    return nearest;
  }

  private void clampSlice() {
    int width = (int) image.getWidth(), height = (int) image.getHeight();
    nineSlice[3] = Math.min(nineSlice[3], Math.max(0, width - 1));
    nineSlice[1] = Math.min(nineSlice[1], Math.max(0, width - nineSlice[3] - 1));
    nineSlice[0] = Math.min(nineSlice[0], Math.max(0, height - 1));
    nineSlice[2] = Math.min(nineSlice[2], Math.max(0, height - nineSlice[0] - 1));
  }

  private void moveSlice(Point2D point) {
    int width = (int) image.getWidth(), height = (int) image.getHeight();
    switch (draggedSlice) {
      case 0 -> nineSlice[0] = Math.max(0, Math.min((int) point.getY(),
          height - nineSlice[2] - 1));
      case 1 -> nineSlice[1] = Math.max(0, Math.min(width - (int) point.getX(),
          width - nineSlice[3] - 1));
      case 2 -> nineSlice[2] = Math.max(0, Math.min(height - (int) point.getY(),
          height - nineSlice[0] - 1));
      case 3 -> nineSlice[3] = Math.max(0, Math.min((int) point.getX(),
          width - nineSlice[1] - 1));
      default -> { }
    }
  }

  // --- image transforms ----------------------------------------------------

  private interface PixelTransform {
    WritableImage apply(WritableImage source);
  }

  private static WritableImage flipHorizontal(WritableImage source) {
    int w = (int) source.getWidth();
    int h = (int) source.getHeight();
    WritableImage out = new WritableImage(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        out.getPixelWriter().setColor(x, y, source.getPixelReader().getColor(w - 1 - x, y));
      }
    }
    return out;
  }

  private static WritableImage flipVertical(WritableImage source) {
    int w = (int) source.getWidth();
    int h = (int) source.getHeight();
    WritableImage out = new WritableImage(w, h);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        out.getPixelWriter().setColor(x, y, source.getPixelReader().getColor(x, h - 1 - y));
      }
    }
    return out;
  }

  private static WritableImage rotate90(WritableImage source) {
    int w = (int) source.getWidth();
    int h = (int) source.getHeight();
    WritableImage out = new WritableImage(h, w);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        out.getPixelWriter().setColor(h - 1 - y, x,
            source.getPixelReader().getColor(x, y));
      }
    }
    return out;
  }

  private void transform(PixelTransform transform) {
    pushUndo();
    layers.replaceAll(transform::apply);
    rebuildWith(layers.get(activeLayer));
  }

  private void resize() {
    Dialog<int[]> dialog = new Dialog<>();
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    javafx.scene.control.TextField widthField =
        new javafx.scene.control.TextField(String.valueOf((int) image.getWidth()));
    javafx.scene.control.TextField heightField =
        new javafx.scene.control.TextField(String.valueOf((int) image.getHeight()));
    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.setPadding(new Insets(8));
    grid.addRow(0, new Label("w"), widthField);
    grid.addRow(1, new Label("h"), heightField);
    dialog.getDialogPane().setContent(grid);
    dialog.setResultConverter(bt -> {
      if (bt == I18n.ok()) {
        try {
          return new int[] {Integer.parseInt(widthField.getText().trim()),
              Integer.parseInt(heightField.getText().trim())};
        } catch (NumberFormatException e) {
          return null;
        }
      }
      return null;
    });
    dialog.showAndWait().ifPresent(size -> {
      if (size[0] <= 0 || size[1] <= 0 || size[0] > 4096 || size[1] > 4096) {
        return;
      }
      pushUndo();
      layers.replaceAll(layer -> {
        WritableImage resized = new WritableImage(size[0], size[1]);
        PixelReader reader = layer.getPixelReader();
        PixelWriter writer = resized.getPixelWriter();
        for (int y = 0; y < Math.min(size[1], (int) layer.getHeight()); y++) {
          for (int x = 0; x < Math.min(size[0], (int) layer.getWidth()); x++) {
            writer.setColor(x, y, reader.getColor(x, y));
          }
        }
        return resized;
      });
      rebuildWith(layers.get(activeLayer));
    });
  }

  /** WritableImage is fixed-size, so transforms swap the editor's reference. */
  private void rebuildWith(WritableImage next) {
    image = next;
    if (layers.isEmpty()) {
      layers.add(next);
      layerVisible.add(true);
    }
    layers.set(activeLayer, next);
    refreshLayerList();
    applyZoom();
    redrawOverlay();
    updateStatus();
    updateFrameThumb();
  }

  private static WritableImage copy(WritableImage source) {
    return new WritableImage(source.getPixelReader(),
        (int) source.getWidth(), (int) source.getHeight());
  }

  // --- undo/redo -----------------------------------------------------------

  private Snapshot snapshot() {
    List<WritableImage> copies = new ArrayList<>();
    for (WritableImage layer : layers) {
      copies.add(copy(layer));
    }
    return new Snapshot(copies, List.copyOf(layerVisible), activeLayer);
  }

  private void restore(Snapshot snapshot) {
    layers.clear();
    layers.addAll(snapshot.layers());
    layerVisible.clear();
    layerVisible.addAll(snapshot.visible());
    activeLayer = Math.min(snapshot.active(), layers.size() - 1);
    rebuildWith(layers.get(activeLayer));
  }

  private void pushUndo() {
    dirty.set(true);
    undoStack.push(snapshot());
    if (undoStack.size() > 40) {
      undoStack.removeLast();
    }
    redoStack.clear();
  }

  private void undo() {
    if (floating != null) {
      floating = null;
      selection = null;
    }
    if (undoStack.isEmpty()) {
      return;
    }
    Snapshot previous = undoStack.pop();
    dirty.set(true);
    redoStack.push(snapshot());
    restore(previous);
  }

  private void redo() {
    if (redoStack.isEmpty()) {
      return;
    }
    Snapshot next = redoStack.pop();
    dirty.set(true);
    undoStack.push(snapshot());
    restore(next);
  }

  // --- layers ----------------------------------------------------------------

  /** The layer panel: visibility, add, duplicate, delete, reorder, merge down. */
  private Node layerPanel() {
    layerList.setPrefWidth(170);
    // shrinks before the palette and the frame strip do
    layerList.setMinHeight(60);
    layerList.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
      @Override
      protected void updateItem(Integer index, boolean empty) {
        super.updateItem(index, empty);
        if (empty || index == null) {
          setGraphic(null);
          setText(null);
          return;
        }
        javafx.scene.control.CheckBox visible = new javafx.scene.control.CheckBox();
        visible.setSelected(layerVisible.get(index));
        visible.setOnAction(e -> {
          pushUndo();
          layerVisible.set(index, visible.isSelected());
          redrawOverlay();
        });
        ImageView thumb = new ImageView(layers.get(index));
        thumb.setFitWidth(28);
        thumb.setFitHeight(28);
        thumb.setPreserveRatio(true);
        thumb.setSmooth(false);
        setGraphic(new HBox(6, visible, thumb));
        setText(I18n.t("layers.name", index + 1));
      }
    });
    layerList.getSelectionModel().selectedItemProperty().addListener((o, a, index) -> {
      if (index != null && index != activeLayer && index < layers.size()) {
        commitFloating();
        activeLayer = index;
        image = layers.get(index);
      }
    });
    HBox actions = new HBox(2,
        Icons.button("fth-plus", I18n.t("layers.add"), this::addLayer),
        Icons.button("fth-copy", I18n.t("layers.duplicate"), this::duplicateLayer),
        Icons.button("fth-arrow-up", I18n.t("layers.up"), () -> moveLayer(1)),
        Icons.button("fth-arrow-down", I18n.t("layers.down"), () -> moveLayer(-1)),
        Icons.button("fth-chevrons-down", I18n.t("layers.merge"), this::mergeDown),
        Icons.button("fth-trash-2", I18n.t("layers.delete"), this::deleteLayer));
    Label title = new Label(I18n.t("layers.title"));
    title.getStyleClass().add("card-title");
    VBox panel = new VBox(6, title, layerList, actions);
    VBox.setVgrow(layerList, Priority.ALWAYS);
    panel.setPadding(new Insets(8));
    panel.getStyleClass().add("layer-panel");
    refreshLayerList();
    return panel;
  }

  /** Top layer first, like every paint program. */
  private void refreshLayerList() {
    List<Integer> order = new ArrayList<>();
    for (int i = layers.size() - 1; i >= 0; i--) {
      order.add(i);
    }
    layerList.getItems().setAll(order);
    layerList.getSelectionModel().select(Integer.valueOf(activeLayer));
  }

  void addLayer() {
    commitFloating();
    pushUndo();
    layers.add(activeLayer + 1, new WritableImage((int) image.getWidth(), (int) image.getHeight()));
    layerVisible.add(activeLayer + 1, true);
    activeLayer++;
    rebuildWith(layers.get(activeLayer));
  }

  private void duplicateLayer() {
    commitFloating();
    pushUndo();
    layers.add(activeLayer + 1, copy(image));
    layerVisible.add(activeLayer + 1, true);
    activeLayer++;
    rebuildWith(layers.get(activeLayer));
  }

  private void deleteLayer() {
    if (layers.size() <= 1) {
      return;
    }
    commitFloating();
    pushUndo();
    layers.remove(activeLayer);
    layerVisible.remove(activeLayer);
    activeLayer = Math.max(0, activeLayer - 1);
    rebuildWith(layers.get(activeLayer));
  }

  private void moveLayer(int direction) {
    int target = activeLayer + direction;
    if (target < 0 || target >= layers.size()) {
      return;
    }
    commitFloating();
    pushUndo();
    java.util.Collections.swap(layers, activeLayer, target);
    java.util.Collections.swap(layerVisible, activeLayer, target);
    activeLayer = target;
    rebuildWith(layers.get(activeLayer));
  }

  /** The active layer is painted onto the one below it. */
  void mergeDown() {
    if (activeLayer == 0) {
      return;
    }
    commitFloating();
    pushUndo();
    WritableImage below = layers.get(activeLayer - 1);
    composite(below, layers.get(activeLayer));
    layers.remove(activeLayer);
    layerVisible.remove(activeLayer);
    activeLayer--;
    rebuildWith(below);
  }

  /** Paints {@code top} over {@code bottom} (source-over alpha blending). */
  private static void composite(WritableImage bottom, Image top) {
    PixelReader over = top.getPixelReader();
    PixelReader under = bottom.getPixelReader();
    PixelWriter writer = bottom.getPixelWriter();
    int w = (int) Math.min(bottom.getWidth(), top.getWidth());
    int h = (int) Math.min(bottom.getHeight(), top.getHeight());
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        Color a = over.getColor(x, y);
        if (a.getOpacity() == 0) {
          continue;
        }
        Color b = under.getColor(x, y);
        double alpha = a.getOpacity() + b.getOpacity() * (1 - a.getOpacity());
        if (alpha == 0) {
          continue;
        }
        writer.setColor(x, y, new Color(
            (a.getRed() * a.getOpacity() + b.getRed() * b.getOpacity() * (1 - a.getOpacity())) / alpha,
            (a.getGreen() * a.getOpacity() + b.getGreen() * b.getOpacity() * (1 - a.getOpacity())) / alpha,
            (a.getBlue() * a.getOpacity() + b.getBlue() * b.getOpacity() * (1 - a.getOpacity())) / alpha,
            alpha));
      }
    }
  }

  /** The visible layers flattened into one picture (what is saved). */
  WritableImage flattened() {
    WritableImage out = new WritableImage((int) image.getWidth(), (int) image.getHeight());
    for (int i = 0; i < layers.size(); i++) {
      if (layerVisible.get(i)) {
        composite(out, layers.get(i));
      }
    }
    return out;
  }

  int layerCount() {
    return layers.size();
  }

  // --- persistence ---------------------------------------------------------

  /** PNG save via a temp file and an atomic move: a failed encode never destroys the costume. */
  @Override
  public void save() {
    commitFloating();
    try {
      // layers are an editing aid: the costume is the visible layers flattened
      java.awt.image.BufferedImage buffered = SwingFXUtils.fromFXImage(
          layers.size() == 1 ? image : flattened(), null);
      Files.createDirectories(file.toAbsolutePath().getParent());
      Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".paint", ".png");
      try {
        if (!ImageIO.write(buffered, "png", temp.toFile())) {
          throw new IOException("no PNG writer");
        }
        LocalHistory.snapshot(project.root(), file);
        try {
          Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temp);
      }
      status.setText(I18n.t("status.saved"));
      dirty.set(false);
      onSaved.accept(file);
      saveHistory();
    } catch (IOException e) {
      alert(I18n.t("error.save", e.getMessage()));
    }
  }

  // --- layers and undo steps kept with the file --------------------------------

  private static final String HISTORY = "image";
  /** The undo steps as kept in the editor history, so a save encodes only new ones. */
  private final java.util.Map<Snapshot, byte[]> encoded = new java.util.IdentityHashMap<>();

  /**
   * The layers and the undo and redo steps kept when the picture was last
   * saved, if it is unchanged since (the file holds the layers flattened).
   */
  private void loadHistory() {
    if (project == null) return;
    var steps = EditorHistory.read(project.root(), file, HISTORY);
    try {
      List<Snapshot> undo = new ArrayList<>();
      List<Snapshot> redo = new ArrayList<>();
      for (byte[] step : steps.undo()) undo.add(snapshotOf(step));
      for (byte[] step : steps.redo()) redo.add(snapshotOf(step));
      Snapshot current = steps.current() == null ? null : snapshotOf(steps.current());
      if (current != null && (current.layers().get(0).getWidth() != image.getWidth()
          || current.layers().get(0).getHeight() != image.getHeight())) {
        return;
      }
      undoStack.addAll(undo);
      redoStack.addAll(redo);
      if (current != null) restore(current);
    } catch (IOException | RuntimeException e) {
      undoStack.clear(); // steps the editor can't read: none
      redoStack.clear();
    }
  }

  private void saveHistory() {
    if (project == null) return;
    try {
      java.util.Map<Snapshot, byte[]> known = new java.util.IdentityHashMap<>();
      List<byte[]> undo = new ArrayList<>();
      List<byte[]> redo = new ArrayList<>();
      for (Snapshot step : undoStack) undo.add(bytesOf(step, known));
      for (Snapshot step : redoStack) redo.add(bytesOf(step, known));
      encoded.clear();
      encoded.putAll(known);
      // one layer is the file itself; more are kept beside it
      byte[] current = layers.size() == 1 ? null : encode(snapshot());
      EditorHistory.write(project.root(), file, HISTORY,
          new EditorHistory.Steps(undo, redo, current));
    } catch (IOException | RuntimeException e) {
      status.setText(I18n.t("history.notkept", e.getMessage()));
    }
  }

  private byte[] bytesOf(Snapshot step, java.util.Map<Snapshot, byte[]> known)
      throws IOException {
    byte[] bytes = encoded.get(step);
    if (bytes == null) bytes = encode(step);
    known.put(step, bytes);
    return bytes;
  }

  /** The layers as PNGs, bottom first, each with whether it shows, and the active one. */
  private static byte[] encode(Snapshot step) throws IOException {
    return EditorHistory.encode(out -> {
      out.writeInt(step.active());
      out.writeInt(step.layers().size());
      for (int i = 0; i < step.layers().size(); i++) {
        out.writeBoolean(step.visible().get(i));
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(SwingFXUtils.fromFXImage(step.layers().get(i), null), "png", png)) {
          throw new IOException("no PNG writer");
        }
        out.writeInt(png.size());
        png.writeTo(out);
      }
    });
  }

  private Snapshot snapshotOf(byte[] bytes) throws IOException {
    Snapshot step = EditorHistory.decode(bytes, in -> {
      int active = in.readInt();
      int count = in.readInt();
      if (count < 1 || count > 1000) throw new IOException("not an image step");
      List<WritableImage> layerImages = new ArrayList<>();
      List<Boolean> visible = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        visible.add(in.readBoolean());
        byte[] png = in.readNBytes(in.readInt());
        Image layer = new Image(new java.io.ByteArrayInputStream(png));
        if (layer.isError()) throw new IOException("not an image step");
        layerImages.add(new WritableImage(layer.getPixelReader(),
            (int) layer.getWidth(), (int) layer.getHeight()));
      }
      return new Snapshot(layerImages, List.copyOf(visible), active);
    });
    encoded.put(step, bytes);
    return step;
  }

  private void saveHitbox() {
    if (tool == Tool.NINE_SLICE) {
      writeToolStatement(source -> SpriteRegionWriter.setNineSlice(source,
          nineSlice[0], nineSlice[1], nineSlice[2], nineSlice[3]));
    } else if (tool == Tool.CENTER && rotationCenter != null) {
      writeToolStatement(source -> SpriteRegionWriter.setRotationCenter(source,
          rotationCenter.getX(), rotationCenter.getY()));
    } else if (hitboxPoints.size() >= 3) {
      double[] points = new double[hitboxPoints.size() * 2];
      for (int i = 0; i < hitboxPoints.size(); i++) {
        points[2 * i] = hitboxPoints.get(i).getX();
        points[2 * i + 1] = hitboxPoints.get(i).getY();
      }
      writeToolStatement(source -> SpriteRegionWriter.setHitbox(source, points));
    } else if (tool == Tool.CENTER) {
      alert(I18n.t("imageeditor.center.click"));
    } else {
      alert(I18n.t("imageeditor.hitbox.click"));
    }
  }

  private interface StatementWriter {
    String apply(String source);
  }

  /** Every sprite can take the statement (Scratch for Java 7 nine-slices any sprite). */
  private void writeToolStatement(StatementWriter writer) {
    try {
      List<String> sprites = project.spriteClasses();
      if (sprites.isEmpty()) {
        alert(I18n.t("designer.no.sprites"));
        return;
      }
      ChoiceDialog<String> dialog = new ChoiceDialog<>(sprites.get(0), sprites);
      dialog.setTitle(I18n.t("imageeditor.choose.sprite"));
      dialog.setHeaderText(null);
      dialog.showAndWait().ifPresent(spriteClass -> {
        try {
          Path classFile = project.root().resolve(spriteClass + ".java");
          String source = Files.readString(classFile, StandardCharsets.UTF_8);
          String updated = writer.apply(source);
          LocalHistory.writeString(project.root(), classFile, updated);
          onSpriteWritten.accept(classFile);
          if (tool == Tool.HITBOX) hitboxPoints.clear();
          redrawOverlay();
          status.setText(I18n.t(tool == Tool.CENTER ? "imageeditor.center.saved"
              : tool == Tool.NINE_SLICE ? "imageeditor.nineslice.saved"
              : "imageeditor.hitbox.saved", spriteClass));
        } catch (Exception e) {
          alert(e.getMessage());
        }
      });
    } catch (IOException e) {
      alert(e.getMessage());
    }
  }

  private void alert(String message) {
    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
        javafx.scene.control.Alert.AlertType.INFORMATION, message, I18n.ok());
    alert.setHeaderText(null);
    alert.show();
  }

  private static final class ChoiceDialog<T> extends javafx.scene.control.Dialog<T> {
    ChoiceDialog(T defaultChoice, java.util.Collection<T> choices) {
      getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
      javafx.scene.control.ComboBox<T> box = new javafx.scene.control.ComboBox<>();
      box.getItems().addAll(choices);
      box.getSelectionModel().select(defaultChoice);
      getDialogPane().setContent(box);
      setResultConverter(bt -> bt == I18n.ok()
          ? box.getSelectionModel().getSelectedItem() : null);
    }
  }
}
