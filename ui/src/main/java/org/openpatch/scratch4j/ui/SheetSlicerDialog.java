package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.SpriteAssets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The sprite-sheet slicer: choose the tile size over a grid preview, then
 * either every tile becomes a costume ({@code this.addCostumes(prefix, sheet, w, h);})
 * or one row becomes an animation
 * ({@code this.addAnimation(name, sheet, frames, w, h, row);}) with a live preview.
 * The line goes into the chosen sprite class's managed setup region.
 */
final class SheetSlicerDialog {

  private static final double PREVIEW_WIDTH = 460;
  private static final double PREVIEW_HEIGHT = 300;

  private SheetSlicerDialog() {}

  /** Shows the slicer for {@code sheet} (project-relative {@code reference}). */
  static void show(ScratchProject project, Image sheet, String reference,
      Consumer<Path> onWritten) {
    int sheetWidth = (int) sheet.getWidth();
    int sheetHeight = (int) sheet.getHeight();
    int[] guess = guessTile(sheetWidth, sheetHeight);

    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle(I18n.t("slicer.title"));
    dialog.setHeaderText(I18n.t("slicer.header", reference));
    dialog.getDialogPane().getButtonTypes().setAll(I18n.ok(), I18n.cancel());
    Theme.style(dialog);

    Spinner<Integer> tileWidth = new Spinner<>(1, Math.max(1, sheetWidth), guess[0]);
    Spinner<Integer> tileHeight = new Spinner<>(1, Math.max(1, sheetHeight), guess[1]);
    for (Spinner<Integer> spinner : List.of(tileWidth, tileHeight)) {
      spinner.setEditable(true);
      spinner.setPrefWidth(96);
    }
    ToggleGroup mode = new ToggleGroup();
    RadioButton costumes = new RadioButton(I18n.t("slicer.costumes"));
    RadioButton animation = new RadioButton(I18n.t("slicer.animation"));
    costumes.setToggleGroup(mode);
    animation.setToggleGroup(mode);
    costumes.setSelected(true);
    TextField name = new TextField(baseName(reference));
    Spinner<Integer> row = new Spinner<>(0, 999, 0);
    Spinner<Integer> frames = new Spinner<>(1, 999, Math.max(1, sheetWidth / guess[0]));
    row.setPrefWidth(96);
    frames.setPrefWidth(96);
    row.setEditable(true);
    frames.setEditable(true);
    ChoiceBox<String> target = new ChoiceBox<>();
    Label targetHint = new Label();
    targetHint.getStyleClass().add("text-muted");
    targetHint.setWrapText(true);

    Canvas preview = new Canvas(PREVIEW_WIDTH, PREVIEW_HEIGHT);
    Canvas playback = new Canvas(120, 120);
    int[] frame = {0};

    Runnable redraw = () -> {
      boolean anim = animation.isSelected();
      int w = tileWidth.getValue();
      int h = tileHeight.getValue();
      int columns = Math.max(1, sheetWidth / w);
      int rows = Math.max(1, sheetHeight / h);
      if (row.getValue() >= rows) {
        row.getValueFactory().setValue(rows - 1);
      }
      if (frames.getValue() > columns) {
        frames.getValueFactory().setValue(columns);
      }
      double scale = Math.min(PREVIEW_WIDTH / sheetWidth, PREVIEW_HEIGHT / sheetHeight);
      GraphicsContext g = preview.getGraphicsContext2D();
      g.clearRect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT);
      g.setImageSmoothing(scale < 1);
      g.drawImage(sheet, 0, 0, sheetWidth * scale, sheetHeight * scale);
      if (anim) {
        g.setFill(Color.rgb(133, 92, 214, 0.25));
        g.fillRect(0, row.getValue() * h * scale, frames.getValue() * w * scale, h * scale);
      }
      g.setStroke(Color.rgb(133, 92, 214, 0.9));
      g.setLineWidth(1);
      for (int c = 0; c <= columns; c++) {
        g.strokeLine(c * w * scale + 0.5, 0, c * w * scale + 0.5, rows * h * scale);
      }
      for (int r = 0; r <= rows; r++) {
        g.strokeLine(0, r * h * scale + 0.5, columns * w * scale, r * h * scale + 0.5);
      }
      String label = anim ? I18n.t("slicer.animation.summary", frames.getValue(), row.getValue())
          : I18n.t("slicer.costumes.summary", columns * rows, name.getText().trim());
      targetHint.setText(label);
      row.setDisable(!anim);
      frames.setDisable(!anim);
      List<String> classes = spriteClasses(project, anim);
      String previous = target.getValue();
      target.getItems().setAll(classes);
      target.setValue(classes.contains(previous) ? previous
          : classes.isEmpty() ? null : classes.get(0));
      dialog.getDialogPane().lookupButton(I18n.ok()).setDisable(classes.isEmpty()
          || name.getText().isBlank());
      if (classes.isEmpty()) {
        targetHint.setText(I18n.t(anim ? "slicer.no.animated" : "designer.no.sprites"));
      }
    };
    Timeline play = new Timeline(new KeyFrame(Duration.millis(120), e -> {
      int w = tileWidth.getValue();
      int h = tileHeight.getValue();
      int count = animation.isSelected() ? frames.getValue()
          : Math.max(1, sheetWidth / w) * Math.max(1, sheetHeight / h);
      frame[0] = (frame[0] + 1) % Math.max(1, count);
      int columns = Math.max(1, sheetWidth / w);
      int sx = animation.isSelected() ? frame[0] * w : (frame[0] % columns) * w;
      int sy = animation.isSelected() ? row.getValue() * h : (frame[0] / columns) * h;
      GraphicsContext g = playback.getGraphicsContext2D();
      g.clearRect(0, 0, 120, 120);
      double s = Math.min(120.0 / w, 120.0 / h);
      g.setImageSmoothing(false);
      g.drawImage(sheet, sx, sy, w, h, (120 - w * s) / 2, (120 - h * s) / 2, w * s, h * s);
    }));
    play.setCycleCount(Timeline.INDEFINITE);
    play.play();
    dialog.setOnHidden(e -> play.stop());

    tileWidth.valueProperty().addListener((o, a, b) -> redraw.run());
    tileHeight.valueProperty().addListener((o, a, b) -> redraw.run());
    row.valueProperty().addListener((o, a, b) -> redraw.run());
    frames.valueProperty().addListener((o, a, b) -> redraw.run());
    mode.selectedToggleProperty().addListener((o, a, b) -> {
      if (animation.isSelected() && name.getText().equals(baseName(reference))) {
        name.setText("walk");
      }
      redraw.run();
    });
    name.textProperty().addListener((o, a, b) -> redraw.run());

    GridPane form = new GridPane();
    form.setHgap(8);
    form.setVgap(8);
    form.addRow(0, new Label(I18n.t("slicer.tile")), new HBox(6, tileWidth, new Label("×"),
        tileHeight));
    form.addRow(1, new Label(I18n.t("slicer.use")), new HBox(12, costumes, animation));
    form.addRow(2, new Label(I18n.t("slicer.name")), name);
    form.addRow(3, new Label(I18n.t("slicer.row")), new HBox(6, row,
        new Label(I18n.t("slicer.frames")), frames));
    form.addRow(4, new Label(I18n.t("slicer.target")), target);
    VBox right = new VBox(8, form, targetHint, playback);
    right.setPrefWidth(320);
    HBox content = new HBox(16, preview, right);
    content.setPadding(new Insets(8));
    dialog.getDialogPane().setContent(content);
    redraw.run();

    dialog.showAndWait().filter(button -> button == I18n.ok()).ifPresent(button -> {
      String spriteClass = target.getValue();
      if (spriteClass == null) {
        return;
      }
      Path classFile = project.root().resolve(spriteClass + ".java");
      try {
        String source = Files.readString(classFile, StandardCharsets.UTF_8);
        String updated = animation.isSelected()
            ? SpriteAssets.addSheetAnimation(source, name.getText().trim(), reference,
                frames.getValue(), tileWidth.getValue(), tileHeight.getValue(), row.getValue())
            : SpriteAssets.addSheetCostumes(source, name.getText().trim(), reference,
                tileWidth.getValue(), tileHeight.getValue());
        LocalHistory.writeString(project.root(), classFile, updated);
        onWritten.accept(classFile);
      } catch (IOException | RuntimeException e) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.ERROR, e.getMessage(), I18n.ok());
        alert.setHeaderText(null);
        Theme.style(alert);
        alert.showAndWait();
      }
    });
  }

  /** Square tiles when the sheet is a strip of them, else the whole sheet. */
  static int[] guessTile(int width, int height) {
    if (width > height && width % height == 0) {
      return new int[] {height, height};
    }
    if (height > width && height % width == 0) {
      return new int[] {width, width};
    }
    for (int size : new int[] {128, 64, 48, 32, 16}) {
      if (width % size == 0 && height % size == 0 && width / size * (height / size) > 1) {
        return new int[] {size, size};
      }
    }
    return new int[] {width, height};
  }

  private static String baseName(String reference) {
    String file = reference.substring(reference.lastIndexOf('/') + 1);
    String base = file.replaceAll("\\.[A-Za-z]+$", "").replaceAll("[^A-Za-z0-9_]", "");
    return base.isEmpty() ? "tile" : base;
  }

  /** Sprite classes of the project; for an animation only AnimatedSprite subclasses. */
  private static List<String> spriteClasses(ScratchProject project, boolean animatedOnly) {
    try {
      return project.spriteClasses().stream().filter(name -> {
        if (!animatedOnly) {
          return true;
        }
        try {
          return Pattern.compile("\\bclass\\s+" + Pattern.quote(name)
              + "\\s+extends\\s+AnimatedSprite\\b").matcher(Files.readString(
                  project.root().resolve(name + ".java"))).find();
        } catch (IOException e) {
          return false;
        }
      }).toList();
    } catch (IOException e) {
      return List.of();
    }
  }
}
