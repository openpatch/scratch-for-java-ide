package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Search tab: the uses of a name (Find usages) or the lines that contain a
 * text (Find in project). A click opens the file with the match selected.
 */
final class SearchResultsView extends BorderPane {

  /**
   * One match: its offsets in the file, 1-based line, the stripped line text
   * and where the match sits in that text. {@code declaration} marks where a
   * name is declared.
   */
  record Match(Path file, int start, int end, long line, String lineText, int from, int to,
      boolean declaration) {}

  private final Label title = new Label();
  private final ListView<Match> list = new ListView<>();
  private Path root;

  SearchResultsView(Consumer<Match> onOpen) {
    getStyleClass().add("search-results");
    title.getStyleClass().add("search-title");
    title.setMaxWidth(Double.MAX_VALUE);
    Label empty = new Label(I18n.t("search.empty"), Icons.of("fth-search", 18));
    empty.getStyleClass().add("problems-empty");
    list.setPlaceholder(empty);
    list.setCellFactory(view -> new ListCell<>() {
      {
        setPrefWidth(0);
      }

      @Override
      protected void updateItem(Match match, boolean isEmpty) {
        super.updateItem(match, isEmpty);
        if (isEmpty || match == null) {
          setText(null);
          setGraphic(null);
          return;
        }
        String text = match.lineText();
        int from = Math.max(0, Math.min(match.from(), text.length()));
        int to = Math.max(from, Math.min(match.to(), text.length()));
        Label before = new Label(text.substring(0, from));
        Label hit = new Label(text.substring(from, to));
        hit.getStyleClass().add("search-hit");
        Label after = new Label(text.substring(to));
        for (Label part : List.of(before, hit, after)) {
          part.getStyleClass().add("search-line");
          part.setMinWidth(Region.USE_PREF_SIZE);
        }
        // the rest of a long line is cut off with "..."
        after.setMinWidth(0);
        HBox code = new HBox(before, hit, after);
        code.setAlignment(Pos.CENTER_LEFT);
        code.setMinWidth(0);
        HBox.setHgrow(code, Priority.ALWAYS);
        Label location = new Label(location(match));
        location.getStyleClass().add("problem-location");
        location.setMinWidth(Region.USE_PREF_SIZE);
        var icon = Icons.of(match.declaration() ? "fth-flag" : "fth-corner-down-right", 14);
        icon.getStyleClass().add("search-icon");
        HBox row = new HBox(8, icon, location, code);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        setText(null);
        setGraphic(row);
      }
    });
    list.setOnMouseClicked(e -> open(onOpen));
    list.setOnKeyPressed(e -> {
      if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
        open(onOpen);
      }
    });
    setTop(title);
    setCenter(list);
    show(I18n.t("search.title"), List.of());
  }

  private void open(Consumer<Match> onOpen) {
    Match match = list.getSelectionModel().getSelectedItem();
    if (match != null) {
      onOpen.accept(match);
    }
  }

  private String location(Match match) {
    Path shown = root != null && match.file().startsWith(root)
        ? root.relativize(match.file()) : match.file().getFileName();
    return shown.toString().replace('\\', '/') + ":" + match.line();
  }

  /** Shows new results under a heading; paths are shown relative to {@code root}. */
  void show(Path root, String heading, List<Match> matches) {
    this.root = root;
    show(heading, matches);
  }

  private void show(String heading, List<Match> matches) {
    title.setText(heading);
    list.getItems().setAll(matches);
    if (!matches.isEmpty()) {
      list.getSelectionModel().clearSelection();
      list.scrollTo(0);
    }
  }

  List<Match> matches() {
    return List.copyOf(list.getItems());
  }
}
