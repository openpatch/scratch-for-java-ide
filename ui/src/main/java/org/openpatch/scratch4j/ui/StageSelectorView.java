package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.project.NewClass;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.project.ProjectSettings;
import org.openpatch.scratch4j.core.region.WindowDocument;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.region.StageDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The stage selector: every Stage subclass of the project as a card with a
 * thumbnail (backdrop + sprites, rendered like the designer). The star marks
 * the start stage (Run and Export use it); a click opens the designer.
 */
final class StageSelectorView extends BorderPane {

  private static final double THUMB_WIDTH = 200;

  private final ScratchProject project;
  private final Consumer<String> onOpenDesigner;
  private final Consumer<String> onOpenCode;
  private final Runnable onProjectChanged;
  private Consumer<String> onRun = name -> { };
  @FunctionalInterface interface StageChange {
    void apply(String oldName, String newName) throws IOException;
  }
  private StageChange onDuplicate = (oldName, newName) -> { };
  private StageChange onRename = (oldName, newName) -> { };
  private StageChange onDelete = (name, ignored) -> { };
  private Consumer<String> onInsertSwitch = snippet -> { };
  private final StageRenderer renderer;
  private final ListView<String> stages = new ListView<>();
  private final Label sizeWarning = new Label();
  private final Map<String, Size> sizes = new HashMap<>();
  private record Size(int width, int height) {}

  StageSelectorView(ScratchProject project, Consumer<String> onOpenDesigner,
      Consumer<String> onOpenCode, Runnable onProjectChanged) {
    this.project = project;
    this.onOpenDesigner = onOpenDesigner;
    this.onOpenCode = onOpenCode;
    this.onProjectChanged = onProjectChanged;
    this.renderer = new StageRenderer(project);
    getStyleClass().add("stage-selector");

    stages.getStyleClass().add("stage-list");
    stages.setCellFactory(view -> new StageCell());
    stages.setOnMouseClicked(e -> {
      String name = stages.getSelectionModel().getSelectedItem();
      if (name != null && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
        onOpenDesigner.accept(name);
      }
    });
    Label empty = new Label(I18n.t("stages.none"));
    empty.setWrapText(true);
    stages.setPlaceholder(empty);

    Button add = new Button(I18n.t("stages.new"), Icons.of("fth-plus"));
    add.setMaxWidth(Double.MAX_VALUE);
    add.getStyleClass().add("add-stage");
    add.setOnAction(e -> addStage());

    Button windowSettings = new Button(I18n.t("stages.window.settings"),
        Icons.of("fth-settings"));
    windowSettings.setMaxWidth(Double.MAX_VALUE);
    windowSettings.setOnAction(e -> showWindowSettings());

    setCenter(stages);
    sizeWarning.setWrapText(true);
    sizeWarning.getStyleClass().add("text-warning");
    sizeWarning.setVisible(false);
    sizeWarning.setManaged(false);
    setTop(sizeWarning);
    setBottom(new VBox(6, windowSettings, add));
    refresh();
  }

  /** "Run this stage" from a card's context menu (any stage, not only the start stage). */
  void setOnRun(Consumer<String> onRun) {
    this.onRun = onRun;
  }

  void setOnDuplicate(StageChange action) { onDuplicate = action; }
  void setOnRename(StageChange action) { onRename = action; }
  void setOnDelete(StageChange action) { onDelete = action; }
  void setOnInsertSwitch(Consumer<String> action) { onInsertSwitch = action; }

  /** Re-reads the stage list and redraws every thumbnail. */
  void refresh() {
    try {
      String selected = stages.getSelectionModel().getSelectedItem();
      stages.getItems().setAll(project.stageClasses());
      sizes.clear();
      for (String name : stages.getItems()) {
        Path source = project.root().resolve(name + ".java");
        if (Files.isRegularFile(source)) {
          try {
            StageDocument.declaredSize(Files.readString(source)).ifPresent(size ->
                sizes.put(name, new Size(size.width(), size.height())));
          } catch (IOException | RuntimeException ignored) {
            // hand-written stages can still be opened as code
          }
        }
      }
      Size start = sizes.get(startStage());
      if (start == null && !sizes.isEmpty()) {
        start = sizes.values().iterator().next();
      }
      boolean mismatch = false;
      if (start != null) {
        for (Size size : sizes.values()) {
          if (!size.equals(start)) {
            mismatch = true;
            break;
          }
        }
      }
      sizeWarning.setText(mismatch ? I18n.t("stages.size.warning") : "");
      sizeWarning.setVisible(mismatch);
      sizeWarning.setManaged(mismatch);
      if (selected != null && stages.getItems().contains(selected)) {
        stages.getSelectionModel().select(selected);
      }
      stages.refresh();
    } catch (IOException e) {
      stages.getItems().clear();
      sizes.clear();
      sizeWarning.setVisible(false);
      sizeWarning.setManaged(false);
    }
  }

  void select(String stageClass) {
    stages.getSelectionModel().select(stageClass);
  }

  /** The stage shown first (the window class's setStage when there is one). */
  private String startStage() {
    try {
      return project.firstStage();
    } catch (IOException e) {
      return "";
    }
  }

  private void setStart(String name) {
    try {
      project.setStartStage(name);
    } catch (IOException ignored) {
      // metadata is optional; Run falls back to auto-detection
    }
    refresh();
  }

  private final class StageCell extends ListCell<String> {

    @Override
    protected void updateItem(String name, boolean empty) {
      super.updateItem(name, empty);
      if (empty || name == null) {
        setText(null);
        setGraphic(null);
        setContextMenu(null);
        return;
      }
      boolean isStart = startStage().equals(name);
      ImageView thumb = new ImageView(renderer.thumbnail(name, THUMB_WIDTH));
      thumb.setPreserveRatio(true);
      // as wide as the sidebar allows (the list's padding and scroll bar aside)
      thumb.fitWidthProperty().bind(javafx.beans.binding.Bindings.min(THUMB_WIDTH,
          stages.widthProperty().subtract(44)));
      StackPane frame = new StackPane(thumb);
      frame.getStyleClass().add("stage-thumb");

      Label title = new Label(name);
      title.getStyleClass().add("stage-name");
      Button star = Icons.button("fth-star",
          isStart ? I18n.t("stages.start.is") : I18n.t("stages.start.make"),
          () -> setStart(name));
      star.getStyleClass().add(isStart ? "start-star" : "start-star-off");
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox head = new HBox(4, title, spacer, star);
      head.setAlignment(Pos.CENTER_LEFT);
      VBox card = new VBox(6, frame, head);
      Size size = sizes.get(name);
      if (size != null) {
        Label dimensions = new Label(size.width() + " × " + size.height());
        dimensions.getStyleClass().add("text-muted");
        card.getChildren().add(dimensions);
      }
      if (isStart) {
        Label badge = new Label(I18n.t("stages.start"), Icons.of("fth-flag"));
        badge.getStyleClass().add("start-badge");
        card.getChildren().add(badge);
      }
      card.getStyleClass().add("stage-card");
      setText(null);
      setGraphic(card);
      setTooltip(new Tooltip(I18n.t("stages.open.hint")));

      MenuItem design = new MenuItem(I18n.t("stages.open.designer"), Icons.of("fth-edit-3"));
      design.setOnAction(e -> onOpenDesigner.accept(name));
      MenuItem code = new MenuItem(I18n.t("designer.opencode"), Icons.of("fth-code"));
      code.setOnAction(e -> onOpenCode.accept(name));
      MenuItem start = new MenuItem(I18n.t("stages.start.make"), Icons.of("fth-star"));
      start.setOnAction(e -> setStart(name));
      start.setDisable(isStart);
      MenuItem run = new MenuItem(I18n.t("stages.run"), Icons.of("fth-flag"));
      run.setOnAction(e -> onRun.accept(name));
      MenuItem duplicate = new MenuItem(I18n.t("stages.duplicate"), Icons.of("fth-copy"));
      duplicate.setOnAction(e -> askName(name, I18n.t("stages.duplicate"),
          name + "Copy", onDuplicate));
      MenuItem rename = new MenuItem(I18n.t("stages.rename"), Icons.of("fth-edit-2"));
      rename.setOnAction(e -> askName(name, I18n.t("stages.rename"), name, onRename));
      MenuItem delete = new MenuItem(I18n.t("stages.delete"), Icons.of("fth-trash-2"));
      delete.setOnAction(e -> confirmDelete(name));
      javafx.scene.control.Menu switchMenu = new javafx.scene.control.Menu(
          I18n.t("stages.switch.insert"), Icons.of("fth-arrow-right-circle"));
      MenuItem immediate = new MenuItem(I18n.t("stages.switch.now"));
      immediate.setOnAction(e -> onInsertSwitch.accept(
          "Window.getInstance().setStage(new " + name + "());\n"));
      MenuItem transition = new MenuItem(I18n.t("stages.switch.transition"));
      transition.setOnAction(e -> onInsertSwitch.accept(
          "Window.getInstance().transitionToStage(new "
              + name + "(), 500);\n"));
      switchMenu.getItems().addAll(immediate, transition);
      setContextMenu(new ContextMenu(run, design, code, start,
          switchMenu, new javafx.scene.control.SeparatorMenuItem(),
          duplicate, rename, delete));
    }
  }

  private void askName(String oldName, String title, String suggestion, StageChange action) {
    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(suggestion);
    dialog.getDialogPane().getButtonTypes().setAll(I18n.ok(), I18n.cancel());
    dialog.setTitle(title);
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("stages.name.prompt"));
    Theme.style(dialog);
    dialog.showAndWait().ifPresent(raw -> {
      String name = raw.trim();
      if (name.isEmpty()) {
        return;
      }
      try {
        action.apply(oldName, name);
        refresh();
        stages.getSelectionModel().select(name);
        onOpenDesigner.accept(name);
      } catch (IOException e) {
        showError(e);
      }
    });
  }

  /** When set, the host asks (and deletes) instead of the plain confirmation here. */
  private java.util.function.Consumer<String> onDeleteRequest;

  void setOnDeleteRequest(java.util.function.Consumer<String> action) {
    onDeleteRequest = action;
  }

  private void confirmDelete(String name) {
    if (onDeleteRequest != null) {
      onDeleteRequest.accept(name);
      return;
    }
    javafx.scene.control.Alert dialog = new javafx.scene.control.Alert(
        javafx.scene.control.Alert.AlertType.CONFIRMATION,
        I18n.t("stages.delete.confirm", name), I18n.ok(), I18n.cancel());
    dialog.setHeaderText(null);
    Theme.style(dialog);
    if (dialog.showAndWait().orElse(null) != I18n.ok()) {
      return;
    }
    try {
      onDelete.apply(name, "");
      refresh();
    } catch (IOException e) {
      showError(e);
    }
  }

  private void showError(Exception e) {
    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
        javafx.scene.control.Alert.AlertType.ERROR, e.getMessage(), I18n.ok());
    alert.setHeaderText(null);
    alert.show();
  }

  /**
   * Global settings (full screen, pixel art, splash, debug overlay, window
   * size). With a window class they are code in its managed regions — the
   * program's entry point; otherwise they live in .scratch4j/project.json and
   * the generated launcher applies them, and the dialog offers to create the
   * window class.
   */
  void showWindowSettings() {
    ProjectSettings settings = project.settings();
    String windowClass;
    WindowDocument.Settings code = null;
    Path windowFile = null;
    try {
      windowClass = project.windowClass();
      if (windowClass != null) {
        windowFile = project.sourceOf(windowClass);
        code = WindowDocument.read(Files.readString(windowFile));
      }
    } catch (IOException | RuntimeException e) {
      showError(new IOException(I18n.t("stages.window.handwritten"), e));
      return;
    }
    javafx.scene.control.CheckBox fullScreen = new javafx.scene.control.CheckBox(
        I18n.t("stages.window.fullscreen"));
    fullScreen.setSelected(code != null ? code.fullScreen() : settings.fullScreen);
    javafx.scene.control.CheckBox pixelArt = new javafx.scene.control.CheckBox(
        I18n.t("stages.window.pixelart"));
    pixelArt.setSelected(code != null ? code.pixelArt() : settings.pixelArt);
    javafx.scene.control.CheckBox debug = new javafx.scene.control.CheckBox(
        I18n.t("stages.window.debug"));
    debug.setSelected(code != null ? code.debug() : settings.debugOnStart);
    javafx.scene.control.TextField splash = new javafx.scene.control.TextField(
        code != null ? code.splashLogo() : settings.splashLogo);
    splash.setPromptText("assets/images/logo.png");
    javafx.scene.control.TextField width = new javafx.scene.control.TextField(
        String.valueOf(code != null ? code.width() : 480));
    javafx.scene.control.TextField height = new javafx.scene.control.TextField(
        String.valueOf(code != null ? code.height() : 360));
    width.setPrefColumnCount(5);
    height.setPrefColumnCount(5);
    Button pick = new Button(I18n.t("stages.window.splash.pick"));
    pick.setOnAction(e -> {
      javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
      chooser.setTitle(I18n.t("stages.window.splash.pick"));
      chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
          I18n.t("stages.window.images"), "*.png", "*.jpg", "*.jpeg", "*.gif"));
      Path imageDir = project.root().resolve("assets/images");
      chooser.setInitialDirectory(Files.isDirectory(imageDir)
          ? imageDir.toFile() : project.root().toFile());
      java.io.File chosen = chooser.showOpenDialog(getScene().getWindow());
      if (chosen != null) {
        Path path = chosen.toPath().toAbsolutePath().normalize();
        Path root = project.root().toAbsolutePath().normalize();
        if (path.startsWith(root)) {
          splash.setText(root.relativize(path).toString().replace('\\', '/'));
        } else {
          showError(new IOException(I18n.t("stages.window.splash.project")));
        }
      }
    });
    HBox splashRow = new HBox(6, splash, pick);
    HBox.setHgrow(splash, Priority.ALWAYS);
    Label where = new Label(windowClass != null
        ? I18n.t("stages.window.incode", windowClass + ".java")
        : I18n.t("stages.window.inmeta"));
    where.setWrapText(true);
    where.getStyleClass().add("text-muted");
    javafx.scene.control.CheckBox createWindow = new javafx.scene.control.CheckBox(
        I18n.t("stages.window.create"));
    createWindow.setVisible(windowClass == null);
    createWindow.setManaged(windowClass == null);
    HBox size = new HBox(6, new Label(I18n.t("stages.window.size")), width, new Label("\u00d7"),
        height);
    size.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    size.visibleProperty().bind(createWindow.selectedProperty().or(
        new javafx.beans.property.SimpleBooleanProperty(windowClass != null)));
    size.managedProperty().bind(size.visibleProperty());
    VBox content = new VBox(10, where, size, fullScreen, pixelArt, debug,
        new Label(I18n.t("stages.window.splash")), splashRow, createWindow);
    javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
        new javafx.scene.control.Dialog<>();
    dialog.setTitle(I18n.t("stages.window.settings"));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().setPrefWidth(500);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    String existingWindow = windowClass;
    Path existingFile = windowFile;
    dialog.showAndWait().ifPresent(button -> {
      if (button != I18n.ok()) {
        return;
      }
      String pathText = splash.getText().trim().replace('\\', '/');
      if (!pathText.isEmpty()) {
        Path root = project.root().toAbsolutePath().normalize();
        Path image = root.resolve(pathText).normalize();
        if (!image.startsWith(root) || !Files.isRegularFile(image)
            || !pathText.matches("(?i).*\\.(png|jpg|jpeg|gif)$")) {
          showError(new IOException(I18n.t("stages.window.splash.project")));
          return;
        }
      }
      int w;
      int h;
      try {
        w = Integer.parseInt(width.getText().trim());
        h = Integer.parseInt(height.getText().trim());
      } catch (NumberFormatException e) {
        showError(new IOException(I18n.t("stages.window.size.invalid")));
        return;
      }
      try {
        if (existingWindow != null || createWindow.isSelected()) {
          WindowDocument.Settings next = new WindowDocument.Settings(w, h, startStage(),
              fullScreen.isSelected(), pixelArt.isSelected(), pathText, debug.isSelected());
          if (existingWindow != null) {
            String source = Files.readString(existingFile);
            LocalHistory.writeString(project.root(), existingFile,
                WindowDocument.write(source, next));
          } else {
            // never overwrite an existing (hand-written) class
            String name = "MyWindow";
            for (int i = 2; project.sourceOf(name) != null
                || Files.exists(project.root().resolve(name + ".java")); i++) {
              name = "MyWindow" + i;
            }
            Files.writeString(project.root().resolve(name + ".java"),
                WindowDocument.create(name, next), java.nio.file.StandardOpenOption.CREATE_NEW);
            settings.startStage = name;
          }
          // the window class owns these now: the launcher must not apply them twice
          settings.fullScreen = false;
          settings.pixelArt = false;
          settings.debugOnStart = false;
          settings.splashLogo = "";
        } else {
          settings.fullScreen = fullScreen.isSelected();
          settings.pixelArt = pixelArt.isSelected();
          settings.debugOnStart = debug.isSelected();
          settings.splashLogo = pathText;
        }
        settings.save(project.root());
        onProjectChanged.run();
        refresh();
      } catch (IOException | RuntimeException e) {
        showError(e);
      }
    });
  }

  private void addStage() {
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(nextStageName());
    dialog.getDialogPane().getButtonTypes().setAll(I18n.ok(), I18n.cancel());
    dialog.setTitle(I18n.t("stages.new"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("stages.name.prompt"));
    Theme.style(dialog);
    dialog.showAndWait().ifPresent(name -> {
      name = name.trim();
      if (name.isEmpty()) {
        return;
      }
      try {
        NewClass.create(NewClass.Kind.STAGE, project.root(), name);
        onProjectChanged.run();
        refresh();
        stages.getSelectionModel().select(name);
        onOpenDesigner.accept(name);
      } catch (IOException | RuntimeException e) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.ERROR, e.getMessage(),
            I18n.ok());
        alert.setHeaderText(null);
        alert.show();
      }
    });
  }

  private String nextStageName() {
    int n = 2;
    try {
      while (project.stageClasses().contains("Level" + n)) {
        n++;
      }
    } catch (IOException ignored) {
      // default name only
    }
    return "Level" + n;
  }
}
