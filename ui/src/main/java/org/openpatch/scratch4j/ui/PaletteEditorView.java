package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import org.openpatch.scratch4j.core.assets.GimpPalette;
import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.core.io.LocalHistory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The editor for a {@code .gpl} colour palette: its swatches as a grid laid
 * out by the palette's columns (click selects, Shift-click selects a second
 * one, double-click edits the colour, drag reorders), the selected colour's
 * name and hex value beside it, and actions to add, duplicate, delete, sort,
 * fill a gradient between two swatches, take the colours of an image or merge
 * another palette. The star makes it the project's default palette.
 */
final class PaletteEditorView extends BorderPane implements SavableTool {

  /** More colours than this from one image would bury the palette. */
  static final int IMAGE_COLOURS = 256;
  private static final int SWATCH = 36;
  private static final int UNDO_LIMIT = 100;

  private final Path projectRoot;
  private final Path file;
  private final Consumer<Path> onSaved;
  private String name;
  private int columns;
  private final List<GimpPalette.Entry> entries = new ArrayList<>();
  private int selected = -1;
  /** The second swatch (Shift-click), the other end of a gradient; -1 = none. */
  private int other = -1;

  private final UndoHistory<GimpPalette> history = new UndoHistory<>(UNDO_LIMIT);
  private final javafx.beans.property.BooleanProperty dirty =
      new javafx.beans.property.SimpleBooleanProperty();

  private final TextField nameField = new TextField();
  private final Spinner<Integer> columnsField = new Spinner<>(0, 64, 0);
  private final ToggleButton defaultToggle = new ToggleButton(null, Icons.of("fth-star"));
  private final TilePane grid = new TilePane(6, 6);
  private final Rectangle preview = new Rectangle(96, 96);
  private final TextField entryName = new TextField();
  private final TextField hexField = new TextField();
  private final Label rgbLabel = new Label();
  private final VBox details = new VBox(8);
  private final Label status = new Label();
  private final List<Node> needsSelection = new ArrayList<>();
  private final Button gradient = new Button(null, Icons.of("mdi2g-gradient-horizontal"));
  private boolean updating;

  PaletteEditorView(Path projectRoot, Path file, Consumer<Path> onSaved) throws IOException {
    this.projectRoot = projectRoot;
    this.file = file;
    this.onSaved = onSaved;
    getStyleClass().add("palette-editor");
    load(GimpPalette.read(file));
    setTop(new VBox(topBar(), actionBar()));
    ScrollPane scroll = new ScrollPane(grid);
    scroll.setFitToWidth(true);
    scroll.getStyleClass().add("edge-to-edge");
    grid.setPadding(new Insets(12));
    grid.setMaxWidth(Region.USE_PREF_SIZE);
    BorderPane.setAlignment(grid, Pos.TOP_LEFT);
    setCenter(scroll);
    setRight(detailsPanel());
    HBox footer = new HBox(status);
    footer.getStyleClass().add("editor-status");
    setBottom(footer);
    setOnKeyPressed(e -> {
      if (e.getTarget() instanceof TextField) {
        return;
      }
      if (shortcut(e.getCode(), e.isShortcutDown(), e.isShiftDown())) {
        e.consume();
      }
    });
    refresh();
  }

  Path file() {
    return file;
  }

  /**
   * Re-reads the file after something else wrote it (the image editor's
   * palette panel), unless there are edits here that would be lost.
   */
  void reloadIfUnchanged() {
    if (dirty.get()) {
      return;
    }
    try {
      load(GimpPalette.read(file));
      history.clear();
      refresh();
    } catch (IOException e) {
      status.setText(I18n.t("paletteeditor.error", e.getMessage()));
    }
  }

  /** The palette as edited (not necessarily saved). */
  GimpPalette palette() {
    return new GimpPalette(name, columns, entries);
  }

  int selectedIndex() {
    return selected;
  }

  // --- layout --------------------------------------------------------------

  private Node topBar() {
    nameField.setPromptText(I18n.t("paletteeditor.name"));
    nameField.setPrefColumnCount(14);
    nameField.setTooltip(new Tooltip(I18n.t("paletteeditor.name")));
    nameField.setOnAction(e -> commitName());
    nameField.focusedProperty().addListener((o, was, now) -> {
      if (!now) commitName();
    });
    columnsField.setEditable(true);
    columnsField.setPrefWidth(84);
    columnsField.setTooltip(new Tooltip(I18n.t("paletteeditor.columns.tooltip")));
    columnsField.valueProperty().addListener((o, a, value) -> {
      if (!updating && value != null && value != columns) {
        history.record(palette());
        columns = value;
        dirty.set(true);
        refresh();
      }
    });
    defaultToggle.getStyleClass().addAll("button-icon", "flat");
    defaultToggle.setTooltip(new Tooltip(I18n.t("colorpalette.default")));
    defaultToggle.setVisible(projectRoot != null);
    defaultToggle.setManaged(projectRoot != null);
    defaultToggle.setOnAction(e -> {
      try {
        Palettes.setProjectDefault(projectRoot,
            defaultToggle.isSelected() ? paletteId() : Palettes.SCRATCH);
      } catch (IOException ex) {
        defaultToggle.setSelected(!defaultToggle.isSelected());
        status.setText(I18n.t("paletteeditor.error", ex.getMessage()));
      }
    });
    Button save = new Button(I18n.t("imageeditor.save"), Icons.of("fth-save"));
    save.getStyleClass().add("success");
    save.setOnAction(e -> save());
    save.setTooltip(new Tooltip(I18n.t("editor.save.tooltip")));
    save.setMinWidth(Region.USE_PREF_SIZE);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    Label columnsLabel = new Label(I18n.t("paletteeditor.columns"));
    columnsLabel.setMinWidth(Region.USE_PREF_SIZE);
    HBox bar = new HBox(6, nameField, columnsLabel, columnsField,
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-corner-up-left", I18n.t("imageeditor.undo"), this::undo),
        Icons.button("fth-corner-up-right", I18n.t("imageeditor.redo"), this::redo),
        new Separator(javafx.geometry.Orientation.VERTICAL),
        defaultToggle, spacer, save);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    return bar;
  }

  private Node actionBar() {
    Button duplicate = Icons.button("fth-copy", I18n.t("paletteeditor.duplicate"),
        this::duplicateSelected);
    Button delete = Icons.button("fth-trash-2", I18n.t("paletteeditor.delete"),
        this::deleteSelected);
    Button left = Icons.button("fth-arrow-left", I18n.t("paletteeditor.left"),
        () -> moveSelected(-1));
    Button right = Icons.button("fth-arrow-right", I18n.t("paletteeditor.right"),
        () -> moveSelected(1));
    needsSelection.addAll(List.of(duplicate, delete, left, right));
    gradient.getStyleClass().addAll("button-icon", "flat");
    gradient.setTooltip(new Tooltip(I18n.t("paletteeditor.gradient")));
    gradient.setOnAction(e -> askGradient());
    HBox bar = new HBox(4,
        Icons.button("fth-plus", I18n.t("paletteeditor.add"), this::addAfterSelected),
        duplicate, delete, left, right,
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("mdi2s-sort-variant", I18n.t("paletteeditor.sort.hue"),
            () -> sort(PaletteEditorView::byHue)),
        Icons.button("mdi2b-brightness-6", I18n.t("paletteeditor.sort.brightness"),
            () -> sort(Comparator.comparingDouble(PaletteEditorView::luminance))),
        gradient,
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.labeled("fth-image", I18n.t("paletteeditor.fromimage"), this::chooseImage),
        Icons.labeled("mdi2p-palette-outline", I18n.t("paletteeditor.merge"),
            this::choosePalette));
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    return bar;
  }

  private Node detailsPanel() {
    preview.setArcWidth(12);
    preview.setArcHeight(12);
    preview.setStroke(Color.gray(0.5));
    preview.setCursor(javafx.scene.Cursor.HAND);
    preview.setOnMouseClicked(e -> editColour(preview));
    Tooltip.install(preview, new Tooltip(I18n.t("paletteeditor.edit")));
    entryName.setPromptText(I18n.t("paletteeditor.colour.name"));
    entryName.setOnAction(e -> commitEntryName());
    entryName.focusedProperty().addListener((o, was, now) -> {
      if (!now) commitEntryName();
    });
    hexField.setPromptText("#rrggbb");
    hexField.setOnAction(e -> commitHex());
    hexField.focusedProperty().addListener((o, was, now) -> {
      if (!now) commitHex();
    });
    rgbLabel.getStyleClass().add("text-muted");
    Button edit = Icons.labeled("mdi2e-eyedropper", I18n.t("paletteeditor.edit"),
        () -> editColour(preview));
    Label nameLabel = new Label(I18n.t("paletteeditor.colour.name"));
    nameLabel.getStyleClass().add("field-label");
    Label hexLabel = new Label(I18n.t("paletteeditor.hex"));
    hexLabel.getStyleClass().add("field-label");
    details.getChildren().addAll(preview, edit, nameLabel, entryName, hexLabel, hexField,
        rgbLabel);
    details.setPadding(new Insets(12));
    details.setPrefWidth(200);
    details.getStyleClass().add("palette-details");
    return details;
  }

  // --- model ---------------------------------------------------------------

  private void load(GimpPalette palette) {
    name = palette.name();
    columns = palette.columns();
    entries.clear();
    entries.addAll(palette.entries());
    selected = entries.isEmpty() ? -1 : Math.min(Math.max(selected, 0), entries.size() - 1);
    other = -1;
  }

  /** Redraws the grid and the details after any change. */
  private void refresh() {
    updating = true;
    if (!nameField.getText().strip().equals(name)) {
      nameField.setText(name);
    }
    columnsField.getValueFactory().setValue(columns);
    updating = false;
    if (other >= entries.size() || other == selected) {
      other = -1;
    }
    grid.setPrefColumns(columns > 0 ? columns : 8);
    grid.getChildren().clear();
    for (int i = 0; i < entries.size(); i++) {
      grid.getChildren().add(swatch(i));
    }
    boolean any = selected >= 0;
    needsSelection.forEach(n -> n.setDisable(!any));
    gradient.setDisable(!any || other < 0);
    details.setDisable(!any);
    updating = true;
    if (any) {
      GimpPalette.Entry entry = entries.get(selected);
      preview.setFill(Palettes.color(entry));
      entryName.setText(entry.name());
      hexField.setText(entry.hex());
      rgbLabel.setText(entry.red() + ", " + entry.green() + ", " + entry.blue());
    } else {
      preview.setFill(Color.TRANSPARENT);
      entryName.clear();
      hexField.clear();
      rgbLabel.setText("");
    }
    updating = false;
    defaultToggle.setSelected(projectRoot != null
        && Palettes.projectDefault(projectRoot).equals(paletteId()));
    status.setText(I18n.t("paletteeditor.status", entries.size(), file.getFileName()));
  }

  private Node swatch(int index) {
    GimpPalette.Entry entry = entries.get(index);
    Rectangle chip = new Rectangle(SWATCH, SWATCH, Palettes.color(entry));
    chip.setArcWidth(8);
    chip.setArcHeight(8);
    chip.setStroke(Color.gray(0.5));
    StackPane cell = new StackPane(chip);
    cell.getStyleClass().add("palette-swatch");
    if (index == selected) {
      cell.getStyleClass().add("selected");
    } else if (index == other) {
      cell.getStyleClass().add("other");
    }
    cell.setCursor(javafx.scene.Cursor.HAND);
    Tooltip.install(cell, new Tooltip(entry.name().isEmpty() ? entry.hex()
        : entry.name() + "  " + entry.hex()));
    cell.setOnMouseClicked(e -> {
      if (e.getButton() != MouseButton.PRIMARY) {
        return;
      }
      requestFocus();
      if (e.isShiftDown() && selected >= 0 && index != selected) {
        other = index;
      } else {
        selected = index;
        other = -1;
      }
      refresh();
      if (e.getClickCount() == 2) {
        editColour(grid.getChildren().get(index));
      }
    });
    cell.setOnDragDetected(e -> {
      var board = cell.startDragAndDrop(TransferMode.MOVE);
      ClipboardContent content = new ClipboardContent();
      content.putString(Integer.toString(index));
      board.setContent(content);
      board.setDragView(chip.snapshot(null, null));
      e.consume();
    });
    cell.setOnDragOver(e -> {
      if (e.getGestureSource() instanceof Node source && source.getParent() == grid) {
        e.acceptTransferModes(TransferMode.MOVE);
      }
      e.consume();
    });
    cell.setOnDragDropped(e -> {
      int from = Integer.parseInt(e.getDragboard().getString());
      move(from, index);
      e.setDropCompleted(true);
      e.consume();
    });
    return cell;
  }

  /** Records undo, applies {@code change} and redraws. */
  private void change(Runnable change) {
    history.record(palette());
    change.run();
    dirty.set(true);
    refresh();
  }

  void undo() {
    if (!history.canUndo()) {
      return;
    }
    load(history.undo(palette()));
    dirty.set(true);
    refresh();
  }

  void redo() {
    if (!history.canRedo()) {
      return;
    }
    load(history.redo(palette()));

    dirty.set(true);
    refresh();
  }

  void select(int index) {
    selected = index;
    other = -1;
    refresh();
  }

  /** Selects {@code index} as the second swatch, the other end of a gradient. */
  void selectOther(int index) {
    other = index;
    refresh();
  }

  /** Adds a colour after the selected swatch (or at the end) and selects it. */
  void addColour(Color color) {
    change(() -> {
      int at = selected < 0 ? entries.size() : selected + 1;
      entries.add(at, Palettes.entry(color));
      selected = at;
      other = -1;
    });
  }

  private void addAfterSelected() {
    addColour(selected < 0 ? Color.BLACK : Palettes.color(entries.get(selected)));
  }

  private void duplicateSelected() {
    if (selected >= 0) {
      GimpPalette.Entry entry = entries.get(selected);
      change(() -> {
        entries.add(selected + 1, entry);
        selected++;
      });
    }
  }

  void deleteSelected() {
    if (selected >= 0) {
      change(() -> {
        entries.remove(selected);
        selected = Math.min(selected, entries.size() - 1);
        other = -1;
      });
    }
  }

  private void moveSelected(int by) {
    if (selected >= 0) {
      move(selected, Math.max(0, Math.min(entries.size() - 1, selected + by)));
    }
  }

  /** Moves the swatch at {@code from} to {@code to}, shifting the ones between. */
  void move(int from, int to) {
    if (from == to || from < 0 || from >= entries.size()) {
      return;
    }
    change(() -> {
      entries.add(to, entries.remove(from));
      selected = to;
      other = -1;
    });
  }

  private void sort(Comparator<GimpPalette.Entry> order) {
    if (entries.size() > 1) {
      change(() -> {
        GimpPalette.Entry keep = selected >= 0 ? entries.get(selected) : null;
        entries.sort(order);
        selected = keep == null ? -1 : entries.indexOf(keep);
        other = -1;
      });
    }
  }

  /** Greys first (dark to light), then the colours around the colour wheel. */
  static int byHue(GimpPalette.Entry a, GimpPalette.Entry b) {
    Color ca = Palettes.color(a);
    Color cb = Palettes.color(b);
    boolean greyA = ca.getSaturation() < 0.1;
    boolean greyB = cb.getSaturation() < 0.1;
    if (greyA != greyB) {
      return greyA ? -1 : 1;
    }
    if (greyA || ca.getHue() == cb.getHue()) {
      return Double.compare(luminance(a), luminance(b));
    }
    return Double.compare(ca.getHue(), cb.getHue());
  }

  static double luminance(GimpPalette.Entry e) {
    return 0.299 * e.red() + 0.587 * e.green() + 0.114 * e.blue();
  }

  /**
   * Puts {@code steps} colours between the selected swatch and the second
   * one, blending from the first to the second, right after the first.
   */
  void gradient(int steps) {
    if (selected < 0 || other < 0 || steps < 1) {
      return;
    }
    GimpPalette.Entry from = entries.get(selected);
    GimpPalette.Entry to = entries.get(other);
    change(() -> {
      List<GimpPalette.Entry> blend = new ArrayList<>();
      for (int i = 1; i <= steps; i++) {
        double t = i / (double) (steps + 1);
        blend.add(new GimpPalette.Entry(mix(from.red(), to.red(), t),
            mix(from.green(), to.green(), t), mix(from.blue(), to.blue(), t), ""));
      }
      entries.addAll(selected + 1, blend);
      if (other > selected) {
        other += steps;
      }
    });
  }

  private static int mix(int a, int b, double t) {
    return (int) Math.round(a + (b - a) * t);
  }

  private void askGradient() {
    Spinner<Integer> steps = new Spinner<>(1, 32, 4);
    steps.setEditable(true);
    javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
        new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("paletteeditor.gradient"));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    HBox row = new HBox(8, new Label(I18n.t("paletteeditor.gradient.steps")), steps);
    row.setAlignment(Pos.CENTER_LEFT);
    dialog.getDialogPane().setContent(row);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    if (getScene() != null) {
      dialog.initOwner(getScene().getWindow());
    }
    if (dialog.showAndWait().orElse(null) == I18n.ok()) {
      gradient(steps.getValue());
    }
  }

  /** Adds the colours that are not in the palette yet, keeping their names. */
  int addMissing(List<GimpPalette.Entry> colours) {
    java.util.Set<Integer> present = new java.util.HashSet<>();
    entries.forEach(e -> present.add(e.rgb()));
    List<GimpPalette.Entry> added = new ArrayList<>();
    for (GimpPalette.Entry entry : colours) {
      if (present.add(entry.rgb())) {
        added.add(entry);
      }
    }
    if (!added.isEmpty()) {
      change(() -> entries.addAll(added));
    }
    return added.size();
  }

  /** Adds an image's colours (the most common {@value #IMAGE_COLOURS}). */
  void addColoursOf(Path image) throws IOException {
    java.awt.image.BufferedImage read = javax.imageio.ImageIO.read(image.toFile());
    if (read == null) {
      throw new IOException(I18n.t("paletteeditor.fromimage.unreadable", image.getFileName()));
    }
    List<GimpPalette.Entry> colours = GimpPalette.colorsOf(read);
    int added = addMissing(colours.subList(0, Math.min(colours.size(), IMAGE_COLOURS)));
    status.setText(colours.size() > IMAGE_COLOURS
        ? I18n.t("paletteeditor.fromimage.many", colours.size(), added)
        : I18n.t("paletteeditor.added", added));
  }

  private void chooseImage() {
    FileChooser chooser = chooser(I18n.t("paletteeditor.fromimage"), "assets/images");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
        I18n.t("paletteeditor.images"), "*.png", "*.jpg", "*.jpeg", "*.gif"));
    File chosen = chooser.showOpenDialog(getScene().getWindow());
    if (chosen != null) {
      try {
        addColoursOf(chosen.toPath());
      } catch (IOException e) {
        status.setText(I18n.t("paletteeditor.error", e.getMessage()));
      }
    }
  }

  private void choosePalette() {
    FileChooser chooser = chooser(I18n.t("paletteeditor.merge"), Palettes.FOLDER);
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
        I18n.t("paletteeditor.palettes"), "*.gpl"));
    File chosen = chooser.showOpenDialog(getScene().getWindow());
    if (chosen != null) {
      try {
        int added = addMissing(GimpPalette.read(chosen.toPath()).entries());
        status.setText(I18n.t("paletteeditor.added", added));
      } catch (IOException e) {
        status.setText(I18n.t("paletteeditor.error", e.getMessage()));
      }
    }
  }

  private FileChooser chooser(String title, String folder) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title);
    Path start = projectRoot == null ? null : projectRoot.resolve(folder);
    if (start != null && Files.isDirectory(start)) {
      chooser.setInitialDirectory(start.toFile());
    }
    return chooser;
  }

  /** Opens the colour picker on the selected swatch; the pick replaces its colour. */
  private void editColour(Node owner) {
    if (selected < 0 || owner.getScene() == null) {
      return;
    }
    int index = selected;
    GimpPalette.Entry entry = entries.get(index);
    new ScratchColorPicker(Palettes.color(entry), projectRoot).openAt(owner,
        picked -> setColour(index, picked));
  }

  /** Replaces the colour of swatch {@code index}, keeping its name. */
  void setColour(int index, Color color) {
    GimpPalette.Entry entry = entries.get(index);
    GimpPalette.Entry picked = Palettes.entry(color);
    if (picked.rgb() != entry.rgb()) {
      change(() -> entries.set(index, new GimpPalette.Entry(picked.red(), picked.green(),
          picked.blue(), entry.name())));
    }
  }

  private void commitName() {
    String text = nameField.getText().strip();
    if (!updating && !text.equals(name)) {
      change(() -> name = text);
    }
  }

  private void commitEntryName() {
    if (updating || selected < 0) {
      return;
    }
    GimpPalette.Entry entry = entries.get(selected);
    String text = entryName.getText().strip();
    if (!text.equals(entry.name())) {
      int index = selected;
      change(() -> entries.set(index, new GimpPalette.Entry(entry.red(), entry.green(),
          entry.blue(), text)));
    }
  }

  private void commitHex() {
    if (updating || selected < 0) {
      return;
    }
    String text = hexField.getText().strip();
    try {
      setColour(selected, Color.web(text.startsWith("#") ? text : "#" + text));
    } catch (IllegalArgumentException e) {
      status.setText(I18n.t("paletteeditor.hex.invalid", text));
    }
    refresh();
  }

  /** The id pickers use for this file (see {@link Palettes}). */
  private String paletteId() {
    return projectRoot == null ? "" : projectRoot.relativize(file).toString().replace('\\', '/');
  }

  /**
   * Ctrl+S saves, Ctrl+Z/Ctrl+Y (Ctrl+Shift+Z) undo and redo, Ctrl+D
   * duplicates, Delete removes the selected swatch, the arrow keys move the
   * selection. Returns whether the key was handled.
   */
  boolean shortcut(KeyCode code, boolean ctrl, boolean shift) {
    if (ctrl) {
      switch (code) {
        case S -> save();
        case Z -> {
          if (shift) redo(); else undo();
        }
        case Y -> redo();
        case D -> duplicateSelected();
        default -> {
          return false;
        }
      }
      return true;
    }
    switch (code) {
      case DELETE, BACK_SPACE -> deleteSelected();
      case LEFT -> step(-1);
      case RIGHT -> step(1);
      case UP -> step(-(columns > 0 ? columns : 8));
      case DOWN -> step(columns > 0 ? columns : 8);
      default -> {
        return false;
      }
    }
    return true;
  }

  private void step(int by) {
    if (!entries.isEmpty()) {
      select(Math.max(0, Math.min(entries.size() - 1, (selected < 0 ? 0 : selected) + by)));
    }
  }

  // --- SavableTool -----------------------------------------------------------

  @Override
  public javafx.beans.property.ReadOnlyBooleanProperty dirtyProperty() {
    return dirty;
  }

  @Override
  public String unsavedCloseQuestion() {
    return I18n.t("paletteeditor.close.unsaved");
  }

  @Override
  public void save() {
    try {
      if (projectRoot != null) {
        LocalHistory.snapshot(projectRoot, file);
      }
      AtomicFiles.writeString(file, palette().format());
      dirty.set(false);
      status.setText(I18n.t("paletteeditor.saved", file.getFileName()));
      onSaved.accept(file);
    } catch (IOException e) {
      status.setText(I18n.t("paletteeditor.error", e.getMessage()));
    }
  }
}
