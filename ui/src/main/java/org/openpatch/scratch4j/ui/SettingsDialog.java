package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;
import java.util.function.Consumer;

/**
 * View > Settings: everything about how the IDE looks in one place instead of
 * a View menu full of toggles. Each change applies at once; a new language
 * rebuilds the window, so it applies when the dialog closes.
 */
final class SettingsDialog {

  private static final List<Double> SCALES = List.of(1.0, 1.25, 1.5);

  private SettingsDialog() {}

  static void show(Window owner, Scene scene, Consumer<I18n.Language> switchLanguage,
      Consumer<Boolean> compatibilityGraphics) {
    Dialog<Void> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(I18n.t("settings.title"));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().add(I18n.close());

    ToggleGroup themes = new ToggleGroup();
    RadioButton light = new RadioButton(I18n.t("settings.theme.light"));
    RadioButton dark = new RadioButton(I18n.t("settings.theme.dark"));
    light.setToggleGroup(themes);
    dark.setToggleGroup(themes);
    (Theme.isDark() ? dark : light).setSelected(true);
    themes.selectedToggleProperty().addListener((o, old, now) -> {
      if (now != null && (now == dark) != Theme.isDark()) Theme.toggle(scene);
    });
    CheckBox contrast = new CheckBox(I18n.t("menu.view.contrast"));
    contrast.setId("settings-contrast");
    contrast.setSelected(Theme.isHighContrast());
    contrast.setOnAction(e -> Theme.setHighContrast(scene, contrast.isSelected()));

    ComboBox<Double> scale = new ComboBox<>();
    scale.getItems().setAll(SCALES);
    scale.setConverter(new StringConverter<>() {
      @Override public String toString(Double size) {
        return size == null ? "" : Math.round(size * 100) + " %";
      }

      @Override public Double fromString(String s) {
        return null;
      }
    });
    scale.setValue(SCALES.stream().filter(s -> Math.abs(s - Theme.scale()) < 0.01)
        .findFirst().orElse(1.0));
    scale.valueProperty().addListener((o, old, size) -> Theme.setScale(scene, size));

    Spinner<Integer> codeSize = new Spinner<>(9, 36, CodeEditor.FONT_SIZE.get());
    codeSize.setId("settings-code-size");
    codeSize.setEditable(true);
    codeSize.setPrefWidth(90);
    codeSize.valueProperty().addListener((o, old, size) -> CodeEditor.FONT_SIZE.set(size));

    ComboBox<I18n.Language> language = new ComboBox<>();
    language.getItems().setAll(I18n.Language.values());
    language.setConverter(new StringConverter<>() {
      @Override public String toString(I18n.Language l) {
        return l == null ? "" : l == I18n.Language.DE ? "Deutsch" : "English";
      }

      @Override public I18n.Language fromString(String s) {
        return null;
      }
    });
    language.setValue(I18n.current());

    CheckBox graphics = new CheckBox(I18n.t("menu.view.compatgraphics"));
    graphics.setSelected(Prefs.compatibilityGraphics());
    graphics.setOnAction(e -> compatibilityGraphics.accept(graphics.isSelected()));
    Label graphicsHint = new Label(I18n.t("settings.graphics.hint"));
    graphicsHint.getStyleClass().add("text-muted");
    graphicsHint.setWrapText(true);
    graphicsHint.setMaxWidth(320);

    GridPane grid = new GridPane();
    grid.setHgap(12);
    grid.setVgap(12);
    HBox themeRow = new HBox(12, light, dark);
    themeRow.setAlignment(Pos.CENTER_LEFT);
    grid.addRow(0, new Label(I18n.t("settings.theme")), themeRow);
    grid.addRow(1, new Label(""), contrast);
    grid.addRow(2, new Label(I18n.t("menu.view.textsize")), scale);
    grid.addRow(3, new Label(I18n.t("settings.codesize")), codeSize);
    grid.addRow(4, new Label(I18n.t("menu.language")), language);
    grid.addRow(5, new Label(I18n.t("settings.graphics")), graphics);
    grid.add(graphicsHint, 1, 6);
    dialog.getDialogPane().setContent(grid);
    dialog.showAndWait();
    if (language.getValue() != I18n.current()) switchLanguage.accept(language.getValue());
  }
}
