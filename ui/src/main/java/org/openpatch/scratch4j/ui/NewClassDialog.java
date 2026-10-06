package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.project.NewClass;

import java.util.Locale;
import java.util.Optional;

/** The new-class wizard: Sprite / AnimatedSprite / UISprite / Stage as cards. */
final class NewClassDialog {

  record Result(String name, NewClass.Kind kind) {}

  private NewClassDialog() {}

  /** {@code preset} pre-selects a kind (null: Sprite). */
  static Optional<Result> show(NewClass.Kind preset) {
    Dialog<Result> dialog = new Dialog<>();
    dialog.setTitle(I18n.t("wizard.class.title"));
    Theme.style(dialog);
    ButtonType createType = new ButtonType(I18n.t("wizard.create"), ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(createType, I18n.cancel());

    ToggleGroup kinds = new ToggleGroup();
    HBox cards = new HBox(8);
    for (NewClass.Kind kind : NewClass.Kind.values()) {
      String key = "kind." + kind.name().toLowerCase(Locale.ROOT);
      Label title = new Label(I18n.t(key));
      title.getStyleClass().add("card-button-title");
      Label hint = new Label(I18n.t(key + ".hint"));
      hint.getStyleClass().add("card-button-hint");
      hint.setWrapText(true);
      VBox content = new VBox(4, Icons.of(iconOf(kind), 22), title, hint);
      content.setAlignment(Pos.TOP_LEFT);
      ToggleButton card = new ToggleButton(null, content);
      card.getStyleClass().add("template-card");
      card.setToggleGroup(kinds);
      card.setUserData(kind);
      card.setPrefSize(150, 130);
      card.setSelected(kind == (preset == null ? NewClass.Kind.SPRITE : preset));
      cards.getChildren().add(card);
    }

    TextField nameField = new TextField(preset == NewClass.Kind.STAGE ? "Level2" : "Player");
    Label error = new Label();
    error.getStyleClass().add("form-error");
    Runnable validate = () -> {
      boolean ok = nameField.getText().trim().matches("[A-Z][A-Za-z0-9_]*");
      error.setText(ok ? "" : I18n.t("wizard.class.error"));
      dialog.getDialogPane().lookupButton(createType).setDisable(!ok);
    };
    nameField.textProperty().addListener((o, old, v) -> validate.run());
    kinds.selectedToggleProperty().addListener((o, old, toggle) -> {
      if (toggle == null) {
        old.setSelected(true);
      }
    });
    validate.run();

    Label kindLabel = new Label(I18n.t("wizard.class.kind"));
    kindLabel.getStyleClass().add("form-label");
    Label nameLabel = new Label(I18n.t("wizard.class.name"));
    nameLabel.getStyleClass().add("form-label");
    dialog.getDialogPane().setContent(new VBox(8, kindLabel, cards, nameLabel, nameField, error));
    dialog.setResultConverter(bt -> bt == createType
        ? new Result(nameField.getText().trim(),
            (NewClass.Kind) kinds.getSelectedToggle().getUserData())
        : null);
    javafx.application.Platform.runLater(() -> {
      nameField.requestFocus();
      nameField.selectAll();
    });
    return dialog.showAndWait();
  }

  private static String iconOf(NewClass.Kind kind) {
    return switch (kind) {
      case STAGE -> "fth-monitor";
      case SPRITE -> "fth-user";
      case ANIMATED_SPRITE -> "fth-film";
      case UI_SPRITE -> "fth-square";
    };
  }
}
