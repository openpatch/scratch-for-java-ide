package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.api.ApiMethod;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The sidebar's Reference: every library method from the bundled API index,
 * searchable by name, class, Scratch block or description, with its block,
 * Javadoc and an Insert button. F1 opens it at the word under the caret, so
 * looking something up needs no browser.
 */
final class ReferenceView extends VBox {

  private final ApiIndex index;
  private final TextField search = new TextField();
  private final ListView<ApiMethod> list = new ListView<>();
  private final VBox details = new VBox(8);
  private List<ApiMethod> all = List.of();

  ReferenceView(ApiIndex index, Consumer<ApiMethod> onInsert) {
    super(6);
    this.index = index;
    getStyleClass().add("reference-view");
    setPadding(new Insets(6));
    search.setId("reference-search");
    search.setPromptText(I18n.t("reference.search"));
    search.textProperty().addListener((o, old, text) -> filter());
    list.setId("reference-list");
    list.setCellFactory(view -> new ListCell<>() {
      @Override protected void updateItem(ApiMethod method, boolean empty) {
        super.updateItem(method, empty);
        if (empty || method == null) {
          setText(null);
          setTooltip(null);
        } else {
          setText(label(method));
          setTooltip(method.summary() == null || method.summary().isBlank() ? null
              : new javafx.scene.control.Tooltip(method.summary()));
        }
      }
    });
    list.getSelectionModel().selectedItemProperty().addListener(
        (o, old, method) -> showDetails(method, onInsert));
    list.setOnMouseClicked(e -> {
      ApiMethod method = list.getSelectionModel().getSelectedItem();
      if (e.getClickCount() == 2 && method != null) onInsert.accept(method);
    });
    list.setPlaceholder(new Label(I18n.t("reference.none")));
    ScrollPane detailScroll = new ScrollPane(details);
    detailScroll.setFitToWidth(true);
    detailScroll.setPrefHeight(260);
    detailScroll.getStyleClass().add("reference-details");
    details.setPadding(new Insets(6));
    VBox.setVgrow(list, Priority.ALWAYS);
    getChildren().addAll(search, list, detailScroll);
    refresh();
  }

  /** Reads the index again (the project's library may have another API). */
  void refresh() {
    all = index.methods().stream()
        .sorted(Comparator.comparing(ApiMethod::className)
            .thenComparing(ApiMethod::methodName)
            .thenComparing(m -> m.params().size()))
        .toList();
    filter();
  }

  /** Shows the entries for {@code word}, the exact method first. */
  void lookUp(String word) {
    search.setText(word);
    index.byName(word).stream().filter(list.getItems()::contains).findFirst()
        .ifPresent(method -> {
          list.getSelectionModel().select(method);
          list.scrollTo(method);
        });
    search.requestFocus();
    search.end();
  }

  void focusSearch() {
    search.requestFocus();
    search.selectAll();
  }

  List<ApiMethod> shown() {
    return list.getItems();
  }

  private void filter() {
    String query = search.getText().toLowerCase(Locale.ROOT).strip();
    List<ApiMethod> shown = query.isEmpty() ? all : all.stream()
        .filter(m -> matches(m, query))
        .toList();
    list.getItems().setAll(shown);
    if (!shown.isEmpty()) list.getSelectionModel().selectFirst();
  }

  private static boolean matches(ApiMethod m, String query) {
    for (String text : new String[] {m.methodName(), m.className(), m.scratchblock(),
        m.summary()}) {
      if (text != null && text.toLowerCase(Locale.ROOT).contains(query)) return true;
    }
    return false;
  }

  private static String label(ApiMethod method) {
    return method.className() + " · " + ("constructor".equals(method.methodName())
        ? "new " + method.className() : method.methodName())
        + "(" + String.join(", ", method.params()) + ")";
  }

  private void showDetails(ApiMethod method, Consumer<ApiMethod> onInsert) {
    details.getChildren().clear();
    if (method == null) return;
    Label signature = new Label(method.className() + " · " + method.signature());
    signature.getStyleClass().add("help-signature");
    signature.setWrapText(true);
    details.getChildren().add(signature);
    if (method.scratchblock() != null) {
      details.getChildren().add(BlockPalette.renderBlock(method));
    }
    String text = method.description() != null && !method.description().isBlank()
        ? method.description() : method.summary();
    if (text != null && !text.isBlank()) {
      details.getChildren().add(wrapped(text));
    }
    if (!method.paramDocs().isEmpty()) {
      details.getChildren().add(heading(I18n.t("reference.params")));
      for (String param : method.paramDocs()) details.getChildren().add(wrapped("• " + param));
    }
    if (method.returns() != null && !method.returns().isBlank()) {
      details.getChildren().add(heading(I18n.t("reference.returns")));
      details.getChildren().add(wrapped(method.returns()));
    }
    Button insert = new Button(I18n.t("reference.insert"), Icons.of("fth-corner-down-left"));
    insert.setId("reference-insert");
    insert.setOnAction(e -> onInsert.accept(method));
    details.getChildren().add(insert);
  }

  private static Label wrapped(String text) {
    Label label = new Label(text);
    label.setWrapText(true);
    return label;
  }

  private static Label heading(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("reference-heading");
    return label;
  }
}
