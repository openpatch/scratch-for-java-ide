package org.openpatch.scratch4j.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import org.openpatch.scratch4j.core.assets.GimpPalette;

import java.nio.file.Path;

/**
 * Scratch's colour picker: Color, Saturation, Brightness and Transparency as
 * 0-100 sliders whose tracks show the colours they lead to, then the swatches
 * of a {@code .gpl} palette (see {@link Palettes}) and the custom colours
 * picked lately. The sliders always pick any colour, whatever the palette.
 * A button shows the current colour; the popup edits it.
 */
final class ScratchColorPicker extends Button {

  private static final int MAX_COLUMNS = 16;

  private final ObjectProperty<Color> value = new SimpleObjectProperty<>(Color.BLACK);
  private final Slider hue = slider();
  private final Slider saturation = slider();
  private final Slider brightness = slider();
  private final Slider transparency = slider();
  private final Region hueTrack = new Region();
  private final Region saturationTrack = new Region();
  private final Region brightnessTrack = new Region();
  private final Region transparencyTrack = new Region();
  private final Rectangle swatch = new Rectangle(18, 18);
  private final Popup popup = new Popup();
  private boolean syncing;
  /** The project whose palettes are offered; null = only the built-in ones. */
  private final Path projectRoot;
  private final ComboBox<Palettes.Ref> paletteBox = new ComboBox<>();
  private final ToggleButton defaultToggle = new ToggleButton(null, Icons.of("fth-star"));
  private final Button addToPalette = new Button(null, Icons.of("fth-plus"));
  private final TilePane swatches = new TilePane(4, 4);
  private final HBox recentRow = new HBox(4);
  private GimpPalette palette;
  private boolean refreshing;

  ScratchColorPicker(Color initial) {
    this(initial, null);
  }

  ScratchColorPicker(Color initial, Path projectRoot) {
    this.projectRoot = projectRoot;
    getStyleClass().add("scratch-color-button");
    swatch.setArcWidth(6);
    swatch.setArcHeight(6);
    swatch.setStroke(Color.gray(0.5));
    setGraphic(swatch);
    setText(I18n.t("imageeditor.color"));

    GridPane rows = new GridPane();
    rows.setHgap(8);
    rows.setVgap(6);
    addRow(rows, 0, "color.hue", hue, hueTrack);
    addRow(rows, 1, "color.saturation", saturation, saturationTrack);
    addRow(rows, 2, "color.brightness", brightness, brightnessTrack);
    addRow(rows, 3, "color.transparency", transparency, transparencyTrack);
    VBox box = new VBox(10, rows, paletteSection());
    popupBox = box;
    // its own cursors: over the code, the editor's text cursor would show through
    box.setCursor(javafx.scene.Cursor.DEFAULT);
    for (Slider slider : new Slider[] {hue, saturation, brightness, transparency}) {
      slider.setCursor(javafx.scene.Cursor.HAND);
    }
    box.setPadding(new Insets(12));
    box.getStyleClass().add("scratch-color-popup");
    popup.getContent().add(box);
    popup.setAutoHide(true);
    setOnAction(e -> {
      if (popup.isShowing()) {
        popup.hide();
      } else {
        Color opened = getValue();
        popup.setOnHidden(h -> rememberIfCustom(opened));
        refreshPalettes();
        box.getStylesheets().setAll(getScene().getStylesheets());
        var bounds = localToScreen(getBoundsInLocal());
        popup.show(this, bounds.getMinX(), bounds.getMaxY() + 4);
      }
    });
    for (Slider slider : new Slider[] {hue, saturation, brightness, transparency}) {
      slider.valueProperty().addListener((o, a, b) -> {
        if (!syncing) {
          value.set(Color.hsb(hue.getValue() * 3.6, saturation.getValue() / 100,
              brightness.getValue() / 100, 1 - transparency.getValue() / 100));
        }
      });
    }
    value.addListener((o, a, color) -> sync(color));
    setValue(initial);
  }

  private VBox popupBox;

  /** Palette choice (star = project default, + = add the colour), swatches, recent colours. */
  private VBox paletteSection() {
    paletteBox.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(paletteBox, javafx.scene.layout.Priority.ALWAYS);
    paletteBox.setTooltip(new Tooltip(I18n.t("colorpalette.choose")));
    paletteBox.setOnAction(e -> {
      Palettes.Ref ref = paletteBox.getValue();
      if (!refreshing && ref != null) {
        Palettes.choose(projectRoot, ref.id());
        showPalette(ref.id());
      }
    });
    defaultToggle.getStyleClass().addAll("button-icon", "flat");
    defaultToggle.setTooltip(new Tooltip(I18n.t("colorpalette.default")));
    defaultToggle.setOnAction(e -> {
      Palettes.Ref ref = paletteBox.getValue();
      if (ref == null) {
        return;
      }
      String id = defaultToggle.isSelected() ? ref.id() : Palettes.SCRATCH;
      try {
        Palettes.setProjectDefault(projectRoot, id);
      } catch (java.io.IOException ex) {
        defaultToggle.setSelected(!defaultToggle.isSelected());
      }
    });
    addToPalette.getStyleClass().addAll("button-icon", "flat");
    addToPalette.setTooltip(new Tooltip(I18n.t("colorpalette.add")));
    addToPalette.setOnAction(e -> {
      Palettes.Ref ref = paletteBox.getValue();
      if (ref == null) {
        return;
      }
      try {
        String written = Palettes.add(projectRoot, ref.id(), getValue());
        Palettes.choose(projectRoot, written);
        refreshPalettes();
      } catch (java.io.IOException ex) {
        addToPalette.setTooltip(new Tooltip(I18n.t("colorpalette.add.failed", ex.getMessage())));
      }
    });
    boolean inProject = projectRoot != null;
    for (Node node : new Node[] {defaultToggle, addToPalette}) {
      node.setVisible(inProject);
      node.setManaged(inProject);
    }
    HBox choice = new HBox(4, paletteBox, defaultToggle, addToPalette);
    choice.setAlignment(Pos.CENTER_LEFT);
    swatches.setPrefColumns(12);
    ScrollPane scroll = new ScrollPane(swatches);
    // not fit to width: the palette's Columns decide where rows wrap
    swatches.setMaxWidth(Region.USE_PREF_SIZE);
    scroll.setMaxHeight(140);
    scroll.getStyleClass().addAll("edge-to-edge", "palette-swatches");
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    Label recentLabel = new Label(I18n.t("colorpalette.recent"));
    recentLabel.getStyleClass().add("text-muted");
    recentRow.setAlignment(Pos.CENTER_LEFT);
    HBox recent = new HBox(8, recentLabel, recentRow);
    recent.setAlignment(Pos.CENTER_LEFT);
    recent.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(
        recentRow.getChildren()));
    recent.managedProperty().bind(recent.visibleProperty());
    return new VBox(8, choice, scroll, recent);
  }

  /** The popup's content, refreshed (for snapshots in UI tests). */
  VBox popupContent() {
    refreshPalettes();
    return popupBox;
  }

  /** Reloads the palette list (files may have changed) and shows the current palette. */
  private void refreshPalettes() {
    String current = Palettes.current(projectRoot);
    refreshing = true;
    paletteBox.getItems().setAll(Palettes.available(projectRoot));
    paletteBox.getItems().stream().filter(ref -> ref.id().equals(current)).findFirst()
        .ifPresent(paletteBox::setValue);
    refreshing = false;
    showPalette(current);
    recentRow.getChildren().clear();
    for (Color color : Palettes.recent(projectRoot)) {
      recentRow.getChildren().add(swatchButton(color, null, () -> setValue(color)));
    }
  }

  private void showPalette(String id) {
    palette = Palettes.load(projectRoot, id);
    defaultToggle.setSelected(projectRoot != null && Palettes.projectDefault(projectRoot).equals(id));
    int columns = palette.columns() > 0 ? palette.columns() : 12;
    swatches.setPrefColumns(Math.min(columns, MAX_COLUMNS));
    swatches.setPrefRows(Math.max(1,
        (palette.entries().size() + swatches.getPrefColumns() - 1) / swatches.getPrefColumns()));
    swatches.getChildren().clear();
    for (GimpPalette.Entry entry : palette.entries()) {
      Color color = Palettes.color(entry);
      // a palette colour keeps the transparency set with the slider (.gpl has no alpha)
      swatches.getChildren().add(swatchButton(color,
          entry.name().isEmpty() ? entry.hex() : entry.name() + "  " + entry.hex(),
          () -> setValue(Color.color(color.getRed(), color.getGreen(), color.getBlue(),
              getValue().getOpacity()))));
    }
  }

  private static Button swatchButton(Color color, String tooltip, Runnable action) {
    Button button = new Button();
    Rectangle chip = new Rectangle(16, 16, color);
    chip.setStroke(Color.gray(0.6));
    button.setGraphic(chip);
    button.getStyleClass().addAll("flat", "swatch-button");
    button.setCursor(javafx.scene.Cursor.HAND);
    if (tooltip != null) {
      button.setTooltip(new Tooltip(tooltip));
    }
    button.setOnAction(e -> action.run());
    return button;
  }

  /** A picked colour that is not in the palette shows under Recent next time. */
  private void rememberIfCustom(Color before) {
    Color color = getValue();
    if (color.equals(before)) {
      return;
    }
    GimpPalette.Entry picked = Palettes.entry(color);
    boolean inPalette = palette != null && palette.entries().stream()
        .anyMatch(e -> e.rgb() == picked.rgb());
    if (!inPalette) {
      Palettes.remember(projectRoot, color);
    }
  }

  /**
   * Opens the picker's popup below {@code owner} (e.g. a colour swatch in the
   * code); {@code onCommit} gets the colour once, on Enter or when the popup
   * closes (Escape cancels), so a whole pick is one change (one undo step).
   */
  void openAt(javafx.scene.Node owner, java.util.function.Consumer<Color> onCommit) {
    Color initial = getValue();
    boolean[] cancelled = {false};
    popupBox.setOnKeyPressed(e -> {
      if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
        popup.hide();
        e.consume();
      } else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
        cancelled[0] = true;
        popup.hide();
        e.consume();
      }
    });
    popup.setOnHidden(e -> {
      if (!cancelled[0] && !getValue().equals(initial)) {
        rememberIfCustom(initial);
        onCommit.accept(getValue());
      }
    });
    refreshPalettes();
    popup.setHideOnEscape(false);
    if (owner.getScene() != null) {
      popupBox.getStylesheets().setAll(owner.getScene().getStylesheets());
    }
    var bounds = owner.localToScreen(owner.getBoundsInLocal());
    // owned by the window: the owner node may be rebuilt while picking (a gutter
    // swatch is redrawn with every new colour), which would close the popup
    popup.show(owner.getScene().getWindow(), bounds.getMinX(), bounds.getMaxY() + 4);
  }

  Color getValue() {
    return value.get();
  }

  void setValue(Color color) {
    value.set(color);
    sync(color); // also when equal, so the sliders match after construction
  }

  ObjectProperty<Color> valueProperty() {
    return value;
  }

  private void sync(Color color) {
    syncing = true;
    if (color.getSaturation() > 0 && color.getBrightness() > 0) {
      hue.setValue(color.getHue() / 3.6);
    }
    saturation.setValue(color.getSaturation() * 100);
    brightness.setValue(color.getBrightness() * 100);
    transparency.setValue((1 - color.getOpacity()) * 100);
    syncing = false;
    swatch.setFill(color);
    paintTracks();
  }

  /** Each track shows the colours its slider would give, like Scratch. */
  private void paintTracks() {
    double h = hue.getValue() * 3.6;
    double s = saturation.getValue() / 100;
    double b = brightness.getValue() / 100;
    StringBuilder hues = new StringBuilder();
    for (int i = 0; i <= 6; i++) {
      hues.append(i == 0 ? "" : ", ").append(web(Color.hsb(i * 60, Math.max(s, 0.2),
          Math.max(b, 0.2))));
    }
    track(hueTrack, hues.toString());
    track(saturationTrack, web(Color.hsb(h, 0, b)) + ", " + web(Color.hsb(h, 1, b)));
    track(brightnessTrack, web(Color.hsb(h, s, 0)) + ", " + web(Color.hsb(h, s, 1)));
    track(transparencyTrack, web(Color.hsb(h, s, b)) + ", " + web(Color.hsb(h, s, b, 0)));
  }

  private static void track(Region region, String stops) {
    region.setStyle("-fx-background-radius: 7; -fx-background-color: linear-gradient(to right, "
        + stops + ");");
  }

  private static String web(Color c) {
    return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
        (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
        (int) Math.round(c.getBlue() * 255), c.getOpacity());
  }

  private static Slider slider() {
    Slider slider = new Slider(0, 100, 0);
    slider.setPrefWidth(220);
    slider.getStyleClass().add("color-slider");
    return slider;
  }

  private static void addRow(GridPane grid, int row, String key, Slider slider, Region track) {
    track.setPrefHeight(14);
    track.setMaxHeight(14);
    StackPane stack = new StackPane(track, slider);
    stack.setAlignment(Pos.CENTER);
    Label label = new Label(I18n.t(key));
    Label number = new Label();
    number.setMinWidth(28);
    number.textProperty().bind(slider.valueProperty().asString("%.0f"));
    grid.addRow(row, label, stack, number);
  }
}
