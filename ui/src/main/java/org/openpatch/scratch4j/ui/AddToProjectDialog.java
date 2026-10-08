package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * File > Add to project: one dialog with a card per thing a project can get
 * (a class, an image, a sound, a folder, something from the asset library),
 * each with a sentence saying when you would want it. Shaders and maps sit
 * in a smaller row below, so the common choices stay in front.
 */
final class AddToProjectDialog {

  enum Choice { CLASS, IMAGE, SOUND, LIBRARY, FOLDER, SHADER, MAP }

  private static final List<Choice> MAIN = List.of(Choice.CLASS, Choice.IMAGE, Choice.SOUND,
      Choice.LIBRARY, Choice.FOLDER);
  private static final List<Choice> MORE = List.of(Choice.SHADER, Choice.MAP);

  private AddToProjectDialog() {}

  static Optional<Choice> show(Window owner) {
    Dialog<Choice> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(I18n.t("add.title"));
    dialog.setHeaderText(null);
    Theme.style(dialog);
    dialog.getDialogPane().getButtonTypes().add(I18n.cancel());
    dialog.setResultConverter(button -> null);

    Label hint = new Label(I18n.t("add.hint"));
    hint.getStyleClass().add("form-label");
    GridPane cards = new GridPane();
    cards.setHgap(10);
    cards.setVgap(10);
    int i = 0;
    for (Choice choice : MAIN) {
      cards.add(card(choice, 250, 96, dialog), i % 2, i / 2);
      i++;
    }
    Label more = new Label(I18n.t("add.more"));
    more.getStyleClass().add("form-label");
    GridPane moreCards = new GridPane();
    moreCards.setHgap(10);
    moreCards.setVgap(10);
    i = 0;
    for (Choice choice : MORE) {
      moreCards.add(card(choice, 250, 72, dialog), i % 2, i / 2);
      i++;
    }
    VBox content = new VBox(10, hint, cards, more, moreCards);
    VBox.setMargin(more, new javafx.geometry.Insets(8, 0, 0, 0));
    dialog.getDialogPane().setContent(content);
    return dialog.showAndWait();
  }

  private static Button card(Choice choice, double width, double height, Dialog<Choice> dialog) {
    String key = "add." + choice.name().toLowerCase(Locale.ROOT);
    Label title = new Label(I18n.t(key));
    title.getStyleClass().add("card-button-title");
    Label hint = new Label(I18n.t(key + ".hint"));
    hint.getStyleClass().add("card-button-hint");
    hint.setWrapText(true);
    VBox text = new VBox(2, title, hint);
    javafx.scene.layout.HBox content = new javafx.scene.layout.HBox(10,
        Icons.of(iconOf(choice), 22), text);
    content.setAlignment(Pos.CENTER_LEFT);
    Button button = new Button(null, content);
    button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    button.getStyleClass().addAll("card-button", "add-card");
    button.setPrefSize(width, height);
    button.setMaxWidth(Double.MAX_VALUE);
    button.setOnAction(e -> {
      dialog.setResult(choice);
      dialog.close();
    });
    return button;
  }

  static String iconOf(Choice choice) {
    return switch (choice) {
      case CLASS -> "fth-file-plus";
      case IMAGE -> "fth-image";
      case SOUND -> "fth-music";
      case LIBRARY -> "fth-grid";
      case FOLDER -> "fth-folder-plus";
      case SHADER -> "fth-zap";
      case MAP -> "fth-map";
    };
  }
}
