package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.RegionStatements;
import org.openpatch.scratch4j.core.region.SpriteRef;
import org.openpatch.scratch4j.core.region.StageDocument;
import org.openpatch.scratch4j.core.region.StageModel;
import org.openpatch.scratch.RotationStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * The stage designer: the stage in Scratch coordinates (origin centre, y up)
 * with its backdrop and sprites; drag to move, the round handle turns
 * (direction), the corner handle scales (size %); arrows nudge, Delete
 * removes, Ctrl+D duplicates, Ctrl+Z undoes. The side panel shows the
 * selected sprite's numbers, the sprites on the stage, the sprite classes to
 * add and the stage's size and backdrops. Every change is written through
 * the region round-trip into the stage class - the code stays the truth.
 */
final class StageDesignerView extends BorderPane {

  /** Host notifications after a region write. */
  interface Listener {
    void onStageWritten(Path stageFile);
  }

  private enum Drag { NONE, MOVE, ROTATE, RESIZE }

  private static final double HANDLE_RADIUS = 7;
  private static final double ROTATE_HANDLE_GAP = 26;

  private final ScratchProject project;
  private final String stageClass;
  private final Path stageFile;
  private final Listener listener;
  private final StageRenderer renderer;
  private Runnable onOpenCode = () -> { };
  private Runnable onNewSpriteClass = () -> { };
  private java.util.function.Consumer<String> onEditSpriteAssets = name -> { };

  private final Canvas canvas = new Canvas(480, 360);
  private final ScrollPane stageScroll;
  private final Label readout = new Label("x: 0   y: 0");
  private final Label zoomLabel = new Label();
  private final ToggleButton gridToggle = new ToggleButton(null, Icons.of("fth-grid"));
  private final ToggleButton snapToggle = new ToggleButton(null, Icons.of("fth-crosshair"));
  private final Button undoButton;
  private final Button redoButton;
  private final VBox readOnlyBanner = new VBox(4);
  private final Label readOnlyDetail = new Label();

  private final Label selectedName = new Label();
  private final Label selectedType = new Label();
  private final TextField xField = numberField();
  private final TextField yField = numberField();
  private final TextField directionField = numberField();
  private final TextField sizeField = numberField();
  private final ComboBox<RotationStyle> rotationStyleBox = new ComboBox<>();
  private final ComboBox<String> costumeBox = new ComboBox<>();
  private boolean inspectorUpdating;
  private final ToggleButton visibleToggle = new ToggleButton();
  private final ToggleButton hitboxToggle = new ToggleButton(null, Icons.of("fth-target"));
  /** Shows the stage's Tiled map behind the sprites (only when the stage loads one). */
  private ToggleButton mapToggle;
  private final javafx.scene.control.ComboBox<Path> mapChoice = new javafx.scene.control.ComboBox<>();
  private final VBox spriteCard = new VBox(8);
  private final FlowPane instanceTiles = new FlowPane(6, 6);
  private final FlowPane classTiles = new FlowPane(6, 6);
  private final FlowPane backdropTiles = new FlowPane(6, 6);
  private final FlowPane soundTiles = new FlowPane(6, 6);
  private final TextField widthField = numberField();
  private final TextField heightField = numberField();
  // UISprite pixel size and Text properties (shown only for those objects)
  private final TextField pxWidthField = numberField();
  private final TextField pxHeightField = numberField();
  private final GridPane uiSizeRow = new GridPane();
  private final GridPane turnRow = new GridPane();
  private final VBox spriteOnly = new VBox(8);
  private final TextField textWordsField = new TextField();
  private final TextField textWrapField = numberField();
  private final ComboBox<String> textStyleBox = new ComboBox<>();
  private final VBox textOnly = new VBox(8);

  private StageDocument document;
  private StageModel model;
  private String readOnlyReason = "";
  private StageDocument.RegionIssue regionIssue;
  private HBox regionFixes;
  private java.util.function.IntConsumer onRegionLine = line -> { };
  private java.util.function.IntConsumer onMoveOutOfRegion = line -> { };

  void setOnRegionLine(java.util.function.IntConsumer action) {
    this.onRegionLine = action;
  }

  void setOnMoveOutOfRegion(java.util.function.IntConsumer action) {
    this.onMoveOutOfRegion = action;
  }

  /** The line that makes the designer read-only (tests). */
  StageDocument.RegionIssue regionIssue() {
    return regionIssue;
  }
  private SpriteRef selected;
  private Drag drag = Drag.NONE;
  private double dragOffsetX;
  private double dragOffsetY;
  private double dragStartSize;
  private double dragStartWidth;
  private double dragStartHeight;
  private double dragStartDistance;
  private boolean dragChanged;
  private Double zoom; // null = fit to the available space
  private final Deque<String> undo = new ArrayDeque<>();
  private final Deque<String> redo = new ArrayDeque<>();

  StageDesignerView(ScratchProject project, String stageClass, Listener listener)
      throws IOException {
    this.project = project;
    this.stageClass = stageClass;
    this.stageFile = project.root().resolve(stageClass + ".java");
    this.listener = listener;
    this.renderer = new StageRenderer(project);
    getStyleClass().add("designer");

    readFromDisk();

    canvas.setFocusTraversable(true);
    canvas.setOnMouseMoved(this::hover);
    canvas.setOnMousePressed(this::press);
    canvas.setOnMouseDragged(this::dragTo);
    canvas.setOnMouseReleased(this::release);
    // a sprite made in code: its line in the code
    canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getClickCount() == 1 && hit(e.getX(), e.getY()) == null) {
        var ghost = renderer.ghostAt(e.getX(), e.getY(), canvas.getWidth(), canvas.getHeight(),
            scale());
        if (ghost != null) {
          renderer.highlightGhostLine(ghost.line());
          redraw();
          onGhost.accept(ghost.line());
        }
      }
    });
    // a double click on a sprite (also one made in code) opens its class
    canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getClickCount() != 2 || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
        return;
      }
      String type = classAt(e.getX(), e.getY());
      if (type != null) onOpenClass.accept(type);
    });
    // a double click on a map object (spawn point, wall, item...) opens it in the map editor
    canvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getClickCount() == 2 && hit(e.getX(), e.getY()) == null
          && renderer.ghostAt(e.getX(), e.getY(), canvas.getWidth(), canvas.getHeight(),
              scale()) == null) {
        var object = mapObjectAt(e.getX(), e.getY());
        if (object != null && renderer.chosenMap() != null) {
          onMapObject.accept(renderer.chosenMap(), object.id);
        }
      }
    });
    canvas.setOnMouseExited(e -> readout.setText(""));
    canvas.addEventHandler(KeyEvent.KEY_PRESSED, this::key);
    canvas.setOnContextMenuRequested(e -> {
      ContextMenu menu = contextMenuAt(e.getX(), e.getY());
      if (menu != null) menu.show(canvas, e.getScreenX(), e.getScreenY());
    });

    StackPane canvasHolder = new StackPane(canvas);
    canvasHolder.getStyleClass().add("stage-holder");
    canvasHolder.setPadding(new Insets(16));
    stageScroll = new ScrollPane(canvasHolder);
    stageScroll.setFitToWidth(true);
    stageScroll.setFitToHeight(true);
    stageScroll.getStyleClass().add("stage-scroll");
    stageScroll.setFocusTraversable(false);
    stageScroll.viewportBoundsProperty().addListener((o, old, b) -> {
      if (zoom == null) {
        redraw();
      }
    });

    readOnlyBanner.getStyleClass().add("readonly-banner");
    Label readOnlyTitle = new Label(I18n.t("designer.readonly"), Icons.of("fth-lock"));
    readOnlyTitle.getStyleClass().add("readonly-title");
    readOnlyTitle.setWrapText(true);
    readOnlyDetail.setWrapText(true);
    Button openCode = new Button(I18n.t("designer.opencode"), Icons.of("fth-code"));
    openCode.setOnAction(e -> onOpenCode.run());
    // the line that is in the way, and the fix: below the region it stays the student's code
    Button showLine = new Button(I18n.t("designer.readonly.show"), Icons.of("fth-corner-down-right"));
    showLine.setOnAction(e -> {
      if (regionIssue != null) onRegionLine.accept(regionIssue.line());
    });
    Button moveOut = new Button(I18n.t("designer.readonly.fix"), Icons.of("fth-tool"));
    moveOut.getStyleClass().add("accent");
    moveOut.setOnAction(e -> {
      if (regionIssue != null) onMoveOutOfRegion.accept(regionIssue.line());
    });
    regionFixes = new HBox(8, showLine, moveOut);
    readOnlyBanner.getChildren().addAll(readOnlyTitle, readOnlyDetail, regionFixes, openCode);
    readOnlyBanner.setMaxHeight(Region.USE_PREF_SIZE);
    readOnlyBanner.setMaxWidth(560);
    ghostNote.getStyleClass().add("ghost-note");
    ghostNote.setVisible(false);
    ghostNote.setMouseTransparent(true);
    StackPane center = new StackPane(stageScroll, readOnlyBanner, ghostNote);
    StackPane.setAlignment(ghostNote, Pos.BOTTOM_LEFT);
    StackPane.setMargin(ghostNote, new Insets(0, 0, 10, 10));
    StackPane.setAlignment(readOnlyBanner, Pos.TOP_CENTER);
    StackPane.setMargin(readOnlyBanner, new Insets(12));

    undoButton = Icons.button("fth-corner-up-left", I18n.t("designer.undo"), this::undo);
    redoButton = Icons.button("fth-corner-up-right", I18n.t("designer.redo"), this::redo);
    setTop(toolbar());
    setCenter(center);
    inspector = sidebar();
    setRight(inspector);
    stageCenter = center;
    // narrow (beside the code): the inspector leaves the side and comes back on demand
    setMinWidth(0);
    widthProperty().addListener((o, old, w) -> layoutInspector(w.doubleValue()));

    refreshAll();
  }

  Path file() {
    return stageFile;
  }

  private Node inspector;
  private StackPane stageCenter;
  private boolean narrow;
  private final ToggleButton inspectorToggle = new ToggleButton(null, Icons.of("fth-sliders"));

  /** Wide: the inspector on the right. Narrow: hidden, the toolbar toggle overlays it. */
  private void layoutInspector(double width) {
    boolean nowNarrow = width > 0 && width < 700;
    if (nowNarrow == narrow) return;
    narrow = nowNarrow;
    inspectorToggle.setVisible(narrow);
    inspectorToggle.setManaged(narrow);
    if (narrow) {
      setRight(null);
      inspectorToggle.setSelected(false);
      showInspectorOverlay(false);
    } else {
      stageCenter.getChildren().remove(inspector);
      setRight(inspector);
    }
  }

  private void showInspectorOverlay(boolean show) {
    stageCenter.getChildren().remove(inspector);
    if (show) {
      stageCenter.getChildren().add(inspector);
      StackPane.setAlignment(inspector, Pos.TOP_RIGHT);
      if (inspector instanceof Region region) {
        region.setMaxWidth(320);
        region.getStyleClass().add("inspector-overlay");
      }
    }
  }

  /** The map the designer shows (null: none). */
  Path shownMap() {
    return renderer.hasMap() ? renderer.chosenMap() : null;
  }

  private ToggleButton inspectorToggle() {
    inspectorToggle.setTooltip(new Tooltip(I18n.t("designer.inspector")));
    inspectorToggle.getStyleClass().addAll("button-icon", "flat");
    inspectorToggle.setVisible(false);
    inspectorToggle.setManaged(false);
    inspectorToggle.setOnAction(e -> showInspectorOverlay(inspectorToggle.isSelected()));
    return inspectorToggle;
  }

  private ToggleButton mapToggle() {
    mapToggle = new ToggleButton(null, Icons.of("fth-map"));
    mapToggle.setSelected(true);
    mapToggle.setTooltip(new Tooltip(I18n.t("designer.map")));
    mapToggle.setOnAction(e -> {
      renderer.showMap(mapToggle.isSelected());
      redraw();
    });
    mapToggle.setVisible(renderer.hasMap());
    mapToggle.setManaged(renderer.hasMap());
    objectsToggle.setSelected(true);
    objectsToggle.setTooltip(new Tooltip(I18n.t("designer.mapobjects")));
    objectsToggle.getStyleClass().addAll("button-icon", "flat");
    objectsToggle.setOnAction(e -> {
      renderer.showMapObjects(objectsToggle.isSelected());
      redraw();
    });
    // a computed map path ("./" + level + ".tmx"): choose which map to preview
    mapChoice.setTooltip(new Tooltip(I18n.t("designer.map.choose")));
    mapChoice.setConverter(new javafx.util.StringConverter<>() {
      @Override public String toString(Path p) {
        return p == null ? "" : p.getFileName().toString();
      }
      @Override public Path fromString(String s) {
        return null;
      }
    });
    mapChoice.setOnAction(e -> {
      if (mapChoice.getValue() != null && !mapChoice.getValue().equals(renderer.chosenMap())) {
        renderer.chooseMap(mapChoice.getValue());
        renderer.defaultCamera(model.width(), model.height());
        redraw();
      }
    });
    updateMapChoice();
    return mapToggle;
  }

  private final ToggleButton objectsToggle = new ToggleButton(null, Icons.of("fth-map-pin"));
  private java.util.function.IntConsumer onGhost = line -> { };
  private java.util.function.Consumer<String> onOpenClass = type -> { };

  /** Opens a sprite's class (double click, "Open class" in the menu). */
  void setOnOpenClass(java.util.function.Consumer<String> action) {
    this.onOpenClass = action;
  }

  /** Whether the caret's line outlines sprites made in code (tests). */
  int highlightedGhostLine() {
    return renderer.highlightedGhostLine();
  }

  /** The class of the sprite at a canvas point (a text object has none of its own). */
  String classAt(double x, double y) {
    SpriteRef hit = hit(x, y);
    if (hit != null) return hit.isText() ? null : hit.type();
    var ghost = renderer.ghostAt(x, y, canvas.getWidth(), canvas.getHeight(), scale());
    return ghost == null ? null : ghost.type();
  }

  /**
   * The right-click menu at a canvas point: open the sprite's class, its
   * costumes and sounds (a sprite made in code: its line), then the
   * designer's own actions (null: nothing there).
   */
  ContextMenu contextMenuAt(double x, double y) {
    SpriteRef hit = hit(x, y);
    var ghost = hit == null
        ? renderer.ghostAt(x, y, canvas.getWidth(), canvas.getHeight(), scale()) : null;
    List<javafx.scene.control.MenuItem> items = new java.util.ArrayList<>();
    String type = hit != null ? (hit.isText() ? null : hit.type())
        : ghost != null ? ghost.type() : null;
    if (type != null) {
      MenuItem open = new MenuItem(I18n.t("designer.openclass", type), Icons.of("fth-code"));
      open.setOnAction(e -> onOpenClass.accept(type));
      MenuItem assets = new MenuItem(I18n.t("designer.openassets", type),
          Icons.of("fth-image"));
      assets.setOnAction(e -> onEditSpriteAssets.accept(type));
      items.add(open);
      items.add(assets);
    }
    if (ghost != null) {
      MenuItem line = new MenuItem(I18n.t("designer.ghost.line", ghost.line()),
          Icons.of("fth-corner-down-right"));
      line.setOnAction(e -> onGhost.accept(ghost.line()));
      items.add(line);
    }
    if (hit != null && hit == selected && !isReadOnly()) {
      if (!items.isEmpty()) items.add(new javafx.scene.control.SeparatorMenuItem());
      items.addAll(spriteMenu().getItems());
    }
    return items.isEmpty() ? null : new ContextMenu(items.toArray(MenuItem[]::new));
  }
  private final Label ghostNote = new Label();

  /** A sprite made in code was clicked: show its line. */
  void setOnGhost(java.util.function.IntConsumer action) {
    this.onGhost = action;
  }

  /** The sprites the stage's code adds, as the designer shows them faded (tests). */
  List<org.openpatch.scratch4j.core.region.CodeSprites.Ghost> ghosts() {
    return renderer.ghosts();
  }

  private void updateGhostNote() {
    int n = renderer.ghosts().size();
    ghostNote.setText(I18n.t("designer.ghosts", n));
    ghostNote.setVisible(n > 0);
  }
  private java.util.function.BiConsumer<Path, Integer> onMapObject = (map, id) -> { };

  /** A map object was double-clicked: open the map at it. */
  void setOnMapObject(java.util.function.BiConsumer<Path, Integer> action) {
    this.onMapObject = action;
  }

  /** The map object under a canvas point (tests, double clicks). */
  org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject mapObjectAt(double x, double y) {
    return renderer.mapObjectAt(x, y, canvas.getWidth(), canvas.getHeight(), scale());
  }

  /** The map's objects as the designer shows them (tests). */
  List<org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject> mapObjects() {
    return renderer.mapObjects();
  }

  private void updateMapChoice() {
    objectsToggle.setVisible(renderer.hasMap() && !renderer.mapObjects().isEmpty());
    objectsToggle.setManaged(objectsToggle.isVisible());
    boolean several = renderer.mapChoices().size() > 1;
    mapChoice.getItems().setAll(renderer.mapChoices());
    mapChoice.setValue(renderer.chosenMap());
    mapChoice.setVisible(several);
    mapChoice.setManaged(several);
  }

  private Button codeModeButton() {
    Button button = Icons.labeled("fth-code", I18n.t("mode.code"), () -> onOpenCode.run());
    button.setTooltip(new javafx.scene.control.Tooltip(I18n.t("mode.code.tooltip")));
    return button;
  }

  void setOnOpenCode(Runnable onOpenCode) {
    this.onOpenCode = onOpenCode;
  }

  void setOnNewSpriteClass(Runnable onNewSpriteClass) {
    this.onNewSpriteClass = onNewSpriteClass;
  }

  void setOnEditSpriteAssets(java.util.function.Consumer<String> onEditSpriteAssets) {
    this.onEditSpriteAssets = onEditSpriteAssets;
  }

  String stageClass() {
    return stageClass;
  }

  Path stageFile() {
    return stageFile;
  }

  /** The designer's current model (for tests). */
  /** Field name of the selected object, or null. */
  String selectedName() {
    return selected == null ? null : selected.name();
  }

  /**
   * Selects the object a line of the stage's code is about ({@code player.setSize(80);},
   * {@code Player player;}) — the code-to-visual switch keeps what the caret was on.
   */
  boolean selectFromCodeLine(String line) {
    return selectFromCode(line, -1);
  }

  /**
   * Like {@link #selectFromCodeLine(String)}; a line that makes sprites in
   * code ({@code this.add(new Coin())}) outlines those (1-based {@code lineNumber}).
   */
  boolean selectFromCode(String line, int lineNumber) {
    boolean ghosts = renderer.ghosts().stream().anyMatch(g -> g.line() == lineNumber);
    if (renderer.highlightGhostLine(ghosts ? lineNumber : -1)) redraw();
    if (ghosts) return true;
    if (line == null) {
      return false;
    }
    for (SpriteRef ref : model.sprites()) {
      if (Pattern.compile("(?<![\\w.])" + Pattern.quote(ref.name()) + "(?![\\w(])")
          .matcher(line).find()) {
        selected = ref;
        refreshInstanceTiles();
        updateInspector();
        redraw();
        return true;
      }
    }
    return false;
  }

  /** The stage canvas (tests snapshot it). */
  Canvas canvas() {
    return canvas;
  }

  /** The object under a canvas point, topmost first (tests use it for hit checks). */
  SpriteRef objectAt(double canvasX, double canvasY) {
    return hit(canvasX, canvasY);
  }

  StageModel model() {
    return model;
  }

  /** The designer is read-only when the regions are outside its subset. */
  boolean isReadOnly() {
    return document == null;
  }

  /** Re-reads the stage file (after a code edit) and redraws; keeps the selection. */
  void reload() {
    String selectedName = selected == null ? null : selected.name();
    readFromDisk();
    selected = selectedName == null ? null : model.sprites().byName(selectedName);
    refreshAll();
  }

  private void readFromDisk() {
    try {
      String source = Files.readString(stageFile, StandardCharsets.UTF_8);
      renderer.scan(source);
      if (mapToggle != null) {
        mapToggle.setVisible(renderer.hasMap());
        mapToggle.setManaged(renderer.hasMap());
        updateMapChoice();
      }
      document = StageDocument.read(source);
      model = document.model();
      renderer.setGhosts(org.openpatch.scratch4j.core.region.CodeSprites.of(project.root(),
          stageClass));
      updateGhostNote();
      if (StageDocument.declaredSize(source).isEmpty()) {
        // a stage without super(w, h) is as big as the window that shows it
        renderer.windowSize().ifPresent(size -> model.size(size.width(), size.height()));
      }
      renderer.defaultCamera(model.width(), model.height());
      readOnlyReason = "";
      regionIssue = null;
    } catch (IOException | RuntimeException e) {
      document = null;
      model = StageModel.create();
      readOnlyReason = e.getMessage() == null ? "" : e.getMessage();
      try {
        regionIssue = StageDocument.regionIssue(Files.readString(stageFile,
            StandardCharsets.UTF_8));
      } catch (IOException ignored) {
        regionIssue = null;
      }
      if (regionIssue != null && regionIssue.line() > 0) {
        readOnlyReason = I18n.t("designer.readonly.line", regionIssue.line(),
            regionIssue.statement());
      }
    }
  }

  // --- layout ------------------------------------------------------------------

  private Node toolbar() {
    gridToggle.setSelected(false);
    gridToggle.setTooltip(new Tooltip(I18n.t("designer.grid")));
    gridToggle.getStyleClass().addAll("button-icon", "flat");
    gridToggle.setOnAction(e -> redraw());
    snapToggle.setSelected(false);
    snapToggle.setTooltip(new Tooltip(I18n.t("designer.snap")));
    hitboxToggle.setTooltip(new Tooltip(I18n.t("designer.hitboxes")));
    hitboxToggle.getStyleClass().addAll("button-icon", "flat");
    hitboxToggle.setOnAction(e -> redraw());
    snapToggle.getStyleClass().addAll("button-icon", "flat");
    zoomLabel.getStyleClass().add("zoom-label");
    readout.getStyleClass().add("xy-readout");
    // the readout gives way first when the window is narrow
    readout.setPrefWidth(130);
    readout.setMinWidth(0);
    mapChoice.setPrefWidth(120);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar = new HBox(4,
        undoButton, redoButton, new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-zoom-out", I18n.t("designer.zoomout"), () -> zoomBy(1 / 1.25)),
        zoomLabel,
        Icons.button("fth-zoom-in", I18n.t("designer.zoomin"), () -> zoomBy(1.25)),
        Icons.button("fth-maximize", I18n.t("designer.fit"), () -> {
          zoom = null;
          redraw();
        }),
        new Separator(javafx.geometry.Orientation.VERTICAL),
        gridToggle, snapToggle, hitboxToggle, mapToggle(), objectsToggle, mapChoice,
        inspectorToggle(),
        spacer, readout,
        codeModeButton());
    bar.setAlignment(Pos.CENTER_LEFT);
    // a narrow tab shrinks only the readout, then clips the toolbar instead of
    // pushing the designer out of view
    for (Node child : bar.getChildren()) {
      if (child instanceof Region region && child != readout && child != spacer) {
        region.setMinWidth(Region.USE_PREF_SIZE);
      }
    }
    bar.setMinWidth(0);
    javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
    clip.widthProperty().bind(bar.widthProperty());
    clip.heightProperty().bind(bar.heightProperty());
    bar.setClip(clip);
    bar.getStyleClass().add("tool-bar-row");
    return bar;
  }

  private Node sidebar() {
    // selected sprite
    selectedName.getStyleClass().add("card-title");
    selectedType.getStyleClass().add("text-muted");
    GridPane numbers = new GridPane();
    numbers.setHgap(8);
    numbers.setVgap(6);
    numbers.addRow(0, fieldLabel("x"), xField, fieldLabel("y"), yField);
    turnRow.setHgap(8);
    turnRow.addRow(0, fieldLabel(I18n.t("designer.direction")), directionField,
        fieldLabel(I18n.t("designer.size")), sizeField);
    uiSizeRow.setHgap(8);
    uiSizeRow.addRow(0, fieldLabel(I18n.t("designer.width")), pxWidthField,
        fieldLabel(I18n.t("designer.height")), pxHeightField);
    Tooltip.install(uiSizeRow, new Tooltip(I18n.t("designer.uisize.hint")));
    for (TextField f : List.of(xField, yField, directionField, sizeField, pxWidthField,
        pxHeightField, textWrapField, textWordsField)) {
      f.setOnAction(e -> applyInspector());
      f.focusedProperty().addListener((o, was, focused) -> {
        if (!focused) {
          applyInspector();
        }
      });
    }
    rotationStyleBox.getItems().setAll(RotationStyle.ALL_AROUND,
        RotationStyle.LEFT_RIGHT, RotationStyle.DONT);
    rotationStyleBox.setConverter(new javafx.util.StringConverter<>() {
      @Override public String toString(RotationStyle value) {
        return value == null ? "" : I18n.t("designer.rotation." + value.name());
      }
      @Override public RotationStyle fromString(String value) {
        return rotationStyleBox.getValue();
      }
    });
    rotationStyleBox.setMaxWidth(Double.MAX_VALUE);
    rotationStyleBox.setOnAction(e -> {
      if (!inspectorUpdating && selected != null && rotationStyleBox.getValue() != null) {
        selected.rotationStyle("RotationStyle." + rotationStyleBox.getValue().name());
        writeRegions();
      }
    });
    costumeBox.setEditable(true);
    costumeBox.setMaxWidth(Double.MAX_VALUE);
    costumeBox.setOnAction(e -> {
      if (!inspectorUpdating && selected != null && !costumeBox.getEditor().getText().isBlank()) {
        selected.currentCostume(costumeBox.getEditor().getText().trim());
        writeRegions();
      }
    });
    visibleToggle.setOnAction(e -> {
      if (selected != null && !isReadOnly()) {
        selected.visible(visibleToggle.isSelected());
        writeRegions();
      }
    });
    visibleToggle.getStyleClass().add("small");
    Button editAssets = new Button(I18n.t("spriteassets.open.short"), Icons.of("fth-image"));
    editAssets.setTooltip(new Tooltip(I18n.t("spriteassets.open")));
    editAssets.setMaxWidth(Double.MAX_VALUE);
    editAssets.setOnAction(e -> {
      if (selected != null) onEditSpriteAssets.accept(selected.type());
    });
    HBox actions = new HBox(4, visibleToggle,
        Icons.button("fth-edit-2", I18n.t("designer.rename"), this::renameSelected),
        Icons.button("fth-copy", I18n.t("designer.duplicate"), this::duplicateSelected),
        Icons.button("fth-arrow-up", I18n.t("designer.front"), () -> moveLayer(true)),
        Icons.button("fth-arrow-down", I18n.t("designer.back"), () -> moveLayer(false)),
        Icons.button("fth-trash-2", I18n.t("designer.delete"), this::deleteSelected));
    actions.setAlignment(Pos.CENTER_LEFT);
    textStyleBox.getItems().setAll("PLAIN", "BOX", "SPEAK", "THINK");
    textStyleBox.setConverter(new javafx.util.StringConverter<>() {
      @Override public String toString(String value) {
        return value == null ? "" : I18n.t("designer.text.style." + value);
      }
      @Override public String fromString(String value) {
        return textStyleBox.getValue();
      }
    });
    textStyleBox.setMaxWidth(Double.MAX_VALUE);
    textStyleBox.setOnAction(e -> {
      if (!inspectorUpdating && selected != null && selected.isText() && !isReadOnly()) {
        String value = textStyleBox.getValue();
        selected.textStyle("PLAIN".equals(value) && selected.textStyle() == null ? null
            : "TextStyle." + value);
        writeRegions();
      }
    });
    textWordsField.setPromptText(I18n.t("designer.text.words"));
    GridPane wrap = new GridPane();
    wrap.setHgap(8);
    wrap.addRow(0, fieldLabel(I18n.t("designer.text.wrap")), textWrapField);
    textOnly.getChildren().addAll(fieldLabel(I18n.t("designer.text.words")), textWordsField,
        wrap, fieldLabel(I18n.t("designer.text.style")), textStyleBox);
    spriteOnly.getChildren().addAll(turnRow, uiSizeRow,
        fieldLabel(I18n.t("designer.rotation.style")), rotationStyleBox,
        fieldLabel(I18n.t("designer.current.costume")), costumeBox, editAssets);
    spriteCard.getChildren().addAll(new VBox(0, selectedName, selectedType), numbers,
        spriteOnly, textOnly, actions);

    // stage settings
    GridPane size = new GridPane();
    size.setHgap(8);
    size.addRow(0, fieldLabel(I18n.t("designer.width")), widthField,
        fieldLabel(I18n.t("designer.height")), heightField);
    for (TextField f : List.of(widthField, heightField)) {
      f.setOnAction(e -> applyStageSize());
      f.focusedProperty().addListener((o, was, focused) -> {
        if (!focused) {
          applyStageSize();
        }
      });
    }

    VBox panel = new VBox(10,
        card(I18n.t("designer.selected"), "fth-mouse-pointer", spriteCard),
        card(I18n.t("designer.onstage"), "fth-layers", instanceTiles),
        card(I18n.t("designer.addsprite"), "fth-plus-circle", classTiles),
        card(I18n.t("designer.stage"), "fth-monitor", new VBox(8, size,
            fieldLabel(I18n.t("designer.backdrops")), backdropTiles,
            fieldLabel(I18n.t("designer.sounds")), soundTiles)));
    panel.setPadding(new Insets(10));
    panel.getStyleClass().add("designer-sidebar");
    ScrollPane scroll = new ScrollPane(panel);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setPrefWidth(310);
    scroll.setMinWidth(240);
    scroll.getStyleClass().add("designer-sidebar-scroll");
    return scroll;
  }

  private static Node card(String title, String icon, Node content) {
    Label label = new Label(title, Icons.of(icon));
    label.getStyleClass().add("card-header");
    VBox card = new VBox(8, label, content);
    card.getStyleClass().add("side-card");
    return card;
  }

  private static Label fieldLabel(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("field-label");
    return label;
  }

  private static TextField numberField() {
    TextField field = new TextField();
    field.setPrefColumnCount(5);
    field.getStyleClass().add("number-field");
    return field;
  }

  // --- refresh -----------------------------------------------------------------

  private void refreshAll() {
    readOnlyBanner.setVisible(isReadOnly());
    readOnlyBanner.setManaged(isReadOnly());
    readOnlyDetail.setText(readOnlyReason);
    boolean fixable = regionIssue != null && regionIssue.line() > 0;
    regionFixes.setVisible(fixable);
    regionFixes.setManaged(fixable);
    undoButton.setDisable(undoDelegate == null && undo.isEmpty());
    redoButton.setDisable(redoDelegate == null && redo.isEmpty());
    widthField.setText(String.valueOf(model.width()));
    heightField.setText(String.valueOf(model.height()));
    widthField.setDisable(isReadOnly());
    heightField.setDisable(isReadOnly());
    refreshInstanceTiles();
    refreshClassTiles();
    refreshBackdropTiles();
    refreshSoundTiles();
    updateInspector();
    redraw();
  }

  private void refreshInstanceTiles() {
    instanceTiles.getChildren().clear();
    for (SpriteRef ref : model.sprites()) {
      StageRenderer.Look look = renderer.look(ref);
      ToggleButton tile = spriteTile(look.image(), ref.name());
      tile.setSelected(ref == selected);
      tile.setOnAction(e -> {
        selected = ref;
        refreshInstanceTiles();
        updateInspector();
        redraw();
        canvas.requestFocus();
      });
      if (!ref.isVisible()) {
        tile.setOpacity(0.5);
      }
      instanceTiles.getChildren().add(tile);
    }
    if (model.sprites().isEmpty()) {
      Label empty = new Label(I18n.t("designer.nosprites.onstage"));
      empty.getStyleClass().add("text-muted");
      empty.setWrapText(true);
      instanceTiles.getChildren().add(empty);
    }
  }

  private void refreshClassTiles() {
    classTiles.getChildren().clear();
    List<String> classes;
    try {
      classes = project.spriteClasses();
    } catch (IOException e) {
      classes = List.of();
    }
    for (String className : classes) {
      ToggleButton tile = spriteTile(renderer.classCostume(className), className);
      tile.setTooltip(new Tooltip(I18n.t("designer.addinstance", className)));
      tile.setOnAction(e -> {
        tile.setSelected(false);
        addInstance(className);
      });
      tile.setDisable(isReadOnly());
      classTiles.getChildren().add(tile);
    }
    Button add = new Button(I18n.t("designer.newsprite"), Icons.of("fth-plus", 20));
    add.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
    add.getStyleClass().add("add-tile");
    add.setDisable(isReadOnly());
    ContextMenu menu = new ContextMenu();
    MenuItem fromCostume = new MenuItem(I18n.t("designer.newsprite.costume"),
        Icons.of("fth-image"));
    fromCostume.setOnAction(e -> addPlainSprite());
    MenuItem newClass = new MenuItem(I18n.t("designer.newsprite.class"), Icons.of("fth-code"));
    newClass.setOnAction(e -> onNewSpriteClass.run());
    MenuItem newText = new MenuItem(I18n.t("designer.newtext"), Icons.of("fth-type"));
    newText.setOnAction(e -> addText());
    menu.getItems().addAll(fromCostume, newClass, newText);
    add.setOnAction(e -> menu.show(add, javafx.geometry.Side.BOTTOM, 0, 0));
    classTiles.getChildren().add(add);
  }

  private void refreshBackdropTiles() {
    backdropTiles.getChildren().clear();
    for (StageModel.Backdrop backdrop : model.backdrops()) {
      String ref = backdrop.path() != null ? backdrop.path() : backdrop.name();
      ImageView view = new ImageView(CostumeView.costume(ref, project.root()));
      view.setFitWidth(72);
      view.setFitHeight(54);
      view.setPreserveRatio(true);
      Label label = new Label(backdrop.name(), view);
      label.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
      label.getStyleClass().add("backdrop-tile");
      label.setTooltip(new Tooltip(ref));
      if (!isReadOnly()) {
        ContextMenu menu = new ContextMenu();
        MenuItem first = new MenuItem(I18n.t("designer.backdrop.first"),
            Icons.of("fth-chevrons-left"));
        first.setOnAction(e -> {
          model.backdrops().remove(backdrop);
          model.backdrops().add(0, backdrop);
          writeRegions();
        });
        MenuItem earlier = new MenuItem(I18n.t("designer.backdrop.earlier"),
            Icons.of("fth-chevron-left"));
        earlier.setDisable(model.backdrops().indexOf(backdrop) <= 0);
        earlier.setOnAction(e -> {
          int index = model.backdrops().indexOf(backdrop);
          model.backdrops().remove(index);
          model.backdrops().add(index - 1, backdrop);
          writeRegions();
        });
        MenuItem later = new MenuItem(I18n.t("designer.backdrop.later"),
            Icons.of("fth-chevron-right"));
        later.setDisable(model.backdrops().indexOf(backdrop) >= model.backdrops().size() - 1);
        later.setOnAction(e -> {
          int index = model.backdrops().indexOf(backdrop);
          model.backdrops().remove(index);
          model.backdrops().add(index + 1, backdrop);
          writeRegions();
        });
        javafx.scene.control.CheckMenuItem stretch = new javafx.scene.control.CheckMenuItem(
            I18n.t("designer.backdrop.stretch"));
        stretch.setSelected(backdrop.isStretched());
        stretch.setOnAction(e -> {
          int index = model.backdrops().indexOf(backdrop);
          model.backdrops().set(index, new StageModel.Backdrop(backdrop.name(),
              backdrop.path(), stretch.isSelected()));
          writeRegions();
        });
        MenuItem remove = new MenuItem(I18n.t("designer.delete"), Icons.of("fth-trash-2"));
        remove.setOnAction(e -> {
          model.backdrops().remove(backdrop);
          writeRegions();
        });
        menu.getItems().addAll(first, earlier, later, stretch, remove);
        label.setContextMenu(menu);
      }
      backdropTiles.getChildren().add(label);
    }
    Button add = new Button(null, Icons.of("fth-plus", 20));
    add.getStyleClass().add("add-tile");
    add.setTooltip(new Tooltip(I18n.t("designer.backdrop.add")));
    add.setDisable(isReadOnly());
    add.setOnAction(e -> addBackdrop());
    backdropTiles.getChildren().add(add);
  }

  private void refreshSoundTiles() {
    soundTiles.getChildren().clear();
    for (StageModel.Sound sound : model.sounds()) {
      Label tile = new Label(sound.name(), Icons.of("fth-music"));
      tile.getStyleClass().add("backdrop-tile");
      tile.setTooltip(new Tooltip(sound.path() == null ? sound.name() : sound.path()));
      if (!isReadOnly()) {
        MenuItem remove = new MenuItem(I18n.t("designer.delete"), Icons.of("fth-trash-2"));
        remove.setOnAction(e -> {
          model.sounds().remove(sound);
          writeRegions();
        });
        tile.setContextMenu(new ContextMenu(remove));
      }
      soundTiles.getChildren().add(tile);
    }
    Button add = new Button(null, Icons.of("fth-plus", 20));
    add.getStyleClass().add("add-tile");
    add.setTooltip(new Tooltip(I18n.t("designer.sound.add")));
    add.setDisable(isReadOnly());
    add.setOnAction(e -> addSound());
    soundTiles.getChildren().add(add);
  }

  private static ToggleButton spriteTile(Image image, String name) {
    ImageView view = new ImageView(image);
    view.setFitWidth(52);
    view.setFitHeight(52);
    view.setPreserveRatio(true);
    StackPane frame = new StackPane(view);
    frame.setPrefSize(56, 56);
    Label label = new Label(name);
    label.getStyleClass().add("tile-label");
    label.setMaxWidth(70);
    VBox box = new VBox(2, frame, label);
    box.setAlignment(Pos.CENTER);
    ToggleButton tile = new ToggleButton(null, box);
    tile.getStyleClass().add("sprite-tile");
    return tile;
  }

  private java.util.function.Consumer<String> onSelectionChanged = name -> { };
  private String notifiedSelection;

  /** Told the selected object's name (null: none) whenever the selection changes. */
  void setOnSelectionChanged(java.util.function.Consumer<String> listener) {
    this.onSelectionChanged = listener;
  }

  private void notifySelection() {
    String name = selected == null ? null : selected.name();
    if (!java.util.Objects.equals(name, notifiedSelection)) {
      notifiedSelection = name;
      onSelectionChanged.accept(name);
    }
  }

  private void updateInspector() {
    notifySelection();
    boolean has = selected != null;
    spriteCard.setDisable(!has || isReadOnly());
    if (!has) {
      inspectorUpdating = true;
      selectedName.setText(I18n.t("designer.noselection"));
      selectedType.setText("");
      for (TextField f : List.of(xField, yField, directionField, sizeField)) {
        f.clear();
      }
      visibleToggle.setText(I18n.t("designer.visible"));
      visibleToggle.setGraphic(Icons.of("fth-eye"));
      rotationStyleBox.setValue(null);
      costumeBox.getItems().clear();
      costumeBox.setValue(null);
      inspectorUpdating = false;
      return;
    }
    inspectorUpdating = true;
    StageRenderer.Look look = renderer.look(selected);
    selectedName.setText(selected.name());
    selectedType.setText(selected.type());
    xField.setText(RegionStatements.number(selected.hasPosition() ? selected.x() : 0));
    yField.setText(RegionStatements.number(selected.hasPosition() ? selected.y() : 0));
    directionField.setText(RegionStatements.number(look.direction()));
    sizeField.setText(RegionStatements.number(look.size()));
    visibleToggle.setSelected(selected.isVisible());
    visibleToggle.setText(selected.isVisible() ? I18n.t("designer.visible")
        : I18n.t("designer.hidden"));
    visibleToggle.setGraphic(Icons.of(selected.isVisible() ? "fth-eye" : "fth-eye-off"));
    boolean text = selected.isText();
    boolean ui = !text && renderer.isUiSprite(selected.type());
    show(spriteOnly, !text);
    show(textOnly, text);
    show(uiSizeRow, ui);
    if (ui) {
      pxWidthField.setText(RegionStatements.number(Math.round(look.width() * 100) / 100.0));
      pxHeightField.setText(RegionStatements.number(Math.round(look.height() * 100) / 100.0));
    }
    if (text) {
      textWordsField.setText(StageRenderer.unescape(
          selected.text() == null ? "" : selected.text()).replace("\n", "\\n"));
      textWrapField.setText(RegionStatements.number(selected.textWidth()));
      textStyleBox.setValue(look.textStyle());
    }
    rotationStyleBox.setValue(look.rotationStyle());
    costumeBox.getItems().setAll(renderer.costumeNames(selected.type()));
    if (selected.costume() != null && !costumeBox.getItems().contains(selected.costume())) {
      costumeBox.getItems().add(selected.costume());
    }
    costumeBox.setValue(selected.currentCostume() == null ? "" : selected.currentCostume());
    inspectorUpdating = false;
  }

  private static void show(Node node, boolean visible) {
    node.setVisible(visible);
    node.setManaged(visible);
  }

  // --- drawing -------------------------------------------------------------------

  private double scale() {
    if (zoom != null) {
      return zoom;
    }
    var viewport = stageScroll == null ? null : stageScroll.getViewportBounds();
    if (viewport == null || viewport.getWidth() < 50) {
      return 1;
    }
    double s = Math.min((viewport.getWidth() - 34) / model.width(),
        (viewport.getHeight() - 34) / model.height());
    return Math.max(0.25, Math.min(4, s));
  }

  private void zoomBy(double factor) {
    zoom = Math.max(0.25, Math.min(6, scale() * factor));
    redraw();
  }

  private void redraw() {
    double scale = scale();
    double w = model.width() * scale;
    double h = model.height() * scale;
    canvas.setWidth(w);
    canvas.setHeight(h);
    zoomLabel.setText(Math.round(scale * 100) + "%");
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, w, h);
    renderer.draw(g, model, scale, gridToggle.isSelected());
    if (hitboxToggle.isSelected()) {
      renderer.drawHitboxes(g, model, scale);
    }
    if (selected != null && model.sprites().contains(selected)) {
      drawSelection(g, scale);
    }
  }

  private void drawSelection(GraphicsContext g, double scale) {
    StageRenderer.Look look = renderer.look(selected);
    double dw = look.width() * scale;
    double dh = look.height() * scale;
    double[] c = centre(selected, scale);
    g.save();
    g.translate(c[0], c[1]);
    g.rotate(look.rotationAngle());
    Color accent = Color.web("#855cd6");
    g.setStroke(accent);
    g.setLineWidth(2);
    g.setLineDashes(6, 4);
    g.strokeRect(-dw / 2 - 3, -dh / 2 - 3, dw + 6, dh + 6);
    g.setLineDashes();
    if (!isReadOnly() && !look.isText()) {
      // direction handle: points where the sprite looks
      double hx = dw / 2 + ROTATE_HANDLE_GAP;
      g.strokeLine(dw / 2 + 3, 0, hx - HANDLE_RADIUS, 0);
      g.setFill(Color.WHITE);
      g.fillOval(hx - HANDLE_RADIUS, -HANDLE_RADIUS, HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
      g.strokeOval(hx - HANDLE_RADIUS, -HANDLE_RADIUS, HANDLE_RADIUS * 2, HANDLE_RADIUS * 2);
    }
    if (!isReadOnly()) {
      // size handle (a text's handle sets where it wraps): bottom-right corner
      g.setFill(accent);
      g.fillRect(dw / 2 + 3 - 5, dh / 2 + 3 - 5, 10, 10);
    }
    g.restore();
  }

  /** Canvas centre of the drawn box (a text's box is not centred on its position). */
  private double[] centre(SpriteRef ref, double scale) {
    double x = ref.hasPosition() ? ref.x() : 0;
    double y = ref.hasPosition() ? ref.y() : 0;
    StageRenderer.Look look = renderer.look(ref);
    return new double[] {canvas.getWidth() / 2 + (x - renderer.cameraX(ref) + look.offsetX())
        * scale, canvas.getHeight() / 2 + (-(y - renderer.cameraY(ref)) + look.offsetY()) * scale};
  }

  /** Canvas point in the sprite's unrotated frame (relative to its centre). */
  private double[] local(SpriteRef ref, double cx, double cy, double scale) {
    double[] c = centre(ref, scale);
    double angle = Math.toRadians(-renderer.look(ref).rotationAngle());
    double dx = cx - c[0];
    double dy = cy - c[1];
    return new double[] {dx * Math.cos(angle) - dy * Math.sin(angle),
        dx * Math.sin(angle) + dy * Math.cos(angle)};
  }

  // --- interaction ---------------------------------------------------------------

  private double[] toScratch(double canvasX, double canvasY) {
    double scale = scale();
    return new double[] {(canvasX - canvas.getWidth() / 2) / scale + renderer.cameraX(),
        (canvas.getHeight() / 2 - canvasY) / scale + renderer.cameraY()};
  }

  /** A canvas point in the coordinates of {@code ref} (screen coordinates for UI sprites). */
  private double[] toScratch(double canvasX, double canvasY, SpriteRef ref) {
    double scale = scale();
    return new double[] {(canvasX - canvas.getWidth() / 2) / scale + renderer.cameraX(ref),
        (canvas.getHeight() / 2 - canvasY) / scale + renderer.cameraY(ref)};
  }

  private void hover(MouseEvent e) {
    double[] xy = toScratch(e.getX(), e.getY());
    readout.setText("x: " + Math.round(xy[0]) + "   y: " + Math.round(xy[1]));
    Drag over = handleAt(e.getX(), e.getY());
    canvas.setCursor(over == Drag.ROTATE ? javafx.scene.Cursor.CROSSHAIR
        : over == Drag.RESIZE ? javafx.scene.Cursor.SE_RESIZE
        : hit(e.getX(), e.getY()) != null ? javafx.scene.Cursor.OPEN_HAND
        : javafx.scene.Cursor.DEFAULT);
  }

  private Drag handleAt(double cx, double cy) {
    if (selected == null || isReadOnly()) {
      return Drag.NONE;
    }
    double scale = scale();
    StageRenderer.Look look = renderer.look(selected);
    double dw = look.width() * scale;
    double dh = look.height() * scale;
    double[] p = local(selected, cx, cy, scale);
    if (!look.isText()
        && Math.hypot(p[0] - (dw / 2 + ROTATE_HANDLE_GAP), p[1]) <= HANDLE_RADIUS + 3) {
      return Drag.ROTATE;
    }
    if (Math.abs(p[0] - (dw / 2 + 3)) <= 8 && Math.abs(p[1] - (dh / 2 + 3)) <= 8) {
      return Drag.RESIZE;
    }
    return Drag.NONE;
  }

  private SpriteRef hit(double cx, double cy) {
    double scale = scale();
    for (int i = model.sprites().size() - 1; i >= 0; i--) {
      SpriteRef ref = model.sprites().get(i);
      StageRenderer.Look look = renderer.look(ref);
      double[] p = local(ref, cx, cy, scale);
      if (Math.abs(p[0]) <= look.width() * scale / 2 + 2
          && Math.abs(p[1]) <= look.height() * scale / 2 + 2) {
        return ref;
      }
    }
    return null;
  }

  private void press(MouseEvent e) {
    // consumed: the enclosing ScrollPane would otherwise take the focus (and
    // with it the arrow/Delete/Ctrl+Z keys) on every click
    e.consume();
    canvas.requestFocus();
    dragChanged = false;
    drag = handleAt(e.getX(), e.getY());
    if (drag == Drag.NONE) {
      SpriteRef hit = hit(e.getX(), e.getY());
      if (hit != selected) {
        selected = hit;
        refreshInstanceTiles();
      }
      if (selected != null) {
        drag = Drag.MOVE;
        double[] xy = toScratch(e.getX(), e.getY(), selected);
        dragOffsetX = (selected.hasPosition() ? selected.x() : 0) - xy[0];
        dragOffsetY = (selected.hasPosition() ? selected.y() : 0) - xy[1];
        canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
      }
    } else if (drag == Drag.RESIZE) {
      double[] c = centre(selected, scale());
      StageRenderer.Look look = renderer.look(selected);
      dragStartSize = look.size();
      dragStartWidth = look.isText() && selected.textWidth() > 0 ? selected.textWidth()
          : look.width();
      dragStartHeight = look.height();
      dragStartDistance = Math.max(1, Math.hypot(e.getX() - c[0], e.getY() - c[1]));
    }
    updateInspector();
    redraw();
  }

  private void dragTo(MouseEvent e) {
    if (selected == null || isReadOnly() || drag == Drag.NONE) {
      return;
    }
    double[] xy = toScratch(e.getX(), e.getY(), selected);
    switch (drag) {
      case MOVE -> {
        double x = xy[0] + dragOffsetX;
        double y = xy[1] + dragOffsetY;
        if (snapToggle.isSelected() != e.isAltDown()) {
          x = Math.round(x / 10) * 10;
          y = Math.round(y / 10) * 10;
        }
        selected.setPosition(Math.round(x), Math.round(y));
      }
      case ROTATE -> {
        double sx = selected.hasPosition() ? selected.x() : 0;
        double sy = selected.hasPosition() ? selected.y() : 0;
        double direction = 90 - Math.toDegrees(Math.atan2(xy[1] - sy, xy[0] - sx));
        if (e.isShiftDown()) {
          direction = Math.round(direction / 15) * 15;
        }
        selected.direction(normalizeDirection(Math.round(direction)));
      }
      case RESIZE -> {
        double[] c = centre(selected, scale());
        double distance = Math.hypot(e.getX() - c[0], e.getY() - c[1]);
        double factor = distance / dragStartDistance;
        if (selected.isText()) {
          selected.textWidth(Math.max(20, Math.round(dragStartWidth * factor)));
        } else if (renderer.isUiSprite(selected.type())) {
          // a UISprite grows in pixels (nine-slice keeps its corners)
          selected.width(Math.max(4, Math.round(dragStartWidth * factor)));
          selected.height(Math.max(4, Math.round(dragStartHeight * factor)));
        } else {
          double size = Math.round(dragStartSize * factor);
          selected.size(Math.max(5, Math.min(1000, size)));
        }
      }
      default -> { }
    }
    dragChanged = true;
    updateInspector();
    redraw();
  }

  static double normalizeDirection(double direction) {
    double d = ((direction + 180) % 360 + 360) % 360 - 180;
    return d == -180 ? 180 : d;
  }

  private void release(MouseEvent e) {
    if (dragChanged && selected != null && !isReadOnly()) {
      writeRegions();
    }
    drag = Drag.NONE;
    dragChanged = false;
    hover(e);
  }

  private void key(KeyEvent e) {
    if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
      if (e.isShiftDown()) {
        redo();
      } else {
        undo();
      }
      e.consume();
      return;
    }
    if (e.isShortcutDown() && e.getCode() == KeyCode.Y) {
      redo();
      e.consume();
      return;
    }
    if (selected == null || isReadOnly()) {
      return;
    }
    double step = e.isShiftDown() ? 10 : 1;
    double x = selected.hasPosition() ? selected.x() : 0;
    double y = selected.hasPosition() ? selected.y() : 0;
    switch (e.getCode()) {
      case LEFT -> selected.setPosition(x - step, y);
      case RIGHT -> selected.setPosition(x + step, y);
      case UP -> selected.setPosition(x, y + step);
      case DOWN -> selected.setPosition(x, y - step);
      case DELETE, BACK_SPACE -> {
        deleteSelected();
        e.consume();
        return;
      }
      case D -> {
        if (e.isShortcutDown()) {
          duplicateSelected();
          e.consume();
        }
        return;
      }
      default -> {
        return;
      }
    }
    e.consume();
    writeRegions();
  }

  private ContextMenu spriteMenu() {
    MenuItem duplicate = new MenuItem(I18n.t("designer.duplicate"), Icons.of("fth-copy"));
    duplicate.setOnAction(e -> duplicateSelected());
    MenuItem front = new MenuItem(I18n.t("designer.front"), Icons.of("fth-arrow-up"));
    front.setOnAction(e -> moveLayer(true));
    MenuItem back = new MenuItem(I18n.t("designer.back"), Icons.of("fth-arrow-down"));
    back.setOnAction(e -> moveLayer(false));
    MenuItem delete = new MenuItem(I18n.t("designer.delete"), Icons.of("fth-trash-2"));
    delete.setOnAction(e -> deleteSelected());
    return new ContextMenu(duplicate, front, back, delete);
  }

  private void applyInspector() {
    if (selected == null || isReadOnly()) {
      return;
    }
    StageRenderer.Look look = renderer.look(selected);
    try {
      double x = parse(xField.getText(), selected.hasPosition() ? selected.x() : 0);
      double y = parse(yField.getText(), selected.hasPosition() ? selected.y() : 0);
      if (selected.isText()) {
        String words = StageRenderer.escape(textWordsField.getText().replace("\\n", "\n"));
        double wrap = Math.max(0, parse(textWrapField.getText(), selected.textWidth()));
        if (x != selected.x() || y != selected.y() || !words.equals(selected.text())
            || wrap != selected.textWidth()) {
          selected.setPosition(x, y);
          selected.text(words);
          selected.textWidth(wrap);
          writeRegions();
        }
        return;
      }
      double direction = normalizeDirection(parse(directionField.getText(), look.direction()));
      double size = parse(sizeField.getText(), look.size());
      boolean ui = renderer.isUiSprite(selected.type());
      double pxWidth = ui ? parse(pxWidthField.getText(), look.width()) : look.width();
      double pxHeight = ui ? parse(pxHeightField.getText(), look.height()) : look.height();
      boolean sizeChanged = size != look.size();
      boolean changed = !selected.hasPosition() || x != selected.x() || y != selected.y()
          || direction != look.direction() || sizeChanged
          || Math.abs(pxWidth - look.width()) > 0.005 || Math.abs(pxHeight - look.height()) > 0.005;
      if (!changed) {
        return;
      }
      if (ui && !sizeChanged) {
        if (Math.abs(pxWidth - look.width()) > 0.005 || selected.hasWidth()) {
          selected.width(pxWidth);
        }
        if (Math.abs(pxHeight - look.height()) > 0.005 || selected.hasHeight()) {
          selected.height(pxHeight);
        }
      } else if (ui) {
        // a new size % rescales the costume, like setSize at run time
        selected.clearWidth();
        selected.clearHeight();
      }
      selected.setPosition(x, y);
      if (direction != look.direction() || selected.hasDirection()) {
        selected.direction(direction);
      }
      if (size != look.size() || selected.hasSize()) {
        selected.size(size);
      }
      writeRegions();
    } catch (NumberFormatException ignored) {
      updateInspector();
    }
  }

  private void applyStageSize() {
    if (isReadOnly()) {
      return;
    }
    try {
      int w = Integer.parseInt(widthField.getText().trim());
      int h = Integer.parseInt(heightField.getText().trim());
      if (w >= 50 && h >= 50 && w <= 4000 && h <= 4000
          && (w != model.width() || h != model.height())) {
        model.size(w, h);
        writeRegions();
      } else {
        widthField.setText(String.valueOf(model.width()));
        heightField.setText(String.valueOf(model.height()));
      }
    } catch (NumberFormatException e) {
      widthField.setText(String.valueOf(model.width()));
      heightField.setText(String.valueOf(model.height()));
    }
  }

  private static double parse(String text, double fallback) {
    return text == null || text.isBlank() ? fallback
        : Double.parseDouble(text.trim().replace(',', '.'));
  }

  // --- model edits -----------------------------------------------------------------

  private String uniqueName(String className) {
    String base = Character.toLowerCase(className.charAt(0)) + className.substring(1);
    base = base.replaceAll("[^A-Za-z0-9_]", "");
    if (base.isEmpty() || !Character.isJavaIdentifierStart(base.charAt(0))) {
      base = "sprite";
    }
    String name = base;
    int n = 2;
    while (model.sprites().byName(name) != null) {
      name = base + n++;
    }
    return name;
  }

  void addInstance(String className) {
    if (isReadOnly()) {
      return;
    }
    SpriteRef ref = new SpriteRef(uniqueName(className), className);
    ref.instantiated(true);
    ref.setPosition(0, 0);
    ref.added(true);
    model.addSprite(ref);
    selected = ref;
    writeRegions();
  }

  /** Imperative approach: a plain {@code Sprite} field with a picked costume. */
  private void addPlainSprite() {
    AssetPickerDialog.pickImage(project.root(), I18n.t("designer.newsprite.costume"))
        .ifPresent(costume -> {
          if (!ensureSpriteImport()) {
            return;
          }
          String base = costume.contains("/")
              ? costume.substring(costume.lastIndexOf('/') + 1) : costume;
          base = base.replaceAll("\\.[A-Za-z]+$", "").replaceAll("[^A-Za-z0-9]", "");
          SpriteRef ref = new SpriteRef(uniqueName(base.isEmpty() ? "sprite" : base), "Sprite");
          ref.instantiated(true);
          ref.costume(costume);
          ref.setPosition(0, 0);
          ref.added(true);
          model.addSprite(ref);
          selected = ref;
          writeRegions();
        });
  }

  /** A {@code Text} on the stage: {@code name = new Text("Hello!", 0, 0, 200);}. */
  void addText() {
    if (isReadOnly() || !ensureImport("Text")) {
      return;
    }
    SpriteRef ref = new SpriteRef(uniqueName("text"), "Text");
    ref.instantiated(true);
    ref.added(true);
    ref.text(StageRenderer.escape(I18n.t("designer.text.default")));
    ref.setPosition(-100, 0);
    ref.textWidth(200);
    model.addSprite(ref);
    selected = ref;
    writeRegions();
  }

  private boolean ensureSpriteImport() {
    return ensureImport("Sprite");
  }

  /**
   * A plain Sprite or a Text field needs {@code import org.openpatch.scratch.<Type>;}.
   * That line lives outside the regions, so the user confirms adding it.
   */
  private boolean ensureImport(String simpleName) {
    try {
      String source = Files.readString(stageFile, StandardCharsets.UTF_8);
      if (source.contains("import org.openpatch.scratch." + simpleName + ";")
          || source.contains("import org.openpatch.scratch.*;")) {
        return true;
      }
      var confirm = new javafx.scene.control.Alert(
          javafx.scene.control.Alert.AlertType.CONFIRMATION,
          I18n.t("designer.import.confirm", stageClass + ".java"),
          I18n.ok(), I18n.cancel());
      confirm.setHeaderText(null);
      if (confirm.showAndWait().orElse(null) != I18n.ok()) {
        return false;
      }
      String importLine = "import org.openpatch.scratch." + simpleName + ";\n";
      int at = source.indexOf("import ");
      String updated = at >= 0 ? source.substring(0, at) + importLine + source.substring(at)
          : importLine + source;
      undo.push(source);
      redo.clear();
      LocalHistory.writeString(project.root(), stageFile, updated);
      // regions are unchanged: keep the in-memory model, the caller adds to it
      document = StageDocument.read(updated);
      return true;
    } catch (IOException | RuntimeException e) {
      showError(e);
      return false;
    }
  }

  private void duplicateSelected() {
    if (selected == null || isReadOnly()) {
      return;
    }
    SpriteRef copy = selected.copyAs(uniqueName(selected.isText() ? "text" : selected.type()));
    copy.instantiated(true);
    copy.added(true);
    copy.setPosition((selected.hasPosition() ? selected.x() : 0) + 20,
        (selected.hasPosition() ? selected.y() : 0) - 20);
    copy.visible(selected.isVisible());
    model.addSprite(copy);
    selected = copy;
    writeRegions();
  }

  private void deleteSelected() {
    if (selected == null || isReadOnly()) {
      return;
    }
    model.sprites().remove(selected);
    selected = null;
    writeRegions();
  }

  /** Layer order is add order: front = added last. */
  private void moveLayer(boolean toFront) {
    if (selected == null || isReadOnly()) {
      return;
    }
    model.sprites().remove(selected);
    if (toFront) {
      model.sprites().add(selected);
    } else {
      model.sprites().add(0, selected);
    }
    writeRegions();
  }

  private void renameSelected() {
    if (selected == null || isReadOnly()) {
      return;
    }
    String oldName = selected.name();
    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(oldName);
    dialog.setTitle(I18n.t("designer.rename"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("designer.rename.prompt"));
    dialog.getDialogPane().getButtonTypes().setAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);
    dialog.showAndWait().ifPresent(raw -> {
      String name = raw.trim();
      if (name.equals(oldName)) {
        return;
      }
      try {
        java.util.regex.Pattern external = java.util.regex.Pattern.compile(
            "\\." + java.util.regex.Pattern.quote(oldName) + "\\b");
        for (Path source : project.javaSources()) {
          if (!source.equals(stageFile) && external.matcher(Files.readString(source)).find()) {
            throw new IllegalArgumentException(I18n.t("designer.rename.external", oldName));
          }
        }
        document.renameInstance(oldName, name);
        selected = model.sprites().byName(name);
        writeRegions();
      } catch (IOException | RuntimeException e) {
        showError(e);
      }
    });
  }

  private void addBackdrop() {
    AssetPickerDialog.pickImage(project.root(), I18n.t("designer.backdrop.add"))
        .ifPresent(ref -> {
          if (ref.matches("(?i).*\\.(png|jpg|jpeg|gif)$")) {
            String name = ref.substring(ref.lastIndexOf('/') + 1).replaceAll("\\.[^.]+$", "");
            model.addBackdrop(new StageModel.Backdrop(name, ref));
          } else {
            model.addBackdrop(new StageModel.Backdrop(ref, null));
          }
          writeRegions();
        });
  }

  private void addSound() {
    AssetPickerDialog.pickSound(project.root(), I18n.t("designer.sound.add"))
        .ifPresent(ref -> {
          StageModel.Sound sound;
          if (ref.matches("(?i).*\\.(wav|ogg|aiff|aif|au)$")) {
            String name = ref.substring(ref.lastIndexOf('/') + 1).replaceAll("\\.[^.]+$", "");
            sound = new StageModel.Sound(name, ref);
          } else {
            sound = new StageModel.Sound(ref, null);
          }
          if (model.sounds().stream().noneMatch(s -> s.name().equals(sound.name()))) {
            model.addSound(sound);
            writeRegions();
          }
        });
  }

  // --- persistence -----------------------------------------------------------------

  private void writeRegions() {
    if (isReadOnly()) {
      return;
    }
    String selectedName = selected == null ? null : selected.name();
    try {
      String before = Files.readString(stageFile, StandardCharsets.UTF_8);
      String updated = document.write(model);
      if (!updated.equals(before)) {
        undo.push(before);
        redo.clear();
        LocalHistory.writeString(project.root(), stageFile, updated);
      }
      // the document is now the source of truth for further edits
      document = StageDocument.read(updated);
      model = document.model();
      listener.onStageWritten(stageFile);
    } catch (IOException | RuntimeException e) {
      showError(e);
      readFromDisk();
    }
    selected = selectedName == null ? null : model.sprites().byName(selectedName);
    refreshAll();
  }

  private Runnable undoDelegate;
  private Runnable redoDelegate;

  /**
   * Beside the code, undo and redo are the code's: one history for typing and
   * designer changes, in the order they happened.
   */
  void setUndoDelegates(Runnable undo, Runnable redo) {
    this.undoDelegate = undo;
    this.redoDelegate = redo;
  }

  void undo() {
    if (undoDelegate != null) {
      undoDelegate.run();
      return;
    }
    restore(undo, redo);
  }

  void redo() {
    if (redoDelegate != null) {
      redoDelegate.run();
      return;
    }
    restore(redo, undo);
  }

  private void restore(Deque<String> from, Deque<String> to) {
    if (from.isEmpty()) {
      return;
    }
    try {
      to.push(Files.readString(stageFile, StandardCharsets.UTF_8));
      LocalHistory.writeString(project.root(), stageFile, from.pop());
      reload();
      listener.onStageWritten(stageFile);
    } catch (IOException e) {
      showError(e);
    }
  }

  private void showError(Exception e) {
    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
        javafx.scene.control.Alert.AlertType.ERROR,
        I18n.t("designer.error", e.getMessage() == null ? e.toString() : e.getMessage()),
        I18n.ok());
    alert.setHeaderText(null);
    alert.show();
  }

  @Override
  public String toString() {
    return "StageDesignerView[" + stageClass.toLowerCase(Locale.ROOT) + "]";
  }
}
