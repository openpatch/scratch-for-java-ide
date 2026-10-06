package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import java.util.stream.Stream;

/**
 * Picks an image: the project's own files in {@code assets/images} first,
 * then the built-in Kenney images (search + sheet filter). Returns the
 * reference the library understands: a relative path or a built-in name.
 */
final class AssetPickerDialog {

  private static final int MAX_TILES = 300;

  private AssetPickerDialog() {}

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
    dialog.getDialogPane().setContent(new VBox(8, search, list));
    dialog.setResultConverter(button -> button == I18n.ok()
        ? list.getSelectionModel().getSelectedItem() : null);
    return dialog.showAndWait();
  }

  static Optional<String> pickImage(Path projectRoot, String title) {
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
      for (BuiltinImage image : BuiltinAssetIndex.get().images()) {
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
    HBox top = new HBox(8, search, sheets);
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

  private static List<String> projectImages(Path root) {
    List<String> files = new ArrayList<>();
    if (root == null) {
      return files;
    }
    Path dir = root.resolve("assets/images");
    if (!Files.isDirectory(dir)) {
      return files;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
              .matches(".*\\.(png|jpg|jpeg|gif)$"))
          .sorted()
          .forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
    } catch (IOException ignored) {
      // unreadable folder: built-ins only
    }
    return files;
  }

  private static List<String> projectSounds(Path root) {
    List<String> files = new ArrayList<>();
    if (root == null) {
      return files;
    }
    Path dir = root.resolve("assets/sounds");
    if (!Files.isDirectory(dir)) {
      return files;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
              .matches(".*\\.(wav|ogg|aiff|aif|au)$"))
          .sorted()
          .forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
    } catch (IOException ignored) {
      // built-in sounds are still available
    }
    return files;
  }
}
