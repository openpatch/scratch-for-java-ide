package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

/**
 * The problems list: compiler errors (with beginner explanations) and asset
 * lints (with "did you mean"). Clicking a problem jumps to its line. The
 * placeholder says what to do next while the list is empty, and a bar above
 * the list offers the last version that ran when the project has been broken
 * for a while.
 */
final class ProblemsPane extends BorderPane {

  private final ListView<Problem> list = new ListView<>();
  private final Label empty = new Label("", Icons.of("fth-check-circle", 18));
  private final Label stuck = new Label();
  private final Button restore = new Button(I18n.t("problems.stuck.restore"),
      Icons.of("fth-rotate-ccw"));
  private final HBox stuckBar = new HBox(10, stuck, restore);
  private Consumer<Problem> onFix = p -> { };

  ProblemsPane(Consumer<Problem> onOpen) {
    getStyleClass().add("problems-pane");
    list.getStyleClass().add("problems");
    empty.getStyleClass().add("problems-empty");
    empty.setWrapText(true);
    empty.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
    setPlaceholder(I18n.t("problems.none"));
    list.setPlaceholder(empty);
    list.setCellFactory(view -> new ListCell<>() {
      {
        // wrap to the list width instead of scrolling sideways
        setPrefWidth(0);
      }

      @Override
      protected void updateItem(Problem problem, boolean empty) {
        super.updateItem(problem, empty);
        if (empty || problem == null) {
          setText(null);
          setGraphic(null);
          return;
        }
        var icon = Icons.of(problem.isHint() ? "fth-zap"
            : problem.error() ? "fth-x-circle" : "fth-alert-triangle", 16);
        icon.getStyleClass().add(problem.isHint() ? "problem-hint"
            : problem.error() ? "problem-error" : "problem-warning");
        Label message = new Label(problem.message().strip());
        message.getStyleClass().add("problem-message");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        Label location = new Label(problem.location());
        location.getStyleClass().add("problem-location");
        location.setMinWidth(Region.USE_PREF_SIZE);
        VBox text = new VBox(2, message);
        if (problem.explanation() != null && !problem.explanation().isBlank()) {
          Label explanation = new Label(problem.explanation());
          explanation.setWrapText(true);
          explanation.getStyleClass().add("problem-explanation");
          explanation.setMinHeight(Region.USE_PREF_SIZE);
          text.getChildren().add(explanation);
        }
        if (problem.suggestions() != null && !problem.suggestions().isEmpty()) {
          Label suggestions = new Label(I18n.t("problems.didyoumean") + " "
              + String.join(", ", problem.suggestions()));
          suggestions.getStyleClass().add("problem-suggestion");
          text.getChildren().add(suggestions);
        }
        if (problem.original() != null) {
          Label original = new Label(I18n.t("problems.original", problem.original().strip()));
          original.setWrapText(true);
          original.getStyleClass().add("problem-original");
          original.setMinHeight(Region.USE_PREF_SIZE);
          text.getChildren().add(original);
        }
        if (problem.followUp()) {
          Label followUp = new Label(I18n.t("problems.followup"));
          followUp.setWrapText(true);
          followUp.getStyleClass().add("problem-followup-note");
          followUp.setMinHeight(Region.USE_PREF_SIZE);
          text.getChildren().add(followUp);
        }
        if (problem.fix() != null) {
          Button fix = new Button(problem.fixLabel(), Icons.of("fth-tool"));
          fix.getStyleClass().add("problem-fix");
          fix.setOnAction(e -> onFix.accept(problem));
          text.getChildren().add(fix);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, icon, text, location);
        row.setMinWidth(0);
        // the error that caused it stands out; its echoes step back
        row.setOpacity(problem.followUp() ? 0.6 : 1);
        row.setAlignment(Pos.TOP_LEFT);
        setText(null);
        setGraphic(row);
      }
    });
    list.setOnMouseClicked(e -> {
      Problem problem = list.getSelectionModel().getSelectedItem();
      if (problem != null && problem.file() != null && Files.isRegularFile(problem.file())) {
        onOpen.accept(problem);
      }
    });

    stuck.setWrapText(true);
    stuck.setMinHeight(Region.USE_PREF_SIZE);
    HBox.setHgrow(stuck, Priority.ALWAYS);
    stuck.setMaxWidth(Double.MAX_VALUE);
    restore.setMinWidth(Region.USE_PREF_SIZE);
    stuckBar.getStyleClass().add("problems-stuck");
    stuckBar.setAlignment(Pos.CENTER_LEFT);
    stuckBar.setVisible(false);
    stuckBar.setManaged(false);
    setTop(stuckBar);
    setCenter(list);
  }

  /** Runs a problem's fix (the button under it). */
  void setOnFix(Consumer<Problem> action) {
    this.onFix = action;
  }

  void setProblems(List<Problem> problems) {
    list.getItems().setAll(problems);
  }

  long errorCount() {
    return list.getItems().stream().filter(Problem::error).count();
  }

  /** What the empty list says: open a project, run the program, no problems. */
  void setPlaceholder(String text) {
    empty.setText(text);
  }

  /**
   * Offers the version that ran at {@code when} (HH:mm) above the list; the
   * button runs {@code action}. Hidden again with {@link #hideStuck()}.
   */
  void showStuck(String when, Runnable action) {
    stuck.setText(I18n.t("problems.stuck", when));
    restore.setOnAction(e -> action.run());
    stuckBar.setVisible(true);
    stuckBar.setManaged(true);
  }

  void hideStuck() {
    stuckBar.setVisible(false);
    stuckBar.setManaged(false);
  }

  boolean stuckShowing() {
    return stuckBar.isVisible();
  }
}
