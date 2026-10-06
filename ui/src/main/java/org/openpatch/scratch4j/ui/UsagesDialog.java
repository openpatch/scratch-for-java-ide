package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.project.FileUsages;

import java.nio.file.Path;
import java.util.List;

/**
 * Asks before a move, rename or delete touches a file that the project uses:
 * lists every usage (which ones the IDE updates by itself, which ones need a
 * look by hand) and lets the user decide.
 */
final class UsagesDialog {

  /** What the user chose. */
  enum Choice { UPDATE, IGNORE, DELETE, SHOW, CANCEL }

  private UsagesDialog() {}

  /**
   * Before a move or rename: "Update paths" (the default), "Don't update" or
   * Cancel. {@code what} is the file or folder name, {@code verb} the action
   * ("move", "rename") as shown on the button.
   */
  static Choice forMove(Path root, String what, List<FileUsages.Usage> usages, boolean rename) {
    long updatable = usages.stream().filter(FileUsages.Usage::updatable).count();
    long manual = usages.size() - updatable;
    ButtonType update = new ButtonType(I18n.t(rename ? "usages.rename.update"
        : "usages.move.update"), ButtonBar.ButtonData.OK_DONE);
    ButtonType ignore = new ButtonType(I18n.t(rename ? "usages.rename.only"
        : "usages.move.only"), ButtonBar.ButtonData.OTHER);
    ButtonType cancel = I18n.cancel();
    String summary = updatable > 0 && manual > 0
        ? I18n.t("usages.summary.mixed", updatable, manual)
        : manual > 0 ? I18n.t("usages.summary.manual", manual)
        : I18n.t("usages.summary.updatable", updatable);
    Alert alert = alert(Alert.AlertType.CONFIRMATION,
        I18n.t("usages.header", what, usages.size()), summary, root, usages,
        update, ignore, cancel);
    if (updatable == 0) {
      // nothing the IDE could update: the choice is just "go ahead" or not
      alert.getButtonTypes().remove(update);
      ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(ignore))
          .setDefaultButton(true);
    }
    ButtonType answer = alert.showAndWait().orElse(cancel);
    return answer == update ? Choice.UPDATE : answer == ignore ? Choice.IGNORE : Choice.CANCEL;
  }

  /** Before deleting something that is still used: "Delete anyway", "Show usages" or Cancel. */
  static Choice forDelete(Path root, String what, List<FileUsages.Usage> usages) {
    ButtonType delete = new ButtonType(I18n.t("usages.delete.anyway"),
        ButtonBar.ButtonData.OTHER);
    ButtonType show = new ButtonType(I18n.t("usages.show"), ButtonBar.ButtonData.OTHER);
    ButtonType cancel = I18n.cancel();
    Alert alert = alert(Alert.AlertType.WARNING,
        I18n.t("usages.delete.header", what, usages.size()),
        I18n.t("usages.delete.summary"), root, usages, delete, show, cancel);
    // Enter must not delete: Cancel is the default here
    ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(cancel))
        .setDefaultButton(true);
    ButtonType answer = alert.showAndWait().orElse(cancel);
    return answer == delete ? Choice.DELETE : answer == show ? Choice.SHOW : Choice.CANCEL;
  }

  private static Alert alert(Alert.AlertType type, String header, String summary, Path root,
      List<FileUsages.Usage> usages, ButtonType... buttons) {
    Alert alert = new Alert(type, "", buttons);
    alert.setTitle(I18n.t("usages.title.dialog"));
    alert.setHeaderText(header);
    Label text = new Label(summary);
    text.setWrapText(true);
    text.setMinHeight(Region.USE_PREF_SIZE);
    ListView<FileUsages.Usage> list = new ListView<>();
    list.getStyleClass().add("usages-list");
    list.getItems().setAll(usages);
    list.setCellFactory(view -> new UsageCell(root));
    list.setFixedCellSize(34);
    list.setPrefHeight(Math.min(8, usages.size()) * 34 + 4);
    list.setMinHeight(Region.USE_PREF_SIZE);
    list.setPrefWidth(680);
    list.setFocusTraversable(false);
    VBox content = new VBox(10, text, list);
    VBox.setVgrow(list, Priority.ALWAYS);
    alert.getDialogPane().setContent(content);
    alert.getDialogPane().setPrefWidth(720);
    // long labels ("Rename and update paths") are not cut off
    for (ButtonType buttonType : buttons) {
      var button = alert.getDialogPane().lookupButton(buttonType);
      if (button instanceof javafx.scene.control.Button b) {
        b.setMinWidth(Region.USE_PREF_SIZE);
        ButtonBar.setButtonUniformSize(b, false);
      }
    }
    alert.setResizable(true);
    Theme.style(alert);
    return alert;
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
      var icon = Icons.of(usage.updatable() ? "fth-check-circle" : "fth-alert-triangle", 14);
      icon.getStyleClass().add(usage.updatable() ? "usage-updatable" : "usage-manual");
      String where = shown(usage.file()) + (usage.line() > 0 ? ":" + usage.line() : "");
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
      setTooltip(new javafx.scene.control.Tooltip(I18n.t(usage.updatable()
          ? "usages.tip.updatable" : "usages.tip.manual")));
    }

    private String shown(Path file) {
      Path absolute = file.toAbsolutePath().normalize();
      Path base = root.toAbsolutePath().normalize();
      Path relative = absolute.startsWith(base) ? base.relativize(absolute) : file.getFileName();
      return relative.toString().replace('\\', '/');
    }
  }
}
