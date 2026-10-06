package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.region.SpriteAssets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A sprite sheet with a grid: set the tile size, then pick cells. As
 * costumes, any cells ({@code addCostume(name, sheet, x, y, w, h)} each). As an
 * animation, the frames of one row or one column from its first cell (what
 * the library's {@code addAnimation(name, sheet, frames, w, h, row)} and
 * {@code (..., column, true)} play): a click on a cell selects up to it.
 */
final class SheetGridDialog {

  /** Whether the dialog makes costumes or an animation. */
  enum Mode { COSTUMES, ANIMATION }

  private static final double VIEW = 460;

  private final Image sheet;
  private final String reference;
  private final Spinner<Integer> tileWidth;
  private final Spinner<Integer> tileHeight;
  private final TextField name = new TextField();
  private final RadioButton inRow = new RadioButton(I18n.t("sheetgrid.row"));
  private final RadioButton inColumn = new RadioButton(I18n.t("sheetgrid.column"));
  private final Canvas canvas = new Canvas();
  private final Canvas preview = new Canvas(140, 140);
  private final Label code = new Label();
  private final Mode mode;
  /** Selected cells as {column, row}, in selection order. */
  private final List<int[]> cells = new ArrayList<>();
  private int frame;

  private SheetGridDialog(Image sheet, String reference, Mode mode, SpriteAssets.Entry entry) {
    this.sheet = sheet;
    this.reference = reference;
    this.mode = mode;
    int[] guess = SheetSlicerDialog.guessTile((int) sheet.getWidth(), (int) sheet.getHeight());
    int w = entry != null && entry.tileWidth() > 0 ? entry.tileWidth() : guess[0];
    int h = entry != null && entry.tileHeight() > 0 ? entry.tileHeight() : guess[1];
    tileWidth = new Spinner<>(1, Math.max(1, (int) sheet.getWidth()), w);
    tileHeight = new Spinner<>(1, Math.max(1, (int) sheet.getHeight()), h);
    for (Spinner<Integer> s : List.of(tileWidth, tileHeight)) {
      s.setEditable(true);
      s.setPrefWidth(90);
    }
    ToggleGroup direction = new ToggleGroup();
    inRow.setToggleGroup(direction);
    inColumn.setToggleGroup(direction);
    inRow.setSelected(true);
    name.setText(entry != null ? entry.name() : mode == Mode.ANIMATION ? "walk" : baseName());
    // the entry being edited: its cells are selected
    if (entry != null && entry.kind() == SpriteAssets.Kind.ANIMATION) {
      inColumn.setSelected(entry.columns());
      for (int i = 0; i < entry.frames(); i++) {
        cells.add(entry.columns() ? new int[] {entry.row(), i} : new int[] {i, entry.row()});
      }
    } else if (entry != null && entry.isSheetCostume()) {
      cells.add(new int[] {entry.cropX() / w, entry.cropY() / h});
    }
  }

  /** The asset calls for the chosen cells, or empty when cancelled. */
  static Optional<List<String>> show(Image sheet, String reference, Mode mode,
      SpriteAssets.Entry entry) {
    SheetGridDialog grid = new SheetGridDialog(sheet, reference, mode, entry);
    return grid.dialog().showAndWait().filter(b -> b == I18n.ok())
        .map(b -> grid.statements());
  }

  /** The dialog without showing it (tests take pictures of it). */
  Dialog<ButtonType> dialog() {
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle(I18n.t(mode == Mode.ANIMATION ? "sheetgrid.title.animation"
        : "sheetgrid.title.costumes"));
    dialog.setHeaderText(I18n.t(mode == Mode.ANIMATION ? "sheetgrid.hint.animation"
        : "sheetgrid.hint.costumes"));
    dialog.getDialogPane().getButtonTypes().setAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);

    canvas.setOnMousePressed(e -> click(e.getX(), e.getY()));
    canvas.setOnMouseDragged(e -> {
      if (mode == Mode.ANIMATION) click(e.getX(), e.getY());
    });
    tileWidth.valueProperty().addListener((o, a, b) -> gridChanged());
    tileHeight.valueProperty().addListener((o, a, b) -> gridChanged());
    inRow.setOnAction(e -> gridChanged());
    inColumn.setOnAction(e -> gridChanged());
    name.textProperty().addListener((o, a, b) -> update());

    GridPane form = new GridPane();
    form.setHgap(8);
    form.setVgap(8);
    Label tileLabel = new Label(I18n.t("slicer.tile"));
    Label nameLabel = new Label(I18n.t("slicer.name"));
    Label framesLabel = new Label(I18n.t("sheetgrid.frames"));
    for (Label l : List.of(tileLabel, nameLabel, framesLabel)) {
      l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    }
    form.addRow(0, tileLabel, new HBox(6, tileWidth, new Label("\u00d7"), tileHeight));
    form.addRow(1, nameLabel, name);
    if (mode == Mode.ANIMATION) {
      inRow.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
      inColumn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
      form.addRow(2, framesLabel, new VBox(6, inRow, inColumn));
    }
    code.setWrapText(true);
    code.getStyleClass().add("sheetgrid-code");
    code.setMaxWidth(360);
    VBox right = new VBox(10, form, preview, code);
    right.setMinWidth(360);
    ScrollPane scroll = new ScrollPane(canvas);
    scroll.setPrefViewportWidth(VIEW);
    scroll.setPrefViewportHeight(VIEW * 0.75);
    HBox content = new HBox(16, scroll, right);
    content.setPadding(new Insets(8));
    dialog.getDialogPane().setContent(content);

    Timeline play = new Timeline(new KeyFrame(Duration.millis(140), e -> {
      frame = cells.isEmpty() ? 0 : (frame + 1) % cells.size();
      drawPreview();
    }));
    play.setCycleCount(Timeline.INDEFINITE);
    play.play();
    dialog.setOnHidden(e -> play.stop());
    dialog.setResultConverter(b -> b);
    gridChanged();
    dialog.getDialogPane().lookupButton(I18n.ok()).disableProperty().bind(
        javafx.beans.binding.Bindings.createBooleanBinding(
            () -> cells.isEmpty() || !name.getText().trim().matches("[A-Za-z][A-Za-z0-9_-]*"),
            name.textProperty(), canvas.widthProperty(), code.textProperty()));
    return dialog;
  }

  private int columns() {
    return Math.max(1, (int) sheet.getWidth() / tileWidth.getValue());
  }

  private int rows() {
    return Math.max(1, (int) sheet.getHeight() / tileHeight.getValue());
  }

  private double scale() {
    return Math.max(1, Math.min(4, Math.floor(VIEW / Math.max(sheet.getWidth(),
        sheet.getHeight()) * 2) / 2));
  }

  /** A click on a cell: toggle it (costumes) or select the frames up to it (animation). */
  void click(double x, double y) {
    double s = scale();
    int c = (int) (x / s / tileWidth.getValue());
    int r = (int) (y / s / tileHeight.getValue());
    if (c < 0 || r < 0 || c >= columns() || r >= rows()) return;
    if (mode == Mode.ANIMATION) {
      cells.clear();
      if (inColumn.isSelected()) {
        for (int i = 0; i <= r; i++) cells.add(new int[] {c, i});
      } else {
        for (int i = 0; i <= c; i++) cells.add(new int[] {i, r});
      }
    } else {
      int[] cell = {c, r};
      boolean removed = cells.removeIf(k -> k[0] == c && k[1] == r);
      if (!removed) cells.add(cell);
    }
    frame = 0;
    update();
  }

  /** Cells (tests): {column, row} in order. */
  List<int[]> cells() {
    return cells;
  }

  private void gridChanged() {
    // cells outside the new grid go; an animation keeps its row/column shape
    cells.removeIf(k -> k[0] >= columns() || k[1] >= rows());
    if (mode == Mode.ANIMATION && !cells.isEmpty()) {
      int[] last = cells.get(cells.size() - 1);
      double s = scale();
      click((last[0] + 0.5) * tileWidth.getValue() * s, (last[1] + 0.5) * tileHeight.getValue()
          * s);
      return;
    }
    update();
  }

  private void update() {
    double s = scale();
    canvas.setWidth(sheet.getWidth() * s);
    canvas.setHeight(sheet.getHeight() * s);
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    g.setImageSmoothing(false);
    g.drawImage(sheet, 0, 0, canvas.getWidth(), canvas.getHeight());
    double w = tileWidth.getValue() * s;
    double h = tileHeight.getValue() * s;
    int n = 0;
    for (int[] k : cells) {
      g.setFill(Color.rgb(133, 92, 214, 0.32));
      g.fillRect(k[0] * w, k[1] * h, w, h);
      g.setFill(Color.web("#3d2a6b"));
      g.fillText(String.valueOf(++n), k[0] * w + 4, k[1] * h + 14);
    }
    g.setStroke(Color.rgb(133, 92, 214, 0.9));
    g.setLineWidth(1);
    for (int c = 0; c <= columns(); c++) g.strokeLine(c * w + 0.5, 0, c * w + 0.5, rows() * h);
    for (int r = 0; r <= rows(); r++) g.strokeLine(0, r * h + 0.5, columns() * w, r * h + 0.5);
    code.setText(cells.isEmpty() ? I18n.t("sheetgrid.none")
        : String.join("\n", statements()));
    drawPreview();
  }

  private void drawPreview() {
    GraphicsContext g = preview.getGraphicsContext2D();
    g.clearRect(0, 0, 140, 140);
    if (cells.isEmpty()) return;
    int[] k = cells.get(Math.min(frame, cells.size() - 1));
    int w = tileWidth.getValue();
    int h = tileHeight.getValue();
    double s = Math.min(140.0 / w, 140.0 / h);
    g.setImageSmoothing(false);
    g.drawImage(sheet, k[0] * w, k[1] * h, w, h, (140 - w * s) / 2, (140 - h * s) / 2, w * s,
        h * s);
  }

  /** The code for the selected cells. */
  List<String> statements() {
    String n = name.getText().trim();
    int w = tileWidth.getValue();
    int h = tileHeight.getValue();
    List<String> out = new ArrayList<>();
    if (cells.isEmpty()) return out;
    if (mode == Mode.ANIMATION) {
      int[] first = cells.get(0);
      if (inColumn.isSelected()) {
        out.add("this.addAnimation(\"" + n + "\", \"" + reference + "\", " + cells.size() + ", "
            + w + ", " + h + ", " + first[0] + ", true);");
      } else {
        out.add("this.addAnimation(\"" + n + "\", \"" + reference + "\", " + cells.size() + ", "
            + w + ", " + h + (first[1] > 0 ? ", " + first[1] : "") + ");");
      }
      return out;
    }
    for (int i = 0; i < cells.size(); i++) {
      int[] k = cells.get(i);
      out.add("this.addCostume(\"" + n + (i == 0 ? "" : String.valueOf(i + 1)) + "\", \""
          + reference + "\", " + k[0] * w + ", " + k[1] * h + ", " + w + ", " + h + ");");
    }
    return out;
  }

  private String baseName() {
    String file = reference.substring(reference.lastIndexOf('/') + 1);
    String base = file.replaceAll("\\.[A-Za-z]+$", "").replaceAll("[^A-Za-z0-9_]", "");
    return base.isEmpty() ? "tile" : base;
  }
}
