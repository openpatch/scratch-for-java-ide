package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * View > Settings: the user's own preferences in one place instead of menus
 * full of toggles, in tabs for how the IDE looks, how the editor behaves and
 * where files go. Each change applies at once; a new language rebuilds the
 * window, so it applies when the dialog closes. What a teacher decides for a
 * project (the beginner interface) stays in the project.
 */
final class SettingsDialog {

  private static final List<Double> SCALES = List.of(1.0, 1.25, 1.5);

  private SettingsDialog() {}

  static void show(Window owner, Scene scene, Consumer<I18n.Language> switchLanguage,
      Consumer<Boolean> compatibilityGraphics, Runnable blocksChanged) {
    Dialog<Void> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(I18n.t("settings.title"));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().add(I18n.close());

    ComboBox<I18n.Language> language = combo(List.of(I18n.Language.values()), I18n.current(),
        l -> l == I18n.Language.DE ? "Deutsch" : "English");
    TabPane tabs = new TabPane(
        tab("appearance", appearance(scene, language, compatibilityGraphics)),
        tab("editor", editor(blocksChanged)),
        tab("files", files(dialog)));
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    tabs.setId("settings-tabs");
    dialog.getDialogPane().setContent(tabs);
    dialog.showAndWait();
    if (language.getValue() != I18n.current()) switchLanguage.accept(language.getValue());
  }

  private static GridPane appearance(Scene scene, ComboBox<I18n.Language> language,
      Consumer<Boolean> compatibilityGraphics) {
    ToggleGroup themes = new ToggleGroup();
    HBox themeRow = new HBox(12);
    themeRow.setAlignment(Pos.CENTER_LEFT);
    for (Prefs.ThemeMode mode : Prefs.ThemeMode.values()) {
      RadioButton choice = new RadioButton(I18n.t("settings.theme." + mode.name().toLowerCase()));
      choice.setId("settings-theme-" + mode.name().toLowerCase());
      choice.setUserData(mode);
      choice.setToggleGroup(themes);
      choice.setSelected(mode == Theme.mode());
      themeRow.getChildren().add(choice);
    }
    themes.selectedToggleProperty().addListener((o, old, now) -> {
      if (now != null) Theme.setMode(scene, (Prefs.ThemeMode) now.getUserData());
    });
    CheckBox contrast = new CheckBox(I18n.t("menu.view.contrast"));
    contrast.setId("settings-contrast");
    contrast.setSelected(Theme.isHighContrast());
    contrast.setOnAction(e -> Theme.setHighContrast(scene, contrast.isSelected()));

    ComboBox<Double> scale = combo(SCALES, SCALES.stream()
        .filter(s -> Math.abs(s - Theme.scale()) < 0.01).findFirst().orElse(1.0),
        size -> Math.round(size * 100) + " %");
    scale.valueProperty().addListener((o, old, size) -> Theme.setScale(scene, size));

    ComboBox<Prefs.CodeFont> codeFont = combo(List.of(Prefs.CodeFont.values()), Theme.codeFont(),
        font -> "Monaspace " + font.label());
    codeFont.setId("settings-code-font");
    // each entry in its own family, so the choice is seen, not only named
    codeFont.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
      @Override protected void updateItem(Prefs.CodeFont font, boolean empty) {
        super.updateItem(font, empty);
        setText(empty || font == null ? null : "Monaspace " + font.label());
        setStyle(empty || font == null ? ""
            : "-fx-font-family: \"" + Theme.codeFamily(font) + "\";");
      }
    });
    codeFont.valueProperty().addListener((o, old, font) -> Theme.setCodeFont(scene, font));

    Spinner<Integer> codeSize = new Spinner<>(9, 36, CodeEditor.FONT_SIZE.get());
    codeSize.setId("settings-code-size");
    codeSize.setEditable(true);
    codeSize.setPrefWidth(90);
    codeSize.valueProperty().addListener((o, old, size) -> CodeEditor.FONT_SIZE.set(size));

    CheckBox graphics = new CheckBox(I18n.t("menu.view.compatgraphics"));
    graphics.setSelected(Prefs.compatibilityGraphics());
    graphics.setOnAction(e -> compatibilityGraphics.accept(graphics.isSelected()));

    GridPane grid = grid();
    grid.addRow(0, new Label(I18n.t("settings.theme")), themeRow);
    grid.addRow(1, new Label(""), contrast);
    grid.addRow(2, new Label(I18n.t("menu.view.textsize")), scale);
    grid.addRow(3, new Label(I18n.t("settings.codefont")), codeFont);
    grid.addRow(4, new Label(I18n.t("settings.codesize")), codeSize);
    grid.addRow(5, new Label(I18n.t("menu.language")), language);
    grid.addRow(6, new Label(I18n.t("settings.graphics")), graphics);
    grid.add(hint(I18n.t("settings.graphics.hint")), 1, 7);
    return grid;
  }

  private static GridPane editor(Runnable blocksChanged) {
    ComboBox<Prefs.BlocksPanel> blocks = combo(List.of(Prefs.BlocksPanel.values()),
        Prefs.blocksPanel(), b -> I18n.t("settings.blocks." + b.name().toLowerCase()));
    blocks.setId("settings-blocks");
    blocks.valueProperty().addListener((o, old, shown) -> {
      Prefs.blocksPanel(shown);
      blocksChanged.run();
    });

    CheckBox pairs = new CheckBox(I18n.t("settings.pairs"));
    pairs.setId("settings-pairs");
    pairs.setSelected(CodeEditor.AUTO_PAIRS.get());
    pairs.setOnAction(e -> CodeEditor.AUTO_PAIRS.set(pairs.isSelected()));

    ComboBox<Prefs.Completion> completion = combo(List.of(Prefs.Completion.values()),
        CodeEditor.COMPLETION.get(), c -> I18n.t("settings.completion." + c.name().toLowerCase()));
    completion.setId("settings-completion");
    completion.valueProperty().addListener((o, old, mode) -> CodeEditor.COMPLETION.set(mode));

    GridPane grid = grid();
    grid.addRow(0, new Label(I18n.t("settings.blocks")), blocks);
    grid.addRow(1, new Label(I18n.t("settings.completion")), completion);
    grid.addRow(2, new Label(""), pairs);
    return grid;
  }

  private static GridPane files(Dialog<?> dialog) {
    TextField name = new TextField(Prefs.studentName());
    name.setId("settings-name");
    name.textProperty().addListener((o, old, text) -> Prefs.studentName(text.strip()));

    TextField projects = folderField(ProjectsFolder.current().toString());
    Button chooseProjects = new Button(I18n.t("settings.choose"));
    chooseProjects.setOnAction(e -> {
      File chosen = chooseFolder(dialog, I18n.t("settings.projects"), ProjectsFolder.current());
      if (chosen == null) return;
      ProjectsFolder.remember(chosen.toPath());
      projects.setText(ProjectsFolder.current().toString());
    });

    // a folder set by the school (SCRATCH4J_HANDIN_DIR) is not the user's to change
    String school = System.getenv("SCRATCH4J_HANDIN_DIR");
    boolean locked = school != null && !school.isBlank();
    TextField handIn = folderField(Prefs.handInFolder());
    handIn.setPromptText(I18n.t("settings.handin.ask"));
    Button chooseHandIn = new Button(I18n.t("settings.choose"));
    Button clearHandIn = new Button(I18n.t("settings.clear"));
    chooseHandIn.setOnAction(e -> {
      File chosen = chooseFolder(dialog, I18n.t("settings.handin.folder"),
          handIn.getText().isBlank() ? null : Path.of(handIn.getText()));
      if (chosen == null) return;
      Prefs.handInFolder(chosen.getAbsolutePath());
      handIn.setText(chosen.getAbsolutePath());
    });
    clearHandIn.setOnAction(e -> {
      Prefs.handInFolder("");
      handIn.clear();
    });
    chooseHandIn.setDisable(locked);
    clearHandIn.setDisable(locked);

    GridPane grid = grid();
    grid.addRow(0, new Label(I18n.t("settings.name")), name);
    grid.addRow(1, new Label(I18n.t("settings.projects")), row(projects, chooseProjects));
    grid.addRow(2, new Label(I18n.t("settings.handin.folder")),
        row(handIn, chooseHandIn, clearHandIn));
    if (locked) grid.add(hint(I18n.t("settings.handin.locked")), 1, 3);
    return grid;
  }

  private static Tab tab(String key, GridPane content) {
    Tab tab = new Tab(I18n.t("settings.tab." + key), content);
    tab.setId("settings-tab-" + key);
    return tab;
  }

  private static GridPane grid() {
    GridPane grid = new GridPane();
    grid.setHgap(12);
    grid.setVgap(12);
    grid.setPadding(new Insets(16, 4, 4, 4));
    return grid;
  }

  private static <T> ComboBox<T> combo(List<T> items, T value, Function<T, String> label) {
    ComboBox<T> combo = new ComboBox<>();
    combo.getItems().setAll(items);
    combo.setConverter(new StringConverter<>() {
      @Override public String toString(T item) {
        return item == null ? "" : label.apply(item);
      }

      @Override public T fromString(String s) {
        return null;
      }
    });
    combo.setValue(value);
    return combo;
  }

  private static Label hint(String text) {
    Label hint = new Label(text);
    hint.getStyleClass().add("text-muted");
    hint.setWrapText(true);
    hint.setMaxWidth(320);
    return hint;
  }

  private static TextField folderField(String folder) {
    TextField field = new TextField(folder);
    field.setEditable(false);
    field.setPrefColumnCount(22);
    HBox.setHgrow(field, Priority.ALWAYS);
    return field;
  }

  private static HBox row(javafx.scene.Node... nodes) {
    HBox row = new HBox(8, nodes);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  private static File chooseFolder(Dialog<?> dialog, String title, Path start) {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle(title);
    if (start != null && Files.isDirectory(start)) chooser.setInitialDirectory(start.toFile());
    return chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
  }
}
