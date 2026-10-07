package org.openpatch.scratch4j.ui;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.openpatch.scratch4j.core.project.ScratchMigration.Task;

/** Clickable review tasks show the generated Java and the preserved Scratch block. */
final class ScratchMigrationPane extends ListView<Task> {
  private Path root;
  ScratchMigrationPane(BiConsumer<Path, Long> open, Consumer<String> browse) {
    setPlaceholder(new Label(I18n.t("migration.empty")));
    setCellFactory(view -> new ListCell<>() {
      { setPrefWidth(0); }
      protected void updateItem(Task task, boolean empty) {
        super.updateItem(task, empty);
        setText(null);
        if (empty || task == null) { setGraphic(null); return; }
        Label message = new Label(task.target() + ": " + task.message());
        message.setWrapText(true);
        Button java = new Button(task.javaFile() + ":" + task.line());
        java.setOnAction(e -> open.accept(root.resolve(task.javaFile()), (long) task.line()));
        Button original = new Button(I18n.t("migration.original"));
        original.setOnAction(e -> {
          TextArea text = new TextArea(task.originalBlock());
          text.setEditable(false);
          text.setWrapText(true);
          Dialog<Void> dialog = new Dialog<>();
          dialog.setTitle(task.target() + " / " + task.blockId() + " / " + task.opcode());
          dialog.getDialogPane().setContent(text);
          dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
          Theme.style(dialog);
          dialog.showAndWait();
        });
        Hyperlink lesson = new Hyperlink(I18n.t("migration.lesson"));
        lesson.setOnAction(e -> {
          // Imported metadata cannot launch a different URL scheme or domain.
          if (task.lessonUrl().startsWith("https://scratch4j.openpatch.org/migration#"))
            browse.accept(task.lessonUrl());
        });
        VBox content = new VBox(6, message, new HBox(8, java, original, lesson));
        content.setMinWidth(0);
        setGraphic(content);
      }
    });
    setOnMouseClicked(e -> {
      Task task = getSelectionModel().getSelectedItem();
      if (task != null && root != null) open.accept(root.resolve(task.javaFile()), (long) task.line());
    });
  }
  void setTasks(Path root, List<Task> tasks) {
    this.root = root;
    getItems().setAll(tasks);
  }
}
