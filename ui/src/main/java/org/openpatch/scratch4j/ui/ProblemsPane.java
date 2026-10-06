package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

/**
 * The problems list: compiler errors (with beginner explanations) and asset
 * lints (with "did you mean"). Clicking a problem jumps to its line.
 */
final class ProblemsPane extends ListView<Problem> {

  ProblemsPane(Consumer<Problem> onOpen) {
    getStyleClass().add("problems");
    Label empty = new Label(I18n.t("problems.none"), Icons.of("fth-check-circle", 18));
    empty.getStyleClass().add("problems-empty");
    setPlaceholder(empty);
    setCellFactory(view -> new ListCell<>() {
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
          javafx.scene.control.Button fix = new javafx.scene.control.Button(
              I18n.t("problems.fix." + problem.fix()), Icons.of("fth-tool"));
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
    setOnMouseClicked(e -> {
      Problem problem = getSelectionModel().getSelectedItem();
      if (problem != null && problem.file() != null && Files.isRegularFile(problem.file())) {
        onOpen.accept(problem);
      }
    });
  }

  private java.util.function.Consumer<Problem> onFix = p -> { };

  /** Runs a problem's fix (the button under it). */
  void setOnFix(java.util.function.Consumer<Problem> action) {
    this.onFix = action;
  }

  void setProblems(List<Problem> problems) {
    getItems().setAll(problems);
  }

  long errorCount() {
    return getItems().stream().filter(Problem::error).count();
  }
}
