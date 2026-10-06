package org.openpatch.scratch4j.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.api.ApiMethod;

import java.util.List;
import java.util.function.Consumer;

/** F1 help for the word at the caret: signature, summary, Scratch block, docs link. */
final class ApiHelpDialog {

  private ApiHelpDialog() {}

  static void showForWord(ApiIndex index, String word, Consumer<String> browse) {
    List<ApiMethod> matches = index.byName(word);
    if (matches.isEmpty()) {
      Alert alert = new Alert(Alert.AlertType.INFORMATION,
          I18n.t("apihelp.none", word), I18n.ok());
      alert.setHeaderText(null);
      alert.showAndWait();
      return;
    }
    Dialog<Void> dialog = new Dialog<>();
    dialog.getDialogPane().getButtonTypes().add(I18n.close());
    dialog.setTitle(I18n.t("apihelp.title") + ": " + word);
    Theme.style(dialog);

    VBox box = new VBox(14);
    box.setPrefWidth(480);
    for (ApiMethod method : matches.subList(0, Math.min(4, matches.size()))) {
      VBox entry = new VBox(6);
      entry.getStyleClass().add("help-entry");
      Label signature = new Label(method.className() + " · " + method.signature());
      signature.getStyleClass().add("help-signature");
      signature.setWrapText(true);
      entry.getChildren().add(signature);
      if (method.scratchblock() != null) {
        entry.getChildren().add(BlockPalette.renderBlock(method));
      }
      if (method.summary() != null && !method.summary().isBlank()) {
        Label summary = new Label(method.summary());
        summary.setWrapText(true);
        entry.getChildren().add(summary);
      }
      Hyperlink docs = new Hyperlink(I18n.t("apihelp.docs") + " " + method.docsUrl());
      docs.setGraphic(Icons.of("fth-external-link"));
      docs.setOnAction(e -> browse.accept(method.docsUrl()));
      entry.getChildren().add(docs);
      box.getChildren().add(entry);
    }
    dialog.getDialogPane().setContent(box);
    dialog.showAndWait();
  }
}
