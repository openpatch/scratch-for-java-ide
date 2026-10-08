package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File > Export: one dialog instead of a menu. A card per target (an app
 * folder for this computer, a runnable JAR, BlueJ or VS Code zips), each with
 * a sentence saying what you get; an app for another operating system below.
 * The result is the format id {@code StudioApp.export} understands, or
 * {@link #APP_INFO} to edit the version and icon first.
 */
final class ExportDialog {

  static final String APP_INFO = "appinfo";

  /** Target id (for export) by menu key, in the order shown. */
  private static final Map<String, String> OTHER = new java.util.LinkedHashMap<>();

  static {
    OTHER.put("menu.export.windows", "windows-x64");
    OTHER.put("menu.export.mac.arm", "mac-aarch64");
    OTHER.put("menu.export.mac.intel", "mac-x64");
    OTHER.put("menu.export.linux", "linux-x64");
    OTHER.put("menu.export.windows.arm", "windows-aarch64");
    OTHER.put("menu.export.linux.arm", "linux-aarch64");
  }

  private ExportDialog() {}

  static Optional<String> show(Window owner) {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(I18n.t("menu.export"));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().add(I18n.cancel());
    dialog.setResultConverter(button -> null);

    Label hint = new Label(I18n.t("export.hint"));
    hint.getStyleClass().add("form-label");
    GridPane cards = new GridPane();
    cards.setHgap(10);
    cards.setVgap(10);
    List<String> formats = List.of("app", "jar", "bluej", "vscode");
    for (int i = 0; i < formats.size(); i++) {
      cards.add(card(formats.get(i), dialog), i % 2, i / 2);
    }

    Label otherTitle = new Label(I18n.t("menu.export.other"));
    otherTitle.getStyleClass().add("card-button-title");
    Label otherHint = new Label(I18n.t("export.other.hint"));
    otherHint.getStyleClass().add("card-button-hint");
    otherHint.setWrapText(true);
    ComboBox<String> target = new ComboBox<>();
    target.getItems().addAll(OTHER.keySet());
    target.getSelectionModel().selectFirst();
    target.setConverter(new StringConverter<>() {
      @Override public String toString(String key) {
        return key == null ? "" : I18n.t(key).replace("...", "");
      }

      @Override public String fromString(String s) {
        return null;
      }
    });
    Button export = new Button(I18n.t("export.other.button"), Icons.of("fth-globe"));
    export.setOnAction(e -> {
      dialog.setResult(OTHER.get(target.getValue()));
      dialog.close();
    });
    HBox otherRow = new HBox(8, target, export);
    otherRow.setAlignment(Pos.CENTER_LEFT);
    VBox other = new VBox(4, otherTitle, otherHint, otherRow);
    other.getStyleClass().add("export-other");

    Hyperlink appInfo = new Hyperlink(I18n.t("menu.export.appinfo"));
    appInfo.setGraphic(Icons.of("fth-tag"));
    appInfo.setOnAction(e -> {
      dialog.setResult(APP_INFO);
      dialog.close();
    });

    VBox content = new VBox(10, hint, cards, other, appInfo);
    VBox.setMargin(other, new javafx.geometry.Insets(8, 0, 0, 0));
    dialog.getDialogPane().setContent(content);
    return dialog.showAndWait();
  }

  private static Button card(String format, Dialog<String> dialog) {
    Label title = new Label(I18n.t("menu.export." + format).replace("...", ""));
    title.getStyleClass().add("card-button-title");
    Label hint = new Label(I18n.t("export." + format + ".hint"));
    hint.getStyleClass().add("card-button-hint");
    hint.setWrapText(true);
    VBox text = new VBox(2, title, hint);
    HBox content = new HBox(10, Icons.of(switch (format) {
      case "app" -> "fth-package";
      case "jar" -> "fth-coffee";
      default -> "fth-archive";
    }, 22), text);
    content.setAlignment(Pos.CENTER_LEFT);
    Button button = new Button(null, content);
    button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    button.getStyleClass().addAll("card-button", "add-card");
    button.setPrefSize(250, 96);
    button.setMaxWidth(Double.MAX_VALUE);
    button.setOnAction(e -> {
      dialog.setResult(format);
      dialog.close();
    });
    return button;
  }
}
