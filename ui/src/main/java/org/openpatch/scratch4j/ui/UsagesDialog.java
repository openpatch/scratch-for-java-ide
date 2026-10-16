package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.openpatch.scratch4j.core.project.FileUsages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Asks before a move, rename or delete touches a file that the project uses:
 * lists every usage (which ones the IDE updates by itself, which ones need a
 * look by hand) and lets the user decide.
 */
final class UsagesDialog {

  /** What the user chose. */
  enum Choice { DELETE, SHOW, CANCEL }

  private UsagesDialog() {}

  /**
   * Before a move or rename, like IntelliJ's refactoring preview: every usage
   * with the change the IDE would make (old path struck through, new path
   * next to it), each with a check box, plus the usages it cannot change.
   * {@code from} and {@code to} are the project paths before and after;
   * {@code ask}: show the preview even when nothing uses the file (a drag and
   * drop is confirmed, a rename that the user just typed is not). Returns the
   * usages the user turned off (to stay as they are), or null when cancelled.
   */
  static Set<FileUsages.Usage> forMove(Window owner, Path root, String what, String from, String to,
      List<FileUsages.Usage> usages, boolean rename, boolean ask) {
    if (usages.isEmpty() && !ask) {
      return Set.of();
    }
    long updatable = usages.stream().filter(FileUsages.Usage::updatable).count();
    long manual = usages.size() - updatable;
    ButtonType apply = new ButtonType(I18n.t(rename ? "usages.rename.do" : "usages.move.do"),
        ButtonBar.ButtonData.OK_DONE);
    ButtonType cancel = I18n.cancel();
    String summary = usages.isEmpty() ? I18n.t("usages.summary.none")
        : updatable > 0 && manual > 0 ? I18n.t("usages.summary.mixed", updatable, manual)
        : manual > 0 ? I18n.t("usages.summary.manual", manual)
        : I18n.t("usages.summary.updatable", updatable);
    Set<FileUsages.Usage> skipped = new HashSet<>();
    String header = usages.isEmpty() ? I18n.t("usages.move.header", what)
        : I18n.t("usages.header", what, usages.size());
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", apply, cancel);
    alert.setTitle(I18n.t(rename ? "usages.rename.title" : "usages.move.title"));
    alert.setHeaderText(header);
    Label path = new Label(from + "  \u2192  " + to);
    path.getStyleClass().add("search-line");
    path.setWrapText(true);
    path.setMinHeight(Region.USE_PREF_SIZE);
    Label text = new Label(summary);
    text.setWrapText(true);
    text.setMinHeight(Region.USE_PREF_SIZE);
    VBox content = new VBox(10, path, text);
    if (!usages.isEmpty()) {
      ListView<FileUsages.Usage> list = list(usages,
          view -> new PreviewCell(root, from, to, skipped));
      if (updatable > 1) {
        // all on or all off at once; the rows follow
        CheckBox all = new CheckBox(I18n.t("usages.preview.all"));
        all.setSelected(true);
        all.setOnAction(e -> {
          skipped.clear();
          if (!all.isSelected()) {
            usages.stream().filter(FileUsages.Usage::updatable).forEach(skipped::add);
          }
          list.refresh();
        });
        content.getChildren().add(all);
      }
      content.getChildren().add(list);
      VBox.setVgrow(list, Priority.ALWAYS);
    }
    alert.getDialogPane().setContent(content);
    alert.getDialogPane().setPrefWidth(usages.isEmpty() ? 520 : 760);
    finish(alert, owner, apply, cancel);
    ButtonType answer = alert.showAndWait().orElse(cancel);
    return answer == apply ? Set.copyOf(skipped) : null;
  }

  /** A deletion preview, with a warning and the references that would break when still used. */
  static Choice forDelete(Window owner, Path root, Path path, List<FileUsages.Usage> usages) {
    boolean used = !usages.isEmpty();
    String what = path.getFileName().toString();
    ButtonType delete = new ButtonType(I18n.t(used ? "usages.delete.anyway" : "usages.delete.do"),
        ButtonBar.ButtonData.OTHER);
    ButtonType show = new ButtonType(I18n.t("usages.show"), ButtonBar.ButtonData.OTHER);
    ButtonType cancel = I18n.cancel();
    Alert alert = new Alert(used ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION,
        "", used ? new ButtonType[] {delete, show, cancel} : new ButtonType[] {delete, cancel});
    alert.setTitle(I18n.t("usages.delete.title"));
    alert.setHeaderText(I18n.t(used ? "usages.delete.header" : "usages.delete.confirm",
        what, usages.size()));
    Label file = new Label(shown(root, path));
    file.getStyleClass().add("search-line");
    file.setWrapText(true);
    file.setMinHeight(Region.USE_PREF_SIZE);
    Label text = new Label(I18n.t(used ? "usages.delete.summary" : "usages.delete.unused"));
    text.setWrapText(true);
    text.setMinHeight(Region.USE_PREF_SIZE);
    VBox content = new VBox(10, file, text);
    if (used) {
      ListView<FileUsages.Usage> list = list(usages, view -> new UsageCell(root));
      content.getChildren().add(list);
      VBox.setVgrow(list, Priority.ALWAYS);
    }
    alert.getDialogPane().setContent(content);
    alert.getDialogPane().setPrefWidth(used ? 760 : 520);
    finish(alert, owner, alert.getButtonTypes().toArray(ButtonType[]::new));
    // Enter must not delete: Cancel is the default here
    ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(cancel))
        .setDefaultButton(true);
    ButtonType answer = alert.showAndWait().orElse(cancel);
    return answer == delete ? Choice.DELETE : answer == show ? Choice.SHOW : Choice.CANCEL;
  }

  private static ListView<FileUsages.Usage> list(List<FileUsages.Usage> usages,
      javafx.util.Callback<ListView<FileUsages.Usage>, ListCell<FileUsages.Usage>> cells) {
    ListView<FileUsages.Usage> list = new ListView<>();
    list.getStyleClass().add("usages-list");
    list.getItems().setAll(usages);
    list.setCellFactory(cells);
    list.setFixedCellSize(34);
    list.setPrefHeight(Math.min(8, usages.size()) * 34 + 4);
    list.setMinHeight(Region.USE_PREF_SIZE);
    list.setPrefWidth(680);
    list.setFocusTraversable(false);
    return list;
  }

  private static void finish(Alert alert, Window owner, ButtonType... buttons) {
    // long labels ("Rename and update paths") are not cut off
    for (ButtonType buttonType : buttons) {
      var button = alert.getDialogPane().lookupButton(buttonType);
      if (button instanceof javafx.scene.control.Button b) {
        b.setMinWidth(Region.USE_PREF_SIZE);
        ButtonBar.setButtonUniformSize(b, false);
      }
    }
    alert.setResizable(true);
    Theme.style(alert, owner);
  }

  /** "Where the path sits now" and "what it becomes", for the preview. */
  private static String replaced(String hit, String from, String to) {
    // a folder's file: the folder part changes, the rest stays
    return hit.startsWith(from) ? to + hit.substring(from.length()) : to;
  }

  /**
   * A preview row: a check box (on: the IDE changes it), the place, and the
   * line with the old path struck through and the new one after it. A usage
   * the IDE cannot change gets a warning sign instead of the check box.
   */
  private static final class PreviewCell extends ListCell<FileUsages.Usage> {
    private final Path root;
    private final String from;
    private final String to;
    private final Set<FileUsages.Usage> skipped;

    PreviewCell(Path root, String from, String to, Set<FileUsages.Usage> skipped) {
      this.root = root;
      this.from = from;
      this.to = to;
      this.skipped = skipped;
      setPrefWidth(0);
    }

    @Override protected void updateItem(FileUsages.Usage usage, boolean empty) {
      super.updateItem(usage, empty);
      if (empty || usage == null) {
        setText(null);
        setGraphic(null);
        setTooltip(null);
        return;
      }
      Node mark;
      String line = usage.lineText();
      int start = Math.max(0, Math.min(usage.from(), line.length()));
      int end = Math.max(start, Math.min(usage.to(), line.length()));
      String hit = line.substring(start, end);
      List<Label> parts = new ArrayList<>();
      parts.add(new Label(line.substring(0, start)));
      if (usage.updatable()) {
        CheckBox box = new CheckBox();
        box.setSelected(!skipped.contains(usage));
        Label old = new Label(hit);
        Label now = new Label(replaced(hit, from, to));
        now.getStyleClass().add("usage-new");
        old.getStyleClass().add(box.isSelected() ? "usage-old" : "search-hit");
        now.setVisible(box.isSelected());
        now.setManaged(box.isSelected());
        box.setOnAction(e -> {
          if (box.isSelected()) skipped.remove(usage); else skipped.add(usage);
          getListView().refresh();
        });
        mark = box;
        parts.add(old);
        parts.add(now);
        setTooltip(new javafx.scene.control.Tooltip(I18n.t("usages.tip.preview")));
      } else {
        var icon = Icons.of("fth-alert-triangle", 14);
        icon.getStyleClass().add("usage-manual");
        mark = icon;
        Label shown = new Label(hit);
        shown.getStyleClass().add("search-hit");
        parts.add(shown);
        setTooltip(new javafx.scene.control.Tooltip(I18n.t("usages.tip.manual")));
      }
      Label after = new Label(line.substring(end));
      parts.add(after);
      for (Label part : parts) {
        part.getStyleClass().add("search-line");
        part.setMinWidth(Region.USE_PREF_SIZE);
      }
      after.setMinWidth(0);
      String where = shown(root, usage.file()) + (usage.line() > 0 ? ":" + usage.line() : "");
      Label location = new Label(where);
      location.getStyleClass().add("problem-location");
      location.setMinWidth(Region.USE_PREF_SIZE);
      HBox code = new HBox(parts.toArray(Node[]::new));
      code.setAlignment(Pos.CENTER_LEFT);
      code.setMinWidth(0);
      HBox.setHgrow(code, Priority.ALWAYS);
      HBox row = new HBox(8, mark, location, code);
      row.setAlignment(Pos.CENTER_LEFT);
      row.setMinWidth(0);
      setText(null);
      setGraphic(row);
    }
  }

  private static String shown(Path root, Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    Path base = root.toAbsolutePath().normalize();
    Path relative = absolute.startsWith(base) ? base.relativize(absolute) : file.getFileName();
    return relative.toString().replace('\\', '/');
  }

  /** "Cat.java:4  say("assets/images/cat.png is me");" with a mark for what updates. */
  private static final class UsageCell extends ListCell<FileUsages.Usage> {
    private final Path root;

    UsageCell(Path root) {
      this.root = root;
      setPrefWidth(0);
    }

    @Override protected void updateItem(FileUsages.Usage usage, boolean empty) {
      super.updateItem(usage, empty);
      if (empty || usage == null) {
        setText(null);
        setGraphic(null);
        setTooltip(null);
        return;
      }
      var icon = Icons.of("fth-alert-triangle", 14);
      icon.getStyleClass().add("usage-manual");
      String where = shown(root, usage.file()) + (usage.line() > 0 ? ":" + usage.line() : "");
      Label location = new Label(where);
      location.getStyleClass().add("problem-location");
      location.setMinWidth(Region.USE_PREF_SIZE);
      String line = usage.lineText();
      int from = Math.max(0, Math.min(usage.from(), line.length()));
      int to = Math.max(from, Math.min(usage.to(), line.length()));
      Label before = new Label(line.substring(0, from));
      Label hit = new Label(line.substring(from, to));
      hit.getStyleClass().add("search-hit");
      Label after = new Label(line.substring(to));
      for (Label part : List.of(before, hit, after)) {
        part.getStyleClass().add("search-line");
        part.setMinWidth(Region.USE_PREF_SIZE);
      }
      after.setMinWidth(0);
      HBox code = new HBox(before, hit, after);
      code.setAlignment(Pos.CENTER_LEFT);
      code.setMinWidth(0);
      HBox.setHgrow(code, Priority.ALWAYS);
      HBox row = new HBox(8, icon, location, code);
      row.setAlignment(Pos.CENTER_LEFT);
      row.setMinWidth(0);
      setText(null);
      setGraphic(row);
      setTooltip(new javafx.scene.control.Tooltip(I18n.t("usages.delete.tip")));
    }
  }
}
