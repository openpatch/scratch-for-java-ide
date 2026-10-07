package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.openpatch.scratch4j.core.io.LineDiff;
import org.openpatch.scratch4j.core.io.ProjectSnapshots;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;

/**
 * The project's versions: one after every run that worked, one before every
 * restore. A version shows what changed since then (line by line) and can be
 * restored as a whole or file by file.
 */
final class VersionsDialog {

  private VersionsDialog() {}

  /** Shows the dialog; {@code onRestored} runs after files changed on disk. */
  static void show(Path root, Runnable onRestored) {
    build(root, onRestored).showAndWait();
  }

  static Dialog<ButtonType> build(Path root, Runnable onRestored) {
    List<ProjectSnapshots.Version> versions;
    try {
      versions = ProjectSnapshots.list(root);
    } catch (IOException e) {
      versions = List.of();
    }
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle(I18n.t("versions.title"));
    dialog.setHeaderText(I18n.t("versions.header"));
    dialog.getDialogPane().getButtonTypes().add(new ButtonType(I18n.t("versions.close"),
        ButtonType.CLOSE.getButtonData()));
    Theme.style(dialog);

    ListView<ProjectSnapshots.Version> list = new ListView<>();
    list.getItems().setAll(versions);
    list.setPrefWidth(260);
    DateTimeFormatter time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(I18n.current() == I18n.Language.DE ? java.util.Locale.GERMAN
            : java.util.Locale.ENGLISH).withZone(ZoneId.systemDefault());
    list.setCellFactory(v -> new ListCell<>() {
      @Override
      protected void updateItem(ProjectSnapshots.Version version, boolean empty) {
        super.updateItem(version, empty);
        if (empty || version == null) {
          setText(null);
          setGraphic(null);
          return;
        }
        Label when = new Label(time.format(version.time()));
        Label why = new Label(I18n.t("versions.kind." + version.kind().name().toLowerCase()));
        why.getStyleClass().add("text-muted");
        setGraphic(new VBox(2, when, why));
        setText(null);
      }
    });

    ListView<String> files = new ListView<>();
    files.setPrefHeight(140);
    StyleClassedTextArea diff = new StyleClassedTextArea();
    diff.setEditable(false);
    diff.getStyleClass().add("version-diff");
    Label summary = new Label();
    summary.setWrapText(true);
    Button restoreFile = new Button(I18n.t("versions.restore.file"), Icons.of("fth-file"));
    Button restoreAll = new Button(I18n.t("versions.restore"), Icons.of("fth-rotate-ccw"));
    restoreAll.getStyleClass().add("accent");
    Map<String, String[]>[] changes = new Map[] {Map.of()};

    Runnable showFile = () -> {
      String path = files.getSelectionModel().getSelectedItem();
      diff.clear();
      restoreFile.setDisable(path == null);
      if (path == null) return;
      String[] texts = changes[0].get(path);
      List<LineDiff.Line> lines = LineDiff.diff(texts[0], texts[1]);
      // only the changes, with three lines around each
      boolean[] shown = new boolean[lines.size()];
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).kind() != ' ') {
          for (int k = Math.max(0, i - 3); k <= Math.min(lines.size() - 1, i + 3); k++) {
            shown[k] = true;
          }
        }
      }
      boolean gap = false;
      for (int i = 0; i < lines.size(); i++) {
        if (!shown[i]) {
          gap = true;
          continue;
        }
        if (gap && diff.getLength() > 0) {
          int at = diff.getLength();
          diff.appendText("  \u22ef\n");
          diff.setStyleClass(at, diff.getLength(), "diff-same");
        }
        gap = false;
        LineDiff.Line line = lines.get(i);
        int start = diff.getLength();
        int number = line.newLine() > 0 ? line.newLine() : line.oldLine();
        String text = String.format("%4d %c %s%n", number, line.kind(), line.text());
        diff.appendText(text);
        diff.setStyleClass(start, start + text.length(), switch (line.kind()) {
          case '+' -> "diff-added";
          case '-' -> "diff-removed";
          default -> "diff-same";
        });
      }
      diff.moveTo(0);
      diff.requestFollowCaret();
    };
    files.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showFile.run());
    list.getSelectionModel().selectedItemProperty().addListener((o, a, version) -> {
      restoreAll.setDisable(version == null);
      if (version == null) return;
      try {
        changes[0] = ProjectSnapshots.changes(root, version);
      } catch (IOException e) {
        changes[0] = Map.of();
      }
      files.getItems().setAll(changes[0].keySet());
      summary.setText(changes[0].isEmpty() ? I18n.t("versions.same")
          : I18n.t("versions.changed", changes[0].size()));
      if (!files.getItems().isEmpty()) files.getSelectionModel().selectFirst();
      showFile.run();
    });
    restoreAll.setDisable(true);
    restoreFile.setDisable(true);
    restoreAll.setOnAction(e -> {
      var version = list.getSelectionModel().getSelectedItem();
      if (version == null || !confirm(I18n.t("versions.restore.confirm",
          time.format(version.time())))) {
        return;
      }
      try {
        ProjectSnapshots.restore(root, version);
        onRestored.run();
        dialog.close();
      } catch (IOException ex) {
        error(ex.getMessage());
      }
    });
    restoreFile.setOnAction(e -> {
      String path = files.getSelectionModel().getSelectedItem();
      if (path == null) return;
      String then = changes[0].get(path)[0];
      try {
        if (then == null) {
          // the file did not exist then: it goes into the per-file history and away
          org.openpatch.scratch4j.core.io.LocalHistory.snapshot(root, root.resolve(path));
          java.nio.file.Files.deleteIfExists(root.resolve(path));
        } else {
          ProjectSnapshots.restoreFile(root, path, then);
        }
        onRestored.run();
        int index = list.getSelectionModel().getSelectedIndex();
        list.getSelectionModel().clearSelection();
        list.getSelectionModel().select(index);
      } catch (IOException ex) {
        error(ex.getMessage());
      }
    });

    Label empty = new Label(I18n.t("versions.none"));
    empty.setWrapText(true);
    list.setPlaceholder(empty);
    restoreFile.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    restoreAll.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    HBox actions = new HBox(8, restoreFile, restoreAll);
    VBox right = new VBox(8, summary, files, diff, actions);
    VBox.setVgrow(diff, Priority.ALWAYS);
    right.setPadding(new Insets(0, 0, 0, 8));
    SplitPane split = new SplitPane(list, right);
    split.setDividerPositions(0.3);
    BorderPane content = new BorderPane(split);
    javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
    double width = Math.min(980, screen.getWidth() - 40);
    double height = Math.min(680, screen.getHeight() - 60);
    content.setPrefSize(width - 40, height - 100);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().setPrefSize(width, height);
    if (!versions.isEmpty()) list.getSelectionModel().selectFirst();
    dialog.setResizable(true);
    return dialog;
  }

  private static boolean confirm(String text) {
    Alert ask = new Alert(Alert.AlertType.CONFIRMATION, text, I18n.ok(), I18n.cancel());
    ask.setHeaderText(null);
    Theme.style(ask);
    return ask.showAndWait().orElse(null) == I18n.ok();
  }

  private static void error(String text) {
    Alert alert = new Alert(Alert.AlertType.ERROR, text, I18n.ok());
    alert.setHeaderText(null);
    Theme.style(alert);
    alert.showAndWait();
  }
}
