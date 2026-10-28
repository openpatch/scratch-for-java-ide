package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.assets.BuiltinAssetIndex;
import org.openpatch.scratch4j.core.assets.BuiltinImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks an image: the project's own files first (those in {@code assets/}
 * before the rest), then the built-in Kenney images (search + sheet filter). Returns the
 * reference the library understands: a relative path or a built-in name.
 */
final class AssetPickerDialog {

  private static final int MAX_TILES = 300;

  /** Opens an asset the picker just created in its editor (set by the IDE). */
  private static java.util.function.Consumer<Path> onCreated = file -> { };

  private AssetPickerDialog() {}

  static void setOnCreated(java.util.function.Consumer<Path> action) {
    onCreated = action;
  }

  /**
   * "Draw a new image" / "Record a new sound": an empty asset with the next
   * free name, chosen and opened in its editor, so a student needs no other
   * program and no web search.
   */
  private static Button createButton(Path projectRoot, ProjectAssetCreator.Kind kind,
      Dialog<String> dialog) {
    boolean image = kind == ProjectAssetCreator.Kind.IMAGE;
    Button create = new Button(I18n.t(image ? "picker.draw" : "picker.record"),
        Icons.of(image ? "fth-edit-2" : "fth-mic"));
    create.setId(image ? "picker-draw" : "picker-record");
    create.setDisable(projectRoot == null);
    create.setOnAction(e -> {
      String base = image ? "costume" : "sound";
      String folder = image ? "assets/images/" : "assets/sounds/";
      String extension = image ? ".png" : ".wav";
      int n = 1;
      while (Files.exists(projectRoot.resolve(folder + base + n + extension))) n++;
      try {
        Path file = ProjectAssetCreator.create(projectRoot, kind, base + n, 64, 64);
        dialog.setResult(projectRoot.relativize(file).toString().replace('\\', '/'));
        dialog.close();
        onCreated.accept(file);
      } catch (IOException error) {
        create.setText(error.getMessage());
      }
    });
    return create;
  }

  static Optional<String> pickSound(Path projectRoot, String title) {
    Dialog<String> dialog = new Dialog<>();
    dialog.setTitle(title);
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    TextField search = new TextField();
    search.setPromptText(I18n.t("library.search"));
    ListView<String> list = new ListView<>();
    List<String> names = new ArrayList<>(projectSounds(projectRoot));
    names.addAll(BuiltinAssetIndex.get().sounds());
    Runnable refill = () -> list.getItems().setAll(names.stream()
        .filter(name -> name.toLowerCase(Locale.ROOT)
            .contains(search.getText().toLowerCase(Locale.ROOT).trim()))
        .toList());
    search.textProperty().addListener((o, old, value) -> refill.run());
    refill.run();
    list.setPrefSize(480, 420);
    list.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
        dialog.setResult(list.getSelectionModel().getSelectedItem());
        dialog.close();
      }
    });
    dialog.getDialogPane().lookupButton(I18n.ok()).disableProperty().bind(
        list.getSelectionModel().selectedItemProperty().isNull());
    HBox top = new HBox(8, search, createButton(projectRoot, ProjectAssetCreator.Kind.SOUND,
        dialog));
    HBox.setHgrow(search, Priority.ALWAYS);
    dialog.getDialogPane().setContent(new VBox(8, top, list));
    dialog.setResultConverter(button -> button == I18n.ok()
        ? list.getSelectionModel().getSelectedItem() : null);
    return dialog.showAndWait();
  }

  static Optional<String> pickImage(Path projectRoot, String title) {
    return pickImage(projectRoot, title, true);
  }

  /**
   * Like {@link #pickImage(Path, String)}; without {@code builtins} only the
   * project's own images (a sprite sheet to cut: no built-in image is one).
   */
  static Optional<String> pickImage(Path projectRoot, String title, boolean builtins) {
    Dialog<String> dialog = new Dialog<>();
    dialog.setTitle(title);
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    dialog.getDialogPane().lookupButton(I18n.ok()).setDisable(true);

    TextField search = new TextField();
    search.setPromptText(I18n.t("library.search"));
    HBox.setHgrow(search, Priority.ALWAYS);
    ComboBox<String> sheets = new ComboBox<>();
    sheets.getItems().add(I18n.t("library.allSheets"));
    sheets.getItems().addAll(BuiltinAssetIndex.get().images().stream()
        .map(BuiltinImage::sheet).distinct().toList());
    sheets.getSelectionModel().selectFirst();

    FlowPane grid = new FlowPane(6, 6);
    grid.setPadding(new Insets(6));
    grid.getStyleClass().add("asset-grid");
    ToggleGroup selection = new ToggleGroup();
    selection.selectedToggleProperty().addListener((o, old, toggle) ->
        dialog.getDialogPane().lookupButton(I18n.ok()).setDisable(toggle == null));

    Runnable refill = () -> {
      grid.getChildren().clear();
      String query = search.getText().toLowerCase(Locale.ROOT).trim();
      boolean allSheets = sheets.getSelectionModel().getSelectedIndex() <= 0;
      for (String file : projectImages(projectRoot)) {
        if (file.toLowerCase(Locale.ROOT).contains(query) && allSheets) {
          grid.getChildren().add(tile(file, CostumeView.costume(file, projectRoot),
              selection, dialog, true));
        }
      }
      if (grid.getChildren().isEmpty() && !builtins) {
        Label none = new Label(I18n.t("picker.noProjectImages"));
        none.getStyleClass().add("text-muted");
        none.setWrapText(true);
        grid.getChildren().add(none);
      }
      for (BuiltinImage image : builtins ? BuiltinAssetIndex.get().images()
          : List.<BuiltinImage>of()) {
        if (grid.getChildren().size() >= MAX_TILES) {
          break;
        }
        if (!image.referenceName().toLowerCase(Locale.ROOT).contains(query)
            || !allSheets && !image.sheet().equals(sheets.getValue())) {
          continue;
        }
        grid.getChildren().add(tile(image.referenceName(),
            CostumeView.costume(image.referenceName()), selection, dialog, false));
      }
    };
    search.textProperty().addListener((o, old, v) -> refill.run());
    sheets.valueProperty().addListener((o, old, v) -> refill.run());
    refill.run();

    ScrollPane scroll = new ScrollPane(grid);
    scroll.setFitToWidth(true);
    scroll.setPrefSize(720, 460);
    Label credits = new Label(I18n.t("library.credits"));
    credits.getStyleClass().add("text-muted");
    sheets.setVisible(builtins);
    sheets.setManaged(builtins);
    credits.setVisible(builtins);
    credits.setManaged(builtins);
    HBox top = new HBox(8, search, sheets,
        createButton(projectRoot, ProjectAssetCreator.Kind.IMAGE, dialog));
    top.setAlignment(Pos.CENTER_LEFT);
    VBox content = new VBox(8, top, scroll, credits);
    dialog.getDialogPane().setContent(content);
    dialog.setResultConverter(button -> button == I18n.ok() && selection.getSelectedToggle()
        != null ? (String) selection.getSelectedToggle().getUserData() : null);
    search.requestFocus();
    return dialog.showAndWait();
  }

  private static ToggleButton tile(String reference, Image image, ToggleGroup group,
      Dialog<String> dialog, boolean projectFile) {
    ImageView view = new ImageView(image);
    view.setFitWidth(64);
    view.setFitHeight(64);
    view.setPreserveRatio(true);
    ToggleButton tile = new ToggleButton(null, view);
    tile.getStyleClass().add("asset-tile");
    if (projectFile) {
      tile.getStyleClass().add("project-file");
    }
    tile.setToggleGroup(group);
    tile.setUserData(reference);
    tile.setTooltip(new Tooltip(reference));
    tile.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        tile.setSelected(true);
        dialog.setResult(reference);
        dialog.close();
      }
    });
    return tile;
  }

  /** Images the picker (and the library) can load. */
  private static final java.util.Set<String> IMAGES = java.util.Set.of("png", "jpg", "jpeg",
      "gif");
  private static final java.util.Set<String> SOUNDS = java.util.Set.of("wav", "ogg", "mp3",
      "aiff", "aif", "au");

  private static List<String> projectImages(Path root) {
    return projectFiles(root, IMAGES);
  }

  private static List<String> projectSounds(Path root) {
    return projectFiles(root, SOUNDS);
  }

  /**
   * The project's files of these types anywhere in the project (not in build
   * or IDE folders): those in assets/ first, where the IDE puts new ones.
   */
  private static List<String> projectFiles(Path root, java.util.Set<String> extensions) {
    if (root == null || !Files.isDirectory(root)) {
      return new ArrayList<>();
    }
    List<String> files = new ArrayList<>(ProjectFileIndex.freshFiles(root, extensions));
    files.sort(java.util.Comparator.comparing((String f) -> !f.startsWith("assets/")));
    return files;
  }
}
