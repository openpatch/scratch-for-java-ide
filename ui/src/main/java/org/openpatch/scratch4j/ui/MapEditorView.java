package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.tiled.TmxCompatibility;
import org.openpatch.scratch4j.core.tiled.TmxDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Tiled map editor: paint tile layers with tiles (or blocks of tiles) from
 * the tilesets, draw objects (rectangles, points, polygons) with names, types
 * and properties on object layers, manage layers and tilesets, resize the map.
 * It saves Tiled's own format in the form every Scratch for Java version loads.
 */
final class MapEditorView extends BorderPane {

  private enum Tool { PAINT, ERASE, FILL, RECTANGLE, PICK, SELECT_TILES, SELECT, NEW_RECTANGLE,
    NEW_ELLIPSE, NEW_POINT, NEW_POLYGON }

  private final ScratchProject project;
  private final Path file;
  private final Consumer<Path> onSaved;
  private TmxDocument doc;

  private final Canvas canvas = new Canvas();
  private final Canvas palette = new Canvas();
  private final ComboBox<TmxDocument.Tileset> tilesetBox = new ComboBox<>();
  private final ListView<TmxDocument.Layer> layerList = new ListView<>();
  private final Slider opacity = new Slider(0, 1, 1);
  private final Label status = new Label();
  private final VBox banner = new VBox(4);
  private final VBox inspector = new VBox(6);
  private final TextField objectName = new TextField();
  private final TextField objectType = new TextField();
  private final TextField objectX = number();
  private final TextField objectY = number();
  private final TextField objectW = number();
  private final TextField objectH = number();
  private final TableView<String[]> properties = new TableView<>();
  private final ToggleButton gridToggle = new ToggleButton(null, Icons.of("fth-grid"));
  private final Map<Path, Image> images = new HashMap<>();
  private final Deque<TmxDocument.State> undo = new ArrayDeque<>();
  private final Deque<TmxDocument.State> redo = new ArrayDeque<>();

  private Tool tool = Tool.PAINT;
  private double zoom = 2;
  private TmxDocument.Layer active;
  /** The brush: a block of gids from the palette, row by row. */
  private long[][] brush = {{0}};
  private int[] paletteStart;
  private int[] dragStart;
  private int[] hover;
  private TmxDocument.MapObject selectedObject;
  private double[] objectDragOffset;
  private final List<double[]> polygon = new ArrayList<>();
  private boolean dirty;
  private boolean strokeRecorded;
  private boolean spaceDown;
  private double[] dragHere;
  private final ToggleGroup tools = new ToggleGroup();
  private boolean updatingInspector;
  private ScrollPane scroll;
  /** Tile selection (x0, y0, x1, y1 inclusive) of the select-tiles tool. */
  private int[] tileSelection;
  private double[] panStart;
  private final Canvas minimap = new Canvas(220, 150);
  private final VBox animationBox = new VBox(4);
  private final javafx.animation.AnimationTimer animator = new javafx.animation.AnimationTimer() {
    private long last;

    @Override
    public void handle(long now) {
      // animated tiles at 10 frames per second are smooth enough for a preview
      if (now - last > 50_000_000L && hasAnimations()) {
        last = now;
        redraw();
      }
    }
  };

  MapEditorView(ScratchProject project, Path file, Consumer<Path> onSaved, Runnable onOpenCode)
      throws IOException {
    this.project = project;
    this.file = file;
    this.onSaved = onSaved;
    this.doc = TmxDocument.open(file);
    getStyleClass().add("map-editor");

    setTop(new VBox(toolbar(), banner));
    setLeft(layerPanel());
    scroll = new ScrollPane(canvas);
    scroll.getStyleClass().add("paint-scroll");
    scroll.setPannable(false);
    // Ctrl + wheel zooms around the mouse, the middle button (or Space + drag) pans
    scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
      if (e.isShortcutDown() && e.getDeltaY() != 0) {
        zoomAt(e.getDeltaY() > 0 ? zoom * 1.25 : zoom / 1.25, e.getX(), e.getY());
        e.consume();
      }
    });
    scroll.hvalueProperty().addListener((o, a, b) -> drawMinimap());
    scroll.vvalueProperty().addListener((o, a, b) -> drawMinimap());
    scroll.viewportBoundsProperty().addListener((o, a, b) -> drawMinimap());
    // the Code button floats in the corner, like in the other visual editors
    Button code = Icons.labeled("fth-code", I18n.t("mode.code"), onOpenCode);
    code.getStyleClass().add("floating-mode-button");
    StackPane center = new StackPane(scroll, code);
    StackPane.setAlignment(code, Pos.TOP_RIGHT);
    StackPane.setMargin(code, new Insets(6, 22, 0, 0));
    setCenter(center);
    setRight(rightPanel());
    status.getStyleClass().add("editor-status");
    setBottom(status);

    canvas.setFocusTraversable(true);
    canvas.setOnMousePressed(e -> {
      if (e.getButton() == MouseButton.MIDDLE || spaceDown) {
        panStart = new double[] {e.getScreenX(), e.getScreenY(), scroll.getHvalue(),
            scroll.getVvalue()};
        return;
      }
      press(e);
    });
    canvas.setOnMouseDragged(e -> {
      if (panStart != null) {
        pan(e);
        return;
      }
      drag(e);
    });
    canvas.setOnMouseReleased(e -> {
      if (panStart != null) {
        panStart = null;
        return;
      }
      release(e);
    });
    sceneProperty().addListener((o, old, scene) -> {
      if (scene == null) animator.stop(); else animator.start();
    });
    canvas.setOnMouseMoved(e -> {
      hover = tileAt(e);
      updateStatus();
      redraw();
    });
    // the usual shortcuts anywhere in the editor; text fields keep their own
    addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
      if (e.getTarget() instanceof javafx.scene.control.TextInputControl) return;
      if (shortcut(e.getCode(), e.isShortcutDown(), e.isShiftDown())) e.consume();
    });
    addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> {
      if (e.getCode() == KeyCode.SPACE) spaceDown = false;
    });
    reloadAll();
  }

  Path file() {
    return file;
  }

  boolean isDirty() {
    return dirty;
  }

  TmxDocument document() {
    return doc;
  }

  Canvas canvas() {
    return canvas;
  }

  /** Asked when the tab closes with unsaved changes. */
  boolean confirmClose() {
    if (!dirty) {
      return true;
    }
    Alert ask = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("map.close.unsaved"),
        I18n.ok(), I18n.cancel());
    ask.setHeaderText(null);
    Theme.style(ask);
    return ask.showAndWait().orElse(null) == I18n.ok();
  }

  // --- layout ------------------------------------------------------------------------

  private Node toolbar() {
    HBox bar = new HBox(4);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    String[][] spec = {
        {"PAINT", "fth-edit-2", "map.tool.paint"}, {"ERASE", "fth-delete", "map.tool.erase"},
        {"FILL", "fth-droplet", "map.tool.fill"}, {"RECTANGLE", "fth-square", "map.tool.rect"},
        {"PICK", "fth-eye", "map.tool.pick"},
        {"SELECT_TILES", "fth-crop", "map.tool.selecttiles"},
        {"SELECT", "fth-mouse-pointer", "map.tool.select"},
        {"NEW_RECTANGLE", "fth-plus-square", "map.tool.newrect"},
        {"NEW_ELLIPSE", "fth-circle", "map.tool.newellipse"},
        {"NEW_POINT", "fth-map-pin", "map.tool.newpoint"},
        {"NEW_POLYGON", "fth-hexagon", "map.tool.newpolygon"}};
    for (String[] s : spec) {
      ToggleButton button = new ToggleButton(null, Icons.of(s[1], 16));
      button.setTooltip(new Tooltip(I18n.t(s[2])));
      button.setToggleGroup(tools);
      button.getStyleClass().add("flat");
      Tool t = Tool.valueOf(s[0]);
      button.setUserData(t);
      button.setSelected(t == tool);
      button.setOnAction(e -> setTool(t));
      bar.getChildren().add(button);
      if (t == Tool.SELECT_TILES) {
        bar.getChildren().add(new javafx.scene.control.Separator(
            javafx.geometry.Orientation.VERTICAL));
      }
    }
    tools.selectedToggleProperty().addListener((o, old, now) -> {
      if (now == null) old.setSelected(true);
    });
    gridToggle.setSelected(true);
    gridToggle.getStyleClass().add("flat");
    gridToggle.setTooltip(new Tooltip(I18n.t("imageeditor.grid.tooltip")));
    gridToggle.setOnAction(e -> redraw());
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    Button save = new Button(I18n.t("imageeditor.save"), Icons.of("fth-save"));
    save.getStyleClass().add("success");
    save.setOnAction(e -> save());
    bar.getChildren().addAll(new javafx.scene.control.Separator(
            javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-corner-up-left", I18n.t("imageeditor.undo"), this::undo),
        Icons.button("fth-corner-up-right", I18n.t("imageeditor.redo"), this::redo),
        Icons.button("fth-zoom-out", I18n.t("map.zoomout"), () -> setZoom(zoom / 1.5)),
        Icons.button("fth-zoom-in", I18n.t("map.zoomin"), () -> setZoom(zoom * 1.5)),
        gridToggle,
        Icons.button("fth-maximize-2", I18n.t("map.resize"), this::resizeDialog),
        spacer,
        Icons.labeled("fth-log-in", I18n.t("map.use"), this::useInStage), save);
    return bar;
  }

  private Node layerPanel() {
    layerList.setPrefWidth(190);
    layerList.setCellFactory(v -> new ListCell<>() {
      @Override
      protected void updateItem(TmxDocument.Layer layer, boolean empty) {
        super.updateItem(layer, empty);
        if (empty || layer == null) {
          setGraphic(null);
          setText(null);
          return;
        }
        CheckBox visible = new CheckBox();
        visible.setSelected(layer.visible);
        visible.setOnAction(e -> {
          record();
          layer.visible = visible.isSelected();
          changed();
        });
        var icon = Icons.of(layer instanceof TmxDocument.TileLayer ? "fth-grid" : "fth-box");
        setGraphic(new HBox(6, visible, icon));
        setText((layer.group.isEmpty() ? "" : layer.group + "/") + layer.name);
      }
    });
    layerList.getSelectionModel().selectedItemProperty().addListener((o, a, layer) -> {
      if (layer != null) {
        active = layer;
        opacity.setValue(layer.opacity);
        if (layer instanceof TmxDocument.ObjectLayer && isTileTool(tool)) {
          setTool(Tool.SELECT);
        } else if (layer instanceof TmxDocument.TileLayer && !isTileTool(tool)) {
          setTool(Tool.PAINT);
        }
        if (selectedObject != null && !(layer instanceof TmxDocument.ObjectLayer objects
            && objects.objects.contains(selectedObject))) {
          selectObject(null);
        }
        updateStatus();
      }
    });
    layerList.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2 && active != null) renameLayer();
    });
    opacity.valueChangingProperty().addListener((o, was, changing) -> {
      if (!changing && active != null && active.opacity != opacity.getValue()) {
        record();
        active.opacity = opacity.getValue();
        changed();
      }
    });
    HBox actions = new HBox(2,
        Icons.button("fth-grid", I18n.t("map.layer.addtiles"), () -> addLayer(true)),
        Icons.button("fth-box", I18n.t("map.layer.addobjects"), () -> addLayer(false)),
        Icons.button("fth-arrow-up", I18n.t("layers.up"), () -> moveLayer(1)),
        Icons.button("fth-arrow-down", I18n.t("layers.down"), () -> moveLayer(-1)),
        Icons.button("fth-edit-3", I18n.t("map.layer.rename"), this::renameLayer),
        Icons.button("fth-trash-2", I18n.t("layers.delete"), this::deleteLayer));
    Label title = new Label(I18n.t("map.layers"));
    title.getStyleClass().add("card-title");
    VBox panel = new VBox(6, title, layerList, actions,
        new HBox(6, new Label(I18n.t("map.layer.opacity")), opacity));
    VBox.setVgrow(layerList, Priority.ALWAYS);
    panel.setPadding(new Insets(8));
    return panel;
  }

  private Node rightPanel() {
    tilesetBox.setConverter(new StringConverter<>() {
      @Override public String toString(TmxDocument.Tileset t) {
        return t == null ? "" : t.name;
      }
      @Override public TmxDocument.Tileset fromString(String s) {
        return null;
      }
    });
    tilesetBox.setMaxWidth(Double.MAX_VALUE);
    tilesetBox.setOnAction(e -> drawPalette());
    palette.setOnMousePressed(e -> {
      paletteStart = paletteTile(e);
      selectBrush(paletteStart, paletteStart);
    });
    palette.setOnMouseDragged(e -> selectBrush(paletteStart, paletteTile(e)));
    ScrollPane paletteScroll = new ScrollPane(palette);
    paletteScroll.setPrefViewportHeight(260);
    paletteScroll.setPrefViewportWidth(260);
    Button addTileset = Icons.labeled("fth-plus", I18n.t("map.tileset.add"), this::addTileset);
    Label tilesTitle = new Label(I18n.t("map.tilesets"));
    tilesTitle.getStyleClass().add("card-title");

    // object inspector
    GridPane fields = new GridPane();
    fields.setHgap(6);
    fields.setVgap(6);
    fields.addRow(0, new Label(I18n.t("debug.name")), objectName);
    fields.addRow(1, new Label(I18n.t("map.object.type")), objectType);
    fields.addRow(2, new Label("x / y"), new HBox(4, objectX, objectY));
    fields.addRow(3, new Label(I18n.t("map.object.size")), new HBox(4, objectW, objectH));
    for (TextField f : List.of(objectName, objectType, objectX, objectY, objectW, objectH)) {
      f.setOnAction(e -> applyInspector());
      f.focusedProperty().addListener((o, was, focused) -> {
        if (!focused) applyInspector();
      });
    }
    properties.setEditable(true);
    properties.setPrefHeight(150);
    String[] headers = {"debug.name", "debug.type", "debug.value"};
    for (int i = 0; i < 3; i++) {
      int column = i;
      TableColumn<String[], String> c = new TableColumn<>(I18n.t(headers[i]));
      c.setCellValueFactory(row -> new javafx.beans.property.SimpleStringProperty(
          row.getValue()[column]));
      c.setCellFactory(TextFieldTableCell.forTableColumn());
      c.setOnEditCommit(edit -> {
        String[] row = edit.getRowValue();
        record();
        String oldName = row[0];
        row[column] = edit.getNewValue();
        if (selectedObject != null) {
          selectedObject.properties.remove(oldName);
          for (String[] r : properties.getItems()) {
            selectedObject.properties.put(r[0], new String[] {r[1], r[2]});
          }
        }
        changed();
      });
      c.setPrefWidth(i == 2 ? 110 : 80);
      properties.getColumns().add(c);
    }
    HBox propertyActions = new HBox(4,
        Icons.button("fth-plus", I18n.t("map.property.add"), () -> {
          if (selectedObject == null) return;
          record();
          String name = "property" + (selectedObject.properties.size() + 1);
          selectedObject.properties.put(name, new String[] {"string", ""});
          showInspector();
          changed();
        }),
        Icons.button("fth-trash-2", I18n.t("map.property.remove"), () -> {
          String[] row = properties.getSelectionModel().getSelectedItem();
          if (selectedObject == null || row == null) return;
          record();
          selectedObject.properties.remove(row[0]);
          showInspector();
          changed();
        }),
        Icons.button("fth-trash", I18n.t("map.object.delete"), this::deleteObject));
    Label objectTitle = new Label(I18n.t("map.object"));
    objectTitle.getStyleClass().add("card-title");
    inspector.getChildren().addAll(objectTitle, fields,
        new Label(I18n.t("map.object.properties")), properties, propertyActions);
    inspector.setVisible(false);
    inspector.setManaged(false);

    Label mapTitle = new Label(I18n.t("map.minimap"));
    mapTitle.getStyleClass().add("card-title");
    minimap.setOnMousePressed(this::minimapJump);
    minimap.setOnMouseDragged(this::minimapJump);
    VBox panel = new VBox(8, tilesTitle, tilesetBox, paletteScroll, addTileset, animationBox,
        inspector, mapTitle, minimap);
    panel.setPadding(new Insets(8));
    panel.setPrefWidth(290);
    ScrollPane side = new ScrollPane(panel);
    side.setFitToWidth(true);
    side.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    side.setPrefWidth(300);
    return side;
  }

  // --- refresh and drawing ----------------------------------------------------------------

  /** Objects copied with Ctrl+C / Ctrl+X: they paste into this or another map. */
  private static final List<TmxDocument.MapObject> OBJECT_CLIPBOARD = new ArrayList<>();
  /** What Ctrl+V pastes: the last copied tiles or objects. */
  private static boolean objectsCopiedLast;

  /**
   * One shortcut (Ctrl is Cmd on macOS). Returns whether it was handled.
   * Undo/redo/save, copy/cut/paste/duplicate of tiles and objects, delete,
   * select all tiles, escape, arrow keys to move an object, zoom.
   */
  boolean shortcut(KeyCode code, boolean ctrl, boolean shift) {
    if (ctrl) {
      switch (code) {
        case Z -> {
          if (shift) redo(); else undo();
        }
        case Y -> redo();
        case S -> save();
        case C, X -> {
          if (selectedObject != null) {
            copyObject(code == KeyCode.X);
          } else if (tileSelection != null) {
            copySelection(code == KeyCode.X);
            objectsCopiedLast = false;
          }
        }
        case V -> paste();
        case D -> {
          if (selectedObject != null) {
            copyObject(false);
            paste();
          }
        }
        case A -> {
          if (active instanceof TmxDocument.TileLayer) {
            selectTiles(0, 0, doc.width - 1, doc.height - 1);
            updateStatus();
            redraw();
          }
        }
        case PLUS, EQUALS, ADD -> setZoom(zoom * 1.25);
        case MINUS, SUBTRACT -> setZoom(zoom / 1.25);
        case DIGIT0, NUMPAD0 -> setZoom(2);
        default -> {
          return false;
        }
      }
      return true;
    }
    switch (code) {
      case DELETE, BACK_SPACE -> {
        if (selectedObject != null) {
          deleteObject();
        } else if (tileSelection != null) {
          clearSelection();
        } else {
          return false;
        }
      }
      case ESCAPE -> {
        polygon.clear();
        dragStart = null;
        tileSelection = null;
        selectObject(null);
        updateStatus();
        redraw();
      }
      case ENTER -> {
        if (tool != Tool.NEW_POLYGON) return false;
        finishPolygon();
      }
      case SPACE -> spaceDown = true;
      case LEFT, RIGHT, UP, DOWN -> {
        if (selectedObject == null) return false;
        double step = shift ? (code == KeyCode.LEFT || code == KeyCode.RIGHT
            ? doc.tileWidth : doc.tileHeight) : 1;
        record();
        switch (code) {
          case LEFT -> selectedObject.x -= step;
          case RIGHT -> selectedObject.x += step;
          case UP -> selectedObject.y -= step;
          default -> selectedObject.y += step;
        }
        showInspector();
        changed();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  private void copyObject(boolean cut) {
    OBJECT_CLIPBOARD.clear();
    OBJECT_CLIPBOARD.add(selectedObject);
    objectsCopiedLast = true;
    status.setText(I18n.t("map.object.copied"));
    if (cut) deleteObject();
  }

  /**
   * Ctrl+V: copied objects land under the mouse (or a bit beside the
   * original) on the active object layer; copied tiles are stamped under the
   * mouse and stay the brush for more.
   */
  void paste() {
    if (objectsCopiedLast && !OBJECT_CLIPBOARD.isEmpty()) {
      TmxDocument.ObjectLayer target = active instanceof TmxDocument.ObjectLayer layer ? layer
          : doc.layers.stream().filter(l -> l instanceof TmxDocument.ObjectLayer)
              .map(l -> (TmxDocument.ObjectLayer) l).reduce((a, b) -> b).orElse(null);
      if (target == null) {
        status.setText(I18n.t("map.hint.objectlayer"));
        return;
      }
      record();
      TmxDocument.MapObject source = OBJECT_CLIPBOARD.get(0);
      double dx = hover != null ? hover[0] * doc.tileWidth - source.x : doc.tileWidth / 2.0;
      double dy = hover != null ? hover[1] * doc.tileHeight - source.y : doc.tileHeight / 2.0;
      TmxDocument.MapObject pasted = doc.duplicate(target, source, dx, dy);
      if (active != target) layerList.getSelectionModel().select(target);
      setTool(Tool.SELECT);
      selectObject(pasted);
      // the next paste goes beside this one
      OBJECT_CLIPBOARD.set(0, pasted);
      changed();
      return;
    }
    if (active instanceof TmxDocument.TileLayer tiles && hover != null) {
      strokeRecorded = false;
      stamp(tiles, hover);
    }
    setTool(Tool.PAINT);
  }

  TmxDocument.ObjectLayer objectLayer(String name) {
    for (TmxDocument.Layer l : doc.layers) {
      if (l instanceof TmxDocument.ObjectLayer o && o.name.equals(name)) return o;
    }
    return null;
  }

  /** The brush becomes one tile (tests; the palette does this for users). */
  void useBrush(long gid) {
    brush = new long[][] {{gid}};
    drawPalette();
    showAnimation();
  }

  private void reloadAll() {
    // start with a real tile: an empty brush would paint "nothing" (erase)
    if (brush.length == 1 && brush[0].length == 1 && brush[0][0] == 0
        && !doc.tilesets.isEmpty()) {
      brush = new long[][] {{doc.tilesets.get(0).firstGid}};
    }
    TmxDocument.Tileset previous = tilesetBox.getValue();
    tilesetBox.getItems().setAll(doc.tilesets);
    tilesetBox.setValue(doc.tilesets.contains(previous) ? previous
        : doc.tilesets.isEmpty() ? null : doc.tilesets.get(0));
    List<TmxDocument.Layer> topFirst = new ArrayList<>(doc.layers);
    java.util.Collections.reverse(topFirst);
    TmxDocument.Layer keep = active;
    layerList.getItems().setAll(topFirst);
    if (keep == null || !doc.layers.contains(keep)) {
      keep = doc.layers.stream().filter(l -> l instanceof TmxDocument.TileLayer).findFirst()
          .orElse(doc.layers.isEmpty() ? null : doc.layers.get(0));
    }
    active = keep;
    if (active != null) {
      layerList.getSelectionModel().select(active);
    }
    drawPalette();
    showAnimation();
    showBanner();
    redraw();
    drawMinimap();
    updateStatus();
  }

  private void changed() {
    dirty = true;
    layerList.refresh();
    redraw();
    drawMinimap();
    updateStatus();
  }

  private void setZoom(double next) {
    zoom = Math.max(0.25, Math.min(8, next));
    redraw();
    drawMinimap();
  }

  private static boolean isTileTool(Tool t) {
    return t == Tool.PAINT || t == Tool.ERASE || t == Tool.FILL || t == Tool.RECTANGLE
        || t == Tool.PICK || t == Tool.SELECT_TILES;
  }

  void setTool(Tool next) {
    tool = next;
    polygon.clear();
    if (next != Tool.SELECT_TILES) tileSelection = null;
    for (var toggle : tools.getToggles()) {
      if (toggle.getUserData() == next) toggle.setSelected(true);
    }
    updateStatus();
    redraw();
  }

  /** Zooms keeping the map point under the mouse (viewport x/y) where it is. */
  private void zoomAt(double next, double viewportX, double viewportY) {
    var viewport = scroll.getViewportBounds();
    double extraW = Math.max(0, canvas.getWidth() - viewport.getWidth());
    double extraH = Math.max(0, canvas.getHeight() - viewport.getHeight());
    double mapX = (scroll.getHvalue() * extraW + viewportX) / zoom;
    double mapY = (scroll.getVvalue() * extraH + viewportY) / zoom;
    setZoom(next);
    scroll.layout();
    double newExtraW = Math.max(0, canvas.getWidth() - viewport.getWidth());
    double newExtraH = Math.max(0, canvas.getHeight() - viewport.getHeight());
    if (newExtraW > 0) scroll.setHvalue(clamp((mapX * zoom - viewportX) / newExtraW));
    if (newExtraH > 0) scroll.setVvalue(clamp((mapY * zoom - viewportY) / newExtraH));
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }

  private void pan(MouseEvent e) {
    var viewport = scroll.getViewportBounds();
    double extraW = Math.max(1, canvas.getWidth() - viewport.getWidth());
    double extraH = Math.max(1, canvas.getHeight() - viewport.getHeight());
    scroll.setHvalue(clamp(panStart[2] - (e.getScreenX() - panStart[0]) / extraW));
    scroll.setVvalue(clamp(panStart[3] - (e.getScreenY() - panStart[1]) / extraH));
  }

  // --- minimap -------------------------------------------------------------------------------

  private double minimapScale() {
    double w = doc.width * doc.tileWidth;
    double h = doc.height * doc.tileHeight;
    return Math.min(minimap.getWidth() / Math.max(1, w), minimap.getHeight() / Math.max(1, h));
  }

  void drawMinimap() {
    if (scroll == null) return;
    GraphicsContext g = minimap.getGraphicsContext2D();
    g.clearRect(0, 0, minimap.getWidth(), minimap.getHeight());
    double s = minimapScale();
    double tw = doc.tileWidth * s;
    double th = doc.tileHeight * s;
    g.setFill(Color.web("#e6e8ee"));
    g.fillRect(0, 0, doc.width * tw, doc.height * th);
    g.setImageSmoothing(true);
    for (TmxDocument.Layer layer : doc.layers) {
      if (!layer.visible || !(layer instanceof TmxDocument.TileLayer tiles)) continue;
      g.setGlobalAlpha(layer.opacity);
      for (int i = 0; i < tiles.gids.length; i++) {
        if (tiles.gids[i] != 0) {
          drawTile(g, tiles.gids[i], (i % doc.width) * tw, (i / doc.width) * th, tw + 0.5,
              th + 0.5);
        }
      }
    }
    g.setGlobalAlpha(1);
    // the visible part of the map
    var viewport = scroll.getViewportBounds();
    double extraW = Math.max(0, canvas.getWidth() - viewport.getWidth());
    double extraH = Math.max(0, canvas.getHeight() - viewport.getHeight());
    double vx = scroll.getHvalue() * extraW / zoom * s;
    double vy = scroll.getVvalue() * extraH / zoom * s;
    double vw = Math.min(canvas.getWidth(), viewport.getWidth()) / zoom * s;
    double vh = Math.min(canvas.getHeight(), viewport.getHeight()) / zoom * s;
    g.setStroke(Color.web("#e8590c"));
    g.setLineWidth(2);
    g.strokeRect(vx, vy, vw, vh);
    g.setLineWidth(1);
  }

  /** A click on the minimap centres the view there. */
  private void minimapJump(MouseEvent e) {
    double s = minimapScale();
    var viewport = scroll.getViewportBounds();
    double extraW = canvas.getWidth() - viewport.getWidth();
    double extraH = canvas.getHeight() - viewport.getHeight();
    if (extraW > 0) {
      scroll.setHvalue(clamp((e.getX() / s * zoom - viewport.getWidth() / 2) / extraW));
    }
    if (extraH > 0) {
      scroll.setVvalue(clamp((e.getY() / s * zoom - viewport.getHeight() / 2) / extraH));
    }
  }

  // --- copy and paste of tile regions ------------------------------------------------------------

  /** Ctrl+C / Ctrl+X: the selected tiles become the brush (paste = paint with it). */
  void copySelection(boolean cut) {
    if (tileSelection == null || !(active instanceof TmxDocument.TileLayer tiles)) return;
    int w = tileSelection[2] - tileSelection[0] + 1;
    int h = tileSelection[3] - tileSelection[1] + 1;
    long[][] copied = new long[h][w];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        copied[y][x] = doc.tile(tiles, tileSelection[0] + x, tileSelection[1] + y);
      }
    }
    brush = copied;
    if (cut) clearSelection();
    drawPalette();
    showAnimation();
    status.setText(I18n.t("map.copied", w, h));
  }

  private void clearSelection() {
    if (tileSelection == null || !(active instanceof TmxDocument.TileLayer tiles)) return;
    record();
    for (int y = tileSelection[1]; y <= tileSelection[3]; y++) {
      for (int x = tileSelection[0]; x <= tileSelection[2]; x++) {
        doc.setTile(tiles, x, y, 0);
      }
    }
    changed();
  }

  /** Selects tiles (tests; the select-tiles tool does this for users). */
  void selectTiles(int x0, int y0, int x1, int y1) {
    setTool(Tool.SELECT_TILES);
    tileSelection = new int[] {x0, y0, x1, y1};
  }

  /** The select tool (tests; the toolbar does this for users). */
  void useSelectTool() {
    setTool(Tool.SELECT);
  }

  TmxDocument.MapObject selectedObject() {
    return selectedObject;
  }

  /** Opens with an object chosen (from the stage designer's double click). */
  void selectObjectById(int id) {
    for (TmxDocument.Layer l : doc.layers) {
      if (l instanceof TmxDocument.ObjectLayer layer) {
        for (TmxDocument.MapObject o : layer.objects) {
          if (o.id == id) {
            layerList.getSelectionModel().select(layer);
            setTool(Tool.SELECT);
            selectObject(o);
            // into view
            double[] box = bounds(o);
            var viewport = scroll.getViewportBounds();
            double extraW = canvas.getWidth() - viewport.getWidth();
            double extraH = canvas.getHeight() - viewport.getHeight();
            if (extraW > 0) {
              scroll.setHvalue(clamp((box[0] * zoom - viewport.getWidth() / 2) / extraW));
            }
            if (extraH > 0) {
              scroll.setVvalue(clamp((box[1] * zoom - viewport.getHeight() / 2) / extraH));
            }
            return;
          }
        }
      }
    }
  }

  /** Chooses a layer like a click in the layer list (tests). */
  void chooseLayer(String name) {
    for (TmxDocument.Layer layer : doc.layers) {
      if (layer.name.equals(name)) layerList.getSelectionModel().select(layer);
    }
  }

  TmxDocument.Layer activeLayer() {
    return active;
  }

  long[][] brush() {
    return brush;
  }

  // --- tile animations ----------------------------------------------------------------------------

  /**
   * The animation of the brush's tile: frames (tile id + duration) to edit,
   * or a button that animates the tile (with the tiles after it as frames).
   */
  private void showAnimation() {
    animationBox.getChildren().clear();
    TmxDocument.Tileset t = doc.tilesetOf(brush[0][0]);
    if (t == null || brush[0][0] == 0) return;
    int local = (int) ((brush[0][0] & 0x1FFFFFFFL) - t.firstGid);
    Label title = new Label(I18n.t("map.animation", local));
    title.getStyleClass().add("card-title");
    animationBox.getChildren().add(title);
    List<int[]> frames = t.animations.get(local);
    if (frames == null) {
      animationBox.getChildren().add(Icons.labeled("fth-film", I18n.t("map.animation.create"),
          () -> {
            record();
            List<int[]> made = new ArrayList<>();
            for (int i = 0; i < Math.min(4, t.tileCount - local); i++) {
              made.add(new int[] {local + i, 150});
            }
            current(t).animations.put(local, made);
            animationChanged();
          }));
      return;
    }
    for (int i = 0; i < frames.size(); i++) {
      int[] frame = frames.get(i);
      int index = i;
      TextField tileId = new TextField(String.valueOf(frame[0]));
      TextField duration = new TextField(String.valueOf(frame[1]));
      tileId.setPrefWidth(56);
      duration.setPrefWidth(70);
      tileId.setTooltip(new Tooltip(I18n.t("map.animation.tile")));
      duration.setTooltip(new Tooltip("ms"));
      Runnable apply = () -> {
        try {
          int id = Integer.parseInt(tileId.getText().trim());
          int ms = Integer.parseInt(duration.getText().trim());
          if (id < 0 || id >= t.tileCount || ms < 1) throw new NumberFormatException();
          if (id != frame[0] || ms != frame[1]) {
            record();
            // record() copied the state: edit the live tileset's frame
            int[] live = current(t).animations.get(local).get(index);
            live[0] = id;
            live[1] = ms;
            animationChanged();
          }
        } catch (NumberFormatException | NullPointerException | IndexOutOfBoundsException e) {
          tileId.setText(String.valueOf(frame[0]));
          duration.setText(String.valueOf(frame[1]));
        }
      };
      for (TextField f : List.of(tileId, duration)) {
        f.setOnAction(e -> apply.run());
        f.focusedProperty().addListener((o, was, focused) -> {
          if (!focused) apply.run();
        });
      }
      Canvas thumb = new Canvas(24, 24);
      thumb.getGraphicsContext2D().setImageSmoothing(false);
      drawStill(thumb.getGraphicsContext2D(), t, frame[0], 24);
      Button remove = Icons.button("fth-x", I18n.t("map.animation.remove"), () -> {
        record();
        List<int[]> live = current(t).animations.get(local);
        live.remove(index);
        if (live.isEmpty()) current(t).animations.remove(local);
        animationChanged();
      });
      Label ms = new Label("ms");
      ms.setMinWidth(Region.USE_PREF_SIZE);
      HBox row = new HBox(6, thumb, tileId, duration, ms, remove);
      row.setAlignment(Pos.CENTER_LEFT);
      animationBox.getChildren().add(row);
    }
    animationBox.getChildren().add(new HBox(4,
        Icons.labeled("fth-plus", I18n.t("map.animation.add"), () -> {
          record();
          List<int[]> live = current(t).animations.get(local);
          int[] last = live.get(live.size() - 1);
          live.add(new int[] {Math.min(t.tileCount - 1, last[0] + 1), last[1]});
          animationChanged();
        }),
        Icons.labeled("fth-trash-2", I18n.t("map.animation.delete"), () -> {
          record();
          current(t).animations.remove(local);
          animationChanged();
        })));
  }

  /** The tileset in the document now (undo snapshots replace the objects). */
  private TmxDocument.Tileset current(TmxDocument.Tileset t) {
    for (TmxDocument.Tileset candidate : doc.tilesets) {
      if (candidate.firstGid == t.firstGid) return candidate;
    }
    return t;
  }

  /** One tile of a tileset, not animated (animation frame thumbnails). */
  private void drawStill(GraphicsContext g, TmxDocument.Tileset t, int local, double size) {
    Image image = image(t.image);
    if (image == null) return;
    int columns = Math.max(1, t.columns);
    double sx = t.margin + (local % columns) * (t.tileWidth + t.spacing);
    double sy = t.margin + (local / columns) * (t.tileHeight + t.spacing);
    g.drawImage(image, sx, sy, t.tileWidth, t.tileHeight, 0, 0, size, size);
  }

  private void animationChanged() {
    showAnimation();
    changed();
  }

  private Image image(Path path) {
    if (path == null) return null;
    return images.computeIfAbsent(path, p -> {
      try (InputStream in = Files.newInputStream(p)) {
        return new Image(in);
      } catch (IOException e) {
        return null;
      }
    });
  }

  void redraw() {
    double tw = doc.tileWidth * zoom;
    double th = doc.tileHeight * zoom;
    canvas.setWidth(Math.max(1, doc.width * tw));
    canvas.setHeight(Math.max(1, doc.height * th));
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.setFill(Color.web("#e6e8ee"));
    g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    g.setImageSmoothing(false);
    for (TmxDocument.Layer layer : doc.layers) {
      if (!layer.visible) continue;
      g.setGlobalAlpha(layer.opacity * (layer == active || active == null ? 1 : 0.85));
      if (layer instanceof TmxDocument.TileLayer tiles) {
        for (int i = 0; i < tiles.gids.length; i++) {
          if (tiles.gids[i] != 0) {
            drawTile(g, tiles.gids[i], (i % doc.width) * tw, (i / doc.width) * th, tw, th);
          }
        }
      } else if (layer instanceof TmxDocument.ObjectLayer objects) {
        for (TmxDocument.MapObject o : objects.objects) {
          drawObject(g, o, o == selectedObject);
        }
      }
    }
    g.setGlobalAlpha(1);
    if (gridToggle.isSelected()) {
      g.setStroke(Color.rgb(0, 0, 0, 0.12));
      g.setLineWidth(1);
      for (int x = 0; x <= doc.width; x++) {
        g.strokeLine(x * tw + 0.5, 0, x * tw + 0.5, canvas.getHeight());
      }
      for (int y = 0; y <= doc.height; y++) {
        g.strokeLine(0, y * th + 0.5, canvas.getWidth(), y * th + 0.5);
      }
    }
    // the brush (or the rectangle being dragged) under the mouse
    g.setStroke(Color.web("#855cd6"));
    g.setLineWidth(2);
    if (dragStart != null && hover != null && tool == Tool.RECTANGLE) {
      int x0 = Math.min(dragStart[0], hover[0]);
      int y0 = Math.min(dragStart[1], hover[1]);
      g.strokeRect(x0 * tw, y0 * th, (Math.abs(hover[0] - dragStart[0]) + 1) * tw,
          (Math.abs(hover[1] - dragStart[1]) + 1) * th);
    } else if (hover != null && active instanceof TmxDocument.TileLayer
        && (tool == Tool.PAINT || tool == Tool.ERASE || tool == Tool.FILL
            || tool == Tool.PICK || tool == Tool.RECTANGLE)) {
      int w = tool == Tool.PAINT ? brush[0].length : 1;
      int h = tool == Tool.PAINT ? brush.length : 1;
      if (tool == Tool.PAINT) {
        g.setGlobalAlpha(0.6);
        for (int y = 0; y < h; y++) {
          for (int x = 0; x < w; x++) {
            if (brush[y][x] != 0) {
              drawTile(g, brush[y][x], (hover[0] + x) * tw, (hover[1] + y) * th, tw, th);
            }
          }
        }
        g.setGlobalAlpha(1);
      }
      g.strokeRect(hover[0] * tw, hover[1] * th, w * tw, h * th);
    }
    if (tileSelection != null) {
      g.setStroke(Color.web("#1c7ed6"));
      g.setLineDashes(6, 4);
      g.strokeRect(tileSelection[0] * tw, tileSelection[1] * th,
          (tileSelection[2] - tileSelection[0] + 1) * tw,
          (tileSelection[3] - tileSelection[1] + 1) * th);
      g.setLineDashes();
    }
    if (dragStart != null && hover != null && tool == Tool.SELECT_TILES) {
      g.setStroke(Color.web("#1c7ed6"));
      int x0 = Math.min(dragStart[0], hover[0]);
      int y0 = Math.min(dragStart[1], hover[1]);
      g.strokeRect(x0 * tw, y0 * th, (Math.abs(hover[0] - dragStart[0]) + 1) * tw,
          (Math.abs(hover[1] - dragStart[1]) + 1) * th);
    }
    if (dragStart != null && dragHere != null
        && (tool == Tool.NEW_RECTANGLE || tool == Tool.NEW_ELLIPSE)) {
      double x = Math.min(dragStart[0], dragHere[0]) * zoom;
      double y = Math.min(dragStart[1], dragHere[1]) * zoom;
      double w = Math.abs(dragHere[0] - dragStart[0]) * zoom;
      double h = Math.abs(dragHere[1] - dragStart[1]) * zoom;
      g.setStroke(Color.web("#e8590c"));
      if (tool == Tool.NEW_ELLIPSE) g.strokeOval(x, y, w, h); else g.strokeRect(x, y, w, h);
    }
    if (!polygon.isEmpty()) {
      g.setStroke(Color.web("#e8590c"));
      for (int i = 1; i < polygon.size(); i++) {
        g.strokeLine(polygon.get(i - 1)[0] * zoom, polygon.get(i - 1)[1] * zoom,
            polygon.get(i)[0] * zoom, polygon.get(i)[1] * zoom);
      }
      for (double[] p : polygon) {
        g.fillOval(p[0] * zoom - 3, p[1] * zoom - 3, 6, 6);
      }
    }
  }

  private boolean hasAnimations() {
    for (TmxDocument.Tileset t : doc.tilesets) {
      if (!t.animations.isEmpty()) return true;
    }
    return false;
  }

  /** The tile an animated tile shows now (as Tiled plays it). */
  private long animated(TmxDocument.Tileset t, long gid) {
    long local = (gid & 0x1FFFFFFFL) - t.firstGid;
    List<int[]> frames = t.animations.get((int) local);
    if (frames == null || frames.isEmpty()) return gid;
    long total = 0;
    for (int[] f : frames) total += Math.max(1, f[1]);
    long at = System.currentTimeMillis() % total;
    for (int[] f : frames) {
      at -= Math.max(1, f[1]);
      if (at < 0) return (gid & ~0x1FFFFFFFL) | (t.firstGid + f[0]);
    }
    return gid;
  }

  private void drawTile(GraphicsContext g, long gid, double x, double y, double w, double h) {
    TmxDocument.Tileset t = doc.tilesetOf(gid);
    if (t != null) gid = animated(t, gid);
    Image image = t == null ? null : image(t.image);
    if (image == null) {
      g.setFill(Color.rgb(220, 60, 60, 0.35));
      g.fillRect(x, y, w, h);
      return;
    }
    long local = (gid & 0x1FFFFFFFL) - t.firstGid;
    int columns = Math.max(1, t.columns);
    double sx = t.margin + (local % columns) * (t.tileWidth + t.spacing);
    double sy = t.margin + (local / columns) * (t.tileHeight + t.spacing);
    boolean flipX = (gid & 0x80000000L) != 0;
    boolean flipY = (gid & 0x40000000L) != 0;
    g.save();
    g.translate(x + (flipX ? w : 0), y + (flipY ? h : 0));
    g.scale(flipX ? -1 : 1, flipY ? -1 : 1);
    g.drawImage(image, sx, sy, t.tileWidth, t.tileHeight, 0, 0, w, h);
    g.restore();
  }

  private void drawObject(GraphicsContext g, TmxDocument.MapObject o, boolean selected) {
    g.setStroke(selected ? Color.web("#e8590c") : Color.web("#855cd6"));
    g.setFill(Color.rgb(133, 92, 214, selected ? 0.25 : 0.12));
    g.setLineWidth(selected ? 2.5 : 1.5);
    double x = o.x * zoom;
    double y = o.y * zoom;
    switch (o.shape) {
      case POINT -> {
        g.fillOval(x - 5, y - 5, 10, 10);
        g.strokeOval(x - 5, y - 5, 10, 10);
      }
      case ELLIPSE -> {
        g.fillOval(x, y, o.width * zoom, o.height * zoom);
        g.strokeOval(x, y, o.width * zoom, o.height * zoom);
      }
      case POLYGON, POLYLINE -> {
        double[] xs = new double[o.points.size()];
        double[] ys = new double[o.points.size()];
        for (int i = 0; i < xs.length; i++) {
          xs[i] = x + o.points.get(i)[0] * zoom;
          ys[i] = y + o.points.get(i)[1] * zoom;
        }
        if (o.shape == TmxDocument.Shape.POLYGON) {
          g.fillPolygon(xs, ys, xs.length);
          g.strokePolygon(xs, ys, xs.length);
        } else {
          g.strokePolyline(xs, ys, xs.length);
        }
      }
      case TILE -> {
        // tile objects sit on their bottom-left corner
        drawTile(g, o.gid, x, y - o.height * zoom, o.width * zoom, o.height * zoom);
        g.strokeRect(x, y - o.height * zoom, o.width * zoom, o.height * zoom);
      }
      default -> {
        g.fillRect(x, y, o.width * zoom, o.height * zoom);
        g.strokeRect(x, y, o.width * zoom, o.height * zoom);
      }
    }
    String label = !o.name.isEmpty() ? o.name : o.type;
    if (!label.isEmpty()) {
      g.setFill(Color.web("#3d2a6b"));
      g.fillText(label, x + 3, y - 4);
    }
  }

  private void drawPalette() {
    TmxDocument.Tileset t = tilesetBox.getValue();
    GraphicsContext g = palette.getGraphicsContext2D();
    Image image = t == null ? null : image(t.image);
    if (image == null) {
      palette.setWidth(200);
      palette.setHeight(40);
      g.clearRect(0, 0, 200, 40);
      g.setFill(Color.GRAY);
      g.fillText(I18n.t("map.tileset.none"), 6, 24);
      return;
    }
    double scale = paletteScale(t);
    palette.setWidth(image.getWidth() * scale);
    palette.setHeight(image.getHeight() * scale);
    g.clearRect(0, 0, palette.getWidth(), palette.getHeight());
    g.setImageSmoothing(false);
    g.drawImage(image, 0, 0, palette.getWidth(), palette.getHeight());
    g.setStroke(Color.rgb(0, 0, 0, 0.15));
    for (int c = 0; c <= t.columns; c++) {
      double x = (t.margin + c * (t.tileWidth + t.spacing)) * scale;
      g.strokeLine(x, 0, x, palette.getHeight());
    }
    int rows = (t.tileCount + t.columns - 1) / Math.max(1, t.columns);
    for (int r = 0; r <= rows; r++) {
      double y = (t.margin + r * (t.tileHeight + t.spacing)) * scale;
      g.strokeLine(0, y, palette.getWidth(), y);
    }
    // animated tiles get a small orange corner
    g.setFill(Color.web("#e8590c"));
    for (int local : t.animations.keySet()) {
      double ax = (t.margin + (local % t.columns) * (t.tileWidth + t.spacing)) * scale;
      double ay = (t.margin + (local / t.columns) * (t.tileHeight + t.spacing)) * scale;
      g.fillPolygon(new double[] {ax, ax + 8, ax}, new double[] {ay, ay, ay + 8}, 3);
    }
    // the brush's tiles, when they are from this tileset
    if (brush[0][0] != 0 && t.contains(brush[0][0])) {
      long local = (brush[0][0] & 0x1FFFFFFFL) - t.firstGid;
      int c = (int) (local % t.columns);
      int r = (int) (local / t.columns);
      g.setStroke(Color.web("#e8590c"));
      g.setLineWidth(2);
      g.strokeRect((t.margin + c * (t.tileWidth + t.spacing)) * scale,
          (t.margin + r * (t.tileHeight + t.spacing)) * scale,
          brush[0].length * t.tileWidth * scale, brush.length * t.tileHeight * scale);
      g.setLineWidth(1);
    }
  }

  private static double paletteScale(TmxDocument.Tileset t) {
    return t.tileWidth <= 16 ? 2 : 1;
  }

  private int[] paletteTile(MouseEvent e) {
    TmxDocument.Tileset t = tilesetBox.getValue();
    if (t == null) return null;
    double scale = paletteScale(t);
    int c = (int) Math.floor((e.getX() / scale - t.margin) / (t.tileWidth + t.spacing));
    int r = (int) Math.floor((e.getY() / scale - t.margin) / (t.tileHeight + t.spacing));
    int rows = (t.tileCount + t.columns - 1) / Math.max(1, t.columns);
    return new int[] {Math.max(0, Math.min(t.columns - 1, c)), Math.max(0, Math.min(rows - 1, r))};
  }

  /** A block of tiles from the palette becomes the brush. */
  private void selectBrush(int[] from, int[] to) {
    TmxDocument.Tileset t = tilesetBox.getValue();
    if (t == null || from == null || to == null) return;
    int c0 = Math.min(from[0], to[0]);
    int r0 = Math.min(from[1], to[1]);
    int w = Math.abs(to[0] - from[0]) + 1;
    int h = Math.abs(to[1] - from[1]) + 1;
    brush = new long[h][w];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int local = (r0 + y) * t.columns + c0 + x;
        brush[y][x] = local < t.tileCount ? t.firstGid + local : 0;
      }
    }
    if (tool != Tool.PAINT && tool != Tool.FILL && tool != Tool.RECTANGLE) {
      setTool(Tool.PAINT);
    }
    drawPalette();
    showAnimation();
    updateStatus();
  }

  private void showBanner() {
    banner.getChildren().clear();
    List<TmxCompatibility.Issue> issues = TmxCompatibility.check(doc,
        org.openpatch.scratch4j.core.project.LibraryCheck.projectVersion(project));
    if (issues.isEmpty()) {
      banner.setVisible(false);
      banner.setManaged(false);
      return;
    }
    banner.setVisible(true);
    banner.setManaged(true);
    banner.getStyleClass().setAll("facing-warning");
    banner.setPadding(new Insets(6, 10, 6, 10));
    for (TmxCompatibility.Issue issue : issues) {
      Label line = new Label("\u26A0 " + TmxCompatibility.describe(issue,
          I18n.current() == I18n.Language.DE
              ? org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.DE
              : org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.EN));
      line.setWrapText(true);
      banner.getChildren().add(line);
    }
    if (issues.stream().anyMatch(TmxCompatibility.Issue::fixable)) {
      Button convert = Icons.labeled("fth-check", I18n.t("map.convert"), () -> {
        record();
        TmxCompatibility.convert(doc);
        dirty = true;
        save();
      });
      banner.getChildren().add(convert);
    }
  }

  private void updateStatus() {
    // Scratch coordinates: the map's top left corner is (0, 0), y grows upwards
    String where = hover == null ? "" : "  \u00b7  " + I18n.t("map.status.tile", hover[0],
        hover[1], hover[0] * doc.tileWidth, -hover[1] * doc.tileHeight);
    String hint = switch (tool) {
      case NEW_POLYGON -> I18n.t("map.hint.polygon");
      case SELECT_TILES -> tileSelection == null ? I18n.t("map.hint.selecttiles")
          : I18n.t("map.hint.selection");
      case SELECT, NEW_RECTANGLE, NEW_ELLIPSE, NEW_POINT -> active instanceof TmxDocument.ObjectLayer
          ? "" : I18n.t("map.hint.objectlayer");
      default -> active instanceof TmxDocument.TileLayer ? "" : I18n.t("map.hint.tilelayer");
    };
    status.setText(I18n.t("map.status", doc.width, doc.height, doc.tileWidth, doc.tileHeight)
        + where + (dirty ? "  \u00b7  " + I18n.t("map.unsaved") : "")
        + (hint.isEmpty() ? "" : "  \u00b7  " + hint));
  }

  // --- mouse --------------------------------------------------------------------------------

  private int[] tileAt(MouseEvent e) {
    int x = (int) Math.floor(e.getX() / (doc.tileWidth * zoom));
    int y = (int) Math.floor(e.getY() / (doc.tileHeight * zoom));
    return doc.inside(x, y) ? new int[] {x, y} : null;
  }

  private double[] pixelAt(MouseEvent e) {
    return new double[] {Math.round(e.getX() / zoom), Math.round(e.getY() / zoom)};
  }

  private void press(MouseEvent e) {
    canvas.requestFocus();
    strokeRecorded = false;
    if (tool == Tool.SELECT) {
      // any visible object, whichever layer is chosen: its layer becomes the active one
      double[] p = pixelAt(e);
      for (int i = doc.layers.size() - 1; i >= 0; i--) {
        if (doc.layers.get(i) instanceof TmxDocument.ObjectLayer objects && objects.visible) {
          TmxDocument.MapObject hit = hit(objects, p);
          if (hit != null) {
            if (active != objects) {
              layerList.getSelectionModel().select(objects);
            }
            selectObject(hit);
            objectDragOffset = new double[] {p[0] - hit.x, p[1] - hit.y};
            redraw();
            return;
          }
        }
      }
      selectObject(null);
      redraw();
      return;
    }
    int[] tile = tileAt(e);
    if (active instanceof TmxDocument.TileLayer tiles && tile != null) {
      boolean erase = tool == Tool.ERASE || e.getButton() == MouseButton.SECONDARY;
      switch (erase ? Tool.ERASE : tool) {
        case PAINT -> stamp(tiles, tile);
        case ERASE -> paint(tiles, tile, 0);
        case FILL -> {
          record();
          doc.fill(tiles, tile[0], tile[1], brush[0][0]);
          changed();
        }
        case RECTANGLE -> dragStart = tile;
        case SELECT_TILES -> {
          dragStart = tile;
          tileSelection = null;
        }
        case PICK -> {
          long gid = doc.tile(tiles, tile[0], tile[1]);
          brush = new long[][] {{gid}};
          TmxDocument.Tileset t = doc.tilesetOf(gid);
          if (t != null) tilesetBox.setValue(t);
          setTool(Tool.PAINT);
          drawPalette();
          showAnimation();
        }
        default -> { }
      }
    }
    if (active instanceof TmxDocument.ObjectLayer objects) {
      double[] p = pixelAt(e);
      switch (tool) {
        case NEW_RECTANGLE, NEW_ELLIPSE -> dragStart = new int[] {(int) p[0], (int) p[1]};
        case NEW_POINT -> {
          record();
          var o = doc.addObject(objects, TmxDocument.Shape.POINT, p[0], p[1], 0, 0);
          selectObject(o);
          changed();
        }
        case NEW_POLYGON -> {
          if (e.getClickCount() == 2 && polygon.size() >= 3) {
            finishPolygon();
          } else {
            polygon.add(p);
          }
        }
        default -> { }
      }
    }
    redraw();
  }

  private void drag(MouseEvent e) {
    hover = tileAt(e);
    dragHere = pixelAt(e);
    int[] tile = hover;
    if (active instanceof TmxDocument.TileLayer tiles && tile != null) {
      boolean erase = tool == Tool.ERASE || e.getButton() == MouseButton.SECONDARY;
      if (erase) {
        paint(tiles, tile, 0);
      } else if (tool == Tool.PAINT) {
        stamp(tiles, tile);
      }
    }
    if (tool == Tool.SELECT && selectedObject != null && objectDragOffset != null) {
      if (!strokeRecorded) {
        record();
        strokeRecorded = true;
      }
      double[] p = pixelAt(e);
      selectedObject.x = p[0] - objectDragOffset[0];
      selectedObject.y = p[1] - objectDragOffset[1];
      showInspector();
      changed();
    }
    redraw();
  }

  private void release(MouseEvent e) {
    if (active instanceof TmxDocument.TileLayer tiles && tool == Tool.RECTANGLE
        && dragStart != null && hover != null) {
      record();
      int x0 = Math.min(dragStart[0], hover[0]);
      int y0 = Math.min(dragStart[1], hover[1]);
      int x1 = Math.max(dragStart[0], hover[0]);
      int y1 = Math.max(dragStart[1], hover[1]);
      for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
          // the brush repeats over the rectangle
          doc.setTile(tiles, x, y, brush[(y - y0) % brush.length][(x - x0) % brush[0].length]);
        }
      }
      changed();
    }
    if (active instanceof TmxDocument.TileLayer && tool == Tool.SELECT_TILES
        && dragStart != null && hover != null) {
      tileSelection = new int[] {Math.min(dragStart[0], hover[0]),
          Math.min(dragStart[1], hover[1]), Math.max(dragStart[0], hover[0]),
          Math.max(dragStart[1], hover[1])};
      updateStatus();
    }
    if (active instanceof TmxDocument.ObjectLayer objects
        && (tool == Tool.NEW_RECTANGLE || tool == Tool.NEW_ELLIPSE) && dragStart != null) {
      double[] p = pixelAt(e);
      double x = Math.min(dragStart[0], p[0]);
      double y = Math.min(dragStart[1], p[1]);
      double w = Math.abs(p[0] - dragStart[0]);
      double h = Math.abs(p[1] - dragStart[1]);
      if (w >= 2 && h >= 2) {
        record();
        selectObject(doc.addObject(objects, tool == Tool.NEW_ELLIPSE
            ? TmxDocument.Shape.ELLIPSE : TmxDocument.Shape.RECTANGLE, x, y, w, h));
        changed();
      }
    }
    dragStart = null;
    dragHere = null;
    objectDragOffset = null;
    redraw();
  }

  private void paint(TmxDocument.TileLayer tiles, int[] tile, long gid) {
    if (doc.tile(tiles, tile[0], tile[1]) == gid) return;
    if (!strokeRecorded) {
      record();
      strokeRecorded = true;
    }
    doc.setTile(tiles, tile[0], tile[1], gid);
    changed();
  }

  private void stamp(TmxDocument.TileLayer tiles, int[] at) {
    if (!strokeRecorded) {
      record();
      strokeRecorded = true;
    }
    for (int y = 0; y < brush.length; y++) {
      for (int x = 0; x < brush[y].length; x++) {
        doc.setTile(tiles, at[0] + x, at[1] + y, brush[y][x]);
      }
    }
    changed();
  }

  private TmxDocument.MapObject hit(TmxDocument.ObjectLayer layer, double[] p) {
    for (int i = layer.objects.size() - 1; i >= 0; i--) {
      TmxDocument.MapObject o = layer.objects.get(i);
      double[] box = bounds(o);
      double pad = 4 / zoom;
      if (p[0] >= box[0] - pad && p[0] <= box[2] + pad && p[1] >= box[1] - pad
          && p[1] <= box[3] + pad) {
        return o;
      }
    }
    return null;
  }

  /** [minX, minY, maxX, maxY] in map pixels. */
  private static double[] bounds(TmxDocument.MapObject o) {
    if (o.shape == TmxDocument.Shape.POLYGON || o.shape == TmxDocument.Shape.POLYLINE) {
      double minX = Double.MAX_VALUE;
      double minY = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      for (double[] p : o.points) {
        minX = Math.min(minX, o.x + p[0]);
        minY = Math.min(minY, o.y + p[1]);
        maxX = Math.max(maxX, o.x + p[0]);
        maxY = Math.max(maxY, o.y + p[1]);
      }
      return new double[] {minX, minY, maxX, maxY};
    }
    if (o.shape == TmxDocument.Shape.TILE) {
      return new double[] {o.x, o.y - o.height, o.x + o.width, o.y};
    }
    return new double[] {o.x, o.y, o.x + o.width, o.y + o.height};
  }

  private void finishPolygon() {
    if (polygon.size() < 3 || !(active instanceof TmxDocument.ObjectLayer objects)) {
      polygon.clear();
      redraw();
      return;
    }
    record();
    double[] origin = polygon.get(0);
    var o = doc.addObject(objects, TmxDocument.Shape.POLYGON, origin[0], origin[1], 0, 0);
    for (double[] p : polygon) {
      o.points.add(new double[] {p[0] - origin[0], p[1] - origin[1]});
    }
    polygon.clear();
    selectObject(o);
    changed();
  }

  // --- objects ---------------------------------------------------------------------------

  void selectObject(TmxDocument.MapObject o) {
    selectedObject = o;
    inspector.setVisible(o != null);
    inspector.setManaged(o != null);
    showInspector();
    redraw();
  }

  private void showInspector() {
    if (selectedObject == null) return;
    updatingInspector = true;
    objectName.setText(selectedObject.name);
    objectType.setText(selectedObject.type);
    objectX.setText(TmxNumbers.format(selectedObject.x));
    objectY.setText(TmxNumbers.format(selectedObject.y));
    objectW.setText(TmxNumbers.format(selectedObject.width));
    objectH.setText(TmxNumbers.format(selectedObject.height));
    List<String[]> rows = new ArrayList<>();
    selectedObject.properties.forEach((name, typed) ->
        rows.add(new String[] {name, typed[0], typed[1]}));
    properties.getItems().setAll(rows);
    updatingInspector = false;
  }

  private void applyInspector() {
    if (updatingInspector || selectedObject == null) return;
    try {
      String name = objectName.getText().trim();
      String type = objectType.getText().trim();
      double x = Double.parseDouble(objectX.getText().trim().replace(',', '.'));
      double y = Double.parseDouble(objectY.getText().trim().replace(',', '.'));
      double w = Double.parseDouble(objectW.getText().trim().replace(',', '.'));
      double h = Double.parseDouble(objectH.getText().trim().replace(',', '.'));
      if (name.equals(selectedObject.name) && type.equals(selectedObject.type)
          && x == selectedObject.x && y == selectedObject.y && w == selectedObject.width
          && h == selectedObject.height) {
        return;
      }
      record();
      selectedObject.name = name;
      selectedObject.type = type;
      selectedObject.x = x;
      selectedObject.y = y;
      selectedObject.width = w;
      selectedObject.height = h;
      changed();
    } catch (NumberFormatException e) {
      showInspector();
    }
  }

  private void deleteObject() {
    if (selectedObject == null) return;
    record();
    for (TmxDocument.Layer layer : doc.layers) {
      if (layer instanceof TmxDocument.ObjectLayer objects) {
        objects.objects.remove(selectedObject);
      }
    }
    selectObject(null);
    changed();
  }

  // --- layers, tilesets, map size -----------------------------------------------------------

  private void addLayer(boolean tiles) {
    TextInputDialog ask = new TextInputDialog(tiles ? "Tiles" : "Objects");
    ask.setHeaderText(null);
    ask.setTitle(I18n.t(tiles ? "map.layer.addtiles" : "map.layer.addobjects"));
    Theme.style(ask);
    ask.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
      record();
      TmxDocument.Layer layer = tiles ? doc.addTileLayer(name) : doc.addObjectLayer(name);
      // above the active layer
      doc.layers.remove(layer);
      int at = active == null ? doc.layers.size() : doc.layers.indexOf(active) + 1;
      doc.layers.add(at, layer);
      active = layer;
      reloadAll();
      dirty = true;
    });
  }

  private void moveLayer(int direction) {
    if (active == null) return;
    int i = doc.layers.indexOf(active);
    int j = i + direction;
    if (j < 0 || j >= doc.layers.size()) return;
    record();
    java.util.Collections.swap(doc.layers, i, j);
    reloadAll();
    dirty = true;
  }

  private void renameLayer() {
    if (active == null) return;
    TextInputDialog ask = new TextInputDialog(active.name);
    ask.setHeaderText(I18n.t("map.layer.rename.hint"));
    ask.setTitle(I18n.t("map.layer.rename"));
    Theme.style(ask);
    ask.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
      record();
      active.name = name;
      changed();
    });
  }

  private void deleteLayer() {
    if (active == null || doc.layers.size() <= 1) return;
    record();
    doc.layers.remove(active);
    active = null;
    reloadAll();
    dirty = true;
  }

  /** A project image cut into tiles becomes a tileset (the slicer's size guess). */
  private void addTileset() {
    javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
    chooser.setTitle(I18n.t("map.tileset.add"));
    chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
        I18n.t("stages.window.images"), "*.png", "*.jpg", "*.jpeg", "*.gif"));
    Path images = project.root().resolve("assets/images");
    chooser.setInitialDirectory(Files.isDirectory(images) ? images.toFile()
        : project.root().toFile());
    java.io.File chosen = chooser.showOpenDialog(getScene().getWindow());
    if (chosen == null) return;
    Path image = chosen.toPath().toAbsolutePath().normalize();
    if (!image.startsWith(project.root().toAbsolutePath().normalize())) {
      alert(I18n.t("map.tileset.outside"));
      return;
    }
    Image loaded = image(image);
    if (loaded == null) return;
    TextInputDialog size = new TextInputDialog(doc.tileWidth + "x" + doc.tileHeight);
    size.setHeaderText(I18n.t("map.tileset.size"));
    size.setTitle(I18n.t("map.tileset.add"));
    Theme.style(size);
    size.showAndWait().ifPresent(text -> {
      String[] parts = text.toLowerCase().split("[x\u00d7 ,]+");
      try {
        int w = Integer.parseInt(parts[0].trim());
        int h = Integer.parseInt(parts[parts.length - 1].trim());
        record();
        TmxDocument.Tileset t = doc.addTileset(image, (int) loaded.getWidth(),
            (int) loaded.getHeight(), w, h);
        reloadAll();
        tilesetBox.setValue(t);
        dirty = true;
      } catch (NumberFormatException e) {
        alert(I18n.t("map.tileset.size"));
      }
    });
  }

  private void resizeDialog() {
    TextField w = number();
    TextField h = number();
    w.setText(String.valueOf(doc.width));
    h.setText(String.valueOf(doc.height));
    ComboBox<String> anchor = new ComboBox<>();
    anchor.getItems().addAll(I18n.t("map.resize.topleft"), I18n.t("map.resize.center"),
        I18n.t("map.resize.bottomright"));
    anchor.getSelectionModel().selectFirst();
    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.addRow(0, new Label(I18n.t("map.resize.size")), new HBox(4, w, new Label("\u00d7"), h));
    grid.addRow(1, new Label(I18n.t("map.resize.keep")), anchor);
    javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
        new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("map.resize"));
    dialog.getDialogPane().setContent(grid);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);
    if (dialog.showAndWait().orElse(null) != I18n.ok()) return;
    try {
      int nw = Integer.parseInt(w.getText().trim());
      int nh = Integer.parseInt(h.getText().trim());
      if (nw < 1 || nh < 1 || nw > 1000 || nh > 1000) return;
      int mode = anchor.getSelectionModel().getSelectedIndex();
      int dx = mode == 0 ? 0 : mode == 1 ? (nw - doc.width) / 2 : nw - doc.width;
      int dy = mode == 0 ? 0 : mode == 1 ? (nh - doc.height) / 2 : nh - doc.height;
      record();
      doc.resize(nw, nh, dx, dy);
      changed();
    } catch (NumberFormatException ignored) {
      // nothing to do
    }
  }

  // --- undo, save, use in a stage ---------------------------------------------------------------

  private void record() {
    undo.push(doc.snapshot());
    if (undo.size() > 60) undo.removeLast();
    redo.clear();
  }

  void undo() {
    if (undo.isEmpty()) return;
    int at = doc.layers.indexOf(active);
    redo.push(doc.snapshot());
    doc.restore(undo.pop());
    afterRestore(at);
  }

  void redo() {
    if (redo.isEmpty()) return;
    int at = doc.layers.indexOf(active);
    undo.push(doc.snapshot());
    doc.restore(redo.pop());
    afterRestore(at);
  }

  /** The snapshot has new layer objects: keep the active layer by its position. */
  private void afterRestore(int activeIndex) {
    selectedObject = null;
    active = activeIndex >= 0 && activeIndex < doc.layers.size()
        ? doc.layers.get(activeIndex) : null;
    dirty = true;
    reloadAll();
    selectObject(null);
  }

  void save() {
    try {
      String xml = doc.toXml();
      LocalHistory.writeString(project.root(), file, xml);
      dirty = false;
      // reopen: the elements are now the saved ones
      doc = TmxDocument.open(file);
      active = null;
      reloadAll();
      onSaved.accept(file);
      status.setText(I18n.t("status.saved"));
    } catch (IOException | RuntimeException e) {
      alert(I18n.t("error.save", e.getMessage()));
    }
  }

  /**
   * Puts the map into a stage: {@code new TiledMap("assets/maps/x.tmx", this)}
   * and the chosen tile layers stamped as background, after the stage's
   * managed setup region (outside it, with the user's consent via this button).
   */
  private void useInStage() {
    if (dirty) save();
    List<String> stages;
    try {
      stages = project.stageClasses();
    } catch (IOException e) {
      alert(e.getMessage());
      return;
    }
    if (stages.isEmpty()) {
      alert(I18n.t("map.use.nostage"));
      return;
    }
    ComboBox<String> stage = new ComboBox<>();
    stage.getItems().setAll(stages);
    try {
      String first = project.firstStage();
      stage.setValue(stages.contains(first) ? first : stages.get(0));
    } catch (IOException e) {
      stage.setValue(stages.get(0));
    }
    VBox layerChoices = new VBox(4);
    for (TmxDocument.Layer layer : doc.layers) {
      if (layer instanceof TmxDocument.TileLayer && layer.visible) {
        CheckBox box = new CheckBox(layer.name);
        box.setSelected(true);
        box.setUserData(layer.name);
        layerChoices.getChildren().add(box);
      }
    }
    VBox content = new VBox(8, new Label(I18n.t("map.use.stage")), stage,
        new Label(I18n.t("map.use.layers")), layerChoices);
    javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
        new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("map.use"));
    dialog.setHeaderText(I18n.t("map.use.hint"));
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);
    if (dialog.showAndWait().orElse(null) != I18n.ok()) return;
    List<String> layers = new ArrayList<>();
    for (Node n : layerChoices.getChildren()) {
      if (n instanceof CheckBox box && box.isSelected()) layers.add((String) box.getUserData());
    }
    try {
      Path stageFile = project.sourceOf(stage.getValue());
      String source = Files.readString(stageFile);
      String reference = project.root().toAbsolutePath().normalize()
          .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
      String updated = org.openpatch.scratch4j.core.tiled.MapCode.insert(source, reference,
          layers);
      LocalHistory.writeString(project.root(), stageFile, updated);
      onSaved.accept(stageFile);
      status.setText(I18n.t("map.use.done", stage.getValue()));
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage());
    }
  }

  private void alert(String message) {
    Alert alert = new Alert(Alert.AlertType.WARNING, message, I18n.ok());
    alert.setHeaderText(null);
    Theme.style(alert);
    alert.showAndWait();
  }

  private static TextField number() {
    TextField field = new TextField();
    field.setPrefColumnCount(5);
    return field;
  }

  /** Short number text for the inspector. */
  static final class TmxNumbers {
    private TmxNumbers() {}

    static String format(double value) {
      return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
  }
}
