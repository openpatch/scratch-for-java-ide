package org.openpatch.scratch4j.ui;

import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.openpatch.scratch4j.core.assets.GimpPalette;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The image editor's palette: the swatches of the palette the colour pickers
 * show (see {@link Palettes}), always in view. A click paints with the colour;
 * the right-click menu replaces a swatch with the current colour or removes
 * it. + adds the current colour, the pencil opens the palette in the palette
 * editor, and "from this picture" makes a palette of the picture's colours.
 * Changing a built-in palette copies it into the project first.
 */
final class PalettePanel extends VBox {

  private static final int MAX_COLUMNS = 8;

  private final Path projectRoot;
  private final ObjectProperty<Color> colour;
  private final ComboBox<Palettes.Ref> paletteBox = new ComboBox<>();
  private final TilePane swatches = new TilePane(3, 3);
  private final Label message = new Label();
  private final Button fromImageButton =
      Icons.button("fth-image", I18n.t("colorpalette.fromimage"), () -> { });
  private Consumer<Path> onOpen = file -> { };
  private Consumer<Path> onWritten = file -> { };
  private String id;
  private GimpPalette palette;
  private boolean refreshing;

  /** {@code colour} is the colour painted with: swatches set it, + adds it. */
  PalettePanel(Path projectRoot, ObjectProperty<Color> colour) {
    super(6);
    this.projectRoot = projectRoot;
    this.colour = colour;
    getStyleClass().add("palette-panel");
    setPadding(new Insets(8));
    Label title = new Label(I18n.t("colorpalette.title"));
    title.getStyleClass().add("card-title");
    paletteBox.setMaxWidth(Double.MAX_VALUE);
    paletteBox.setTooltip(new Tooltip(I18n.t("colorpalette.choose")));
    paletteBox.setOnAction(e -> {
      Palettes.Ref ref = paletteBox.getValue();
      if (!refreshing && ref != null) {
        Palettes.choose(projectRoot, ref.id());
        show(ref.id());
      }
    });
    swatches.setPrefColumns(MAX_COLUMNS);
    ScrollPane scroll = new ScrollPane(swatches);
    // not fit to width: the palette's columns (at most 8) decide where rows wrap
    swatches.setMaxWidth(Region.USE_PREF_SIZE);
    // four rows; the layer list below grows instead of squeezing the swatches
    scroll.setPrefViewportHeight(100);
    scroll.setMinHeight(Region.USE_PREF_SIZE);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.getStyleClass().addAll("edge-to-edge", "palette-swatches");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox actions = new HBox(2,
        Icons.button("fth-plus", I18n.t("colorpalette.add"), this::addCurrent),
        Icons.button("fth-edit-2", I18n.t("colorpalette.edit"), this::openInEditor),
        spacer, fromImageButton);
    actions.setAlignment(Pos.CENTER_LEFT);
    actions.setVisible(projectRoot != null);
    actions.setManaged(projectRoot != null);
    message.getStyleClass().add("text-muted");
    message.setWrapText(true);
    message.setMaxWidth(170);
    message.managedProperty().bind(message.textProperty().isNotEmpty());
    getChildren().addAll(title, paletteBox, scroll, actions, message);
    colour.addListener((o, a, b) -> markCurrent());
    reload();
  }

  void setOnOpen(Consumer<Path> action) {
    onOpen = action;
  }

  /** Called with the file after the panel wrote a palette (others reload it). */
  void setOnWritten(Consumer<Path> action) {
    onWritten = action;
  }

  /** "From this picture": {@code picture} gives the picture's colours on demand. */
  void setPicture(java.util.function.Supplier<java.awt.image.BufferedImage> picture,
      String suggestedName) {
    fromImageButton.setOnAction(e -> fromPicture(picture.get(), suggestedName));
  }

  /** Re-reads the palette list and the shown palette (a file may have changed). */
  void reload() {
    String current = Palettes.current(projectRoot);
    refreshing = true;
    paletteBox.getItems().setAll(Palettes.available(projectRoot));
    paletteBox.getItems().stream().filter(ref -> ref.id().equals(current)).findFirst()
        .ifPresent(paletteBox::setValue);
    refreshing = false;
    show(current);
  }

  String paletteId() {
    return id;
  }

  GimpPalette palette() {
    return palette;
  }

  private void show(String paletteId) {
    id = paletteId;
    palette = Palettes.load(projectRoot, paletteId);
    int columns = palette.columns() > 0 ? palette.columns() : MAX_COLUMNS;
    swatches.setPrefColumns(Math.min(columns, MAX_COLUMNS));
    swatches.getChildren().clear();
    List<GimpPalette.Entry> entries = palette.entries();
    for (int i = 0; i < entries.size(); i++) {
      swatches.getChildren().add(swatch(i, entries.get(i)));
    }
    markCurrent();
  }

  private StackPane swatch(int index, GimpPalette.Entry entry) {
    Color color = Palettes.color(entry);
    Rectangle chip = new Rectangle(16, 16, color);
    chip.setStroke(Color.gray(0.6));
    StackPane cell = new StackPane(chip);
    cell.getStyleClass().add("palette-swatch-small");
    cell.setCursor(javafx.scene.Cursor.HAND);
    Tooltip.install(cell, new Tooltip(entry.name().isEmpty() ? entry.hex()
        : entry.name() + "  " + entry.hex()));
    cell.setOnMouseClicked(e -> {
      if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
        pick(index);
      }
    });
    if (projectRoot != null) {
      MenuItem replace = new MenuItem(I18n.t("colorpalette.replace"));
      replace.setOnAction(e -> replace(index, colour.get()));
      MenuItem remove = new MenuItem(I18n.t("colorpalette.remove"));
      remove.setOnAction(e -> remove(index));
      ContextMenu menu = new ContextMenu(replace, remove);
      cell.setOnContextMenuRequested(e -> menu.show(cell, e.getScreenX(), e.getScreenY()));
    }
    return cell;
  }

  /** Paints with swatch {@code index}, keeping the current transparency (.gpl has no alpha). */
  void pick(int index) {
    Color c = Palettes.color(palette.entries().get(index));
    Color current = colour.get();
    colour.set(Color.color(c.getRed(), c.getGreen(), c.getBlue(),
        current == null ? 1 : current.getOpacity()));
  }

  /** Outlines the swatch of the colour painted with. */
  private void markCurrent() {
    Color current = colour.get();
    int rgb = current == null ? -1 : Palettes.entry(current).rgb();
    for (int i = 0; i < swatches.getChildren().size(); i++) {
      boolean match = palette.entries().get(i).rgb() == rgb;
      swatches.getChildren().get(i).pseudoClassStateChanged(SELECTED, match);
    }
  }

  private static final javafx.css.PseudoClass SELECTED =
      javafx.css.PseudoClass.getPseudoClass("selected");

  void addCurrent() {
    List<GimpPalette.Entry> entries = new ArrayList<>(palette.entries());
    entries.add(Palettes.entry(colour.get()));
    write(entries, I18n.t("colorpalette.added"));
  }

  void replace(int index, Color color) {
    List<GimpPalette.Entry> entries = new ArrayList<>(palette.entries());
    GimpPalette.Entry old = entries.get(index);
    GimpPalette.Entry picked = Palettes.entry(color);
    entries.set(index, new GimpPalette.Entry(picked.red(), picked.green(), picked.blue(),
        old.name()));
    write(entries, "");
  }

  void remove(int index) {
    List<GimpPalette.Entry> entries = new ArrayList<>(palette.entries());
    entries.remove(index);
    write(entries, "");
  }

  /** Saves the changed palette (a built-in one as a copy in the project) and shows it. */
  private void write(List<GimpPalette.Entry> entries, String done) {
    boolean copied = Palettes.isBuiltin(id);
    try {
      String written = Palettes.save(projectRoot, id,
          new GimpPalette(palette.name(), palette.columns(), entries));
      Palettes.choose(projectRoot, written);
      reload();
      message.setText(copied ? I18n.t("colorpalette.copied", written) : done);
      onWritten.accept(projectRoot.resolve(written));
    } catch (IOException e) {
      message.setText(I18n.t("paletteeditor.error", e.getMessage()));
    }
  }

  /** Opens the palette in the palette editor; a built-in one is copied into the project. */
  private void openInEditor() {
    if (Palettes.isBuiltin(id)) {
      write(palette.entries(), "");
    }
    if (!Palettes.isBuiltin(id)) {
      onOpen.accept(projectRoot.resolve(id));
    }
  }

  /**
   * Writes a new palette of {@code picture}'s colours (the most common
   * {@value PaletteEditorView#IMAGE_COLOURS}) and shows it.
   */
  void fromPicture(java.awt.image.BufferedImage picture, String name) {
    List<GimpPalette.Entry> colours = GimpPalette.colorsOf(picture);
    List<GimpPalette.Entry> kept = colours.subList(0,
        Math.min(colours.size(), PaletteEditorView.IMAGE_COLOURS));
    Path file = Palettes.newFile(projectRoot, name);
    try {
      new GimpPalette(name, Math.min(MAX_COLUMNS, Math.max(1, kept.size())), kept).write(file);
      String written = Palettes.idOf(projectRoot, file);
      Palettes.choose(projectRoot, written);
      reload();
      message.setText(colours.size() > kept.size()
          ? I18n.t("paletteeditor.fromimage.many", colours.size(), kept.size())
          : I18n.t("colorpalette.fromimage.done", kept.size(), file.getFileName()));
      onWritten.accept(file);
    } catch (IOException e) {
      message.setText(I18n.t("paletteeditor.error", e.getMessage()));
    }
  }
}
