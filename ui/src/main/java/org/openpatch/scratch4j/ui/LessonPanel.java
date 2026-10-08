package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.openpatch.scratch4j.core.lesson.Lesson;
import org.openpatch.scratch4j.core.lesson.LessonProgress;

import java.util.List;
import java.util.Locale;

/**
 * The lesson beside the project (a sidebar tab): the lesson's title and how
 * far along the student is, then its steps. Done steps show a tick, the
 * current one is open with its explanation and code to copy, later ones are
 * only titles. The IDE checks the steps; the panel only shows them.
 */
final class LessonPanel extends ScrollPane {

  private final VBox content = new VBox(10);
  private LessonProgress progress;
  private Runnable onRestart = () -> { };

  LessonPanel() {
    getStyleClass().add("lesson-panel");
    content.getStyleClass().add("lesson-content");
    setContent(content);
    setFitToWidth(true);
    setHbarPolicy(ScrollBarPolicy.NEVER);
  }

  void setOnRestart(Runnable action) {
    this.onRestart = action;
  }

  LessonProgress progress() {
    return progress;
  }

  void show(LessonProgress progress) {
    this.progress = progress;
    refresh();
  }

  /** Draws the steps as they are now (after a check or a run). */
  void refresh() {
    content.getChildren().clear();
    if (progress == null) {
      return;
    }
    Locale locale = I18n.current() == I18n.Language.DE ? Locale.GERMAN : Locale.ENGLISH;
    Lesson lesson = progress.lesson();
    List<Lesson.Step> steps = lesson.steps();
    int current = progress.current();

    Label title = new Label(lesson.title(locale));
    title.getStyleClass().add("lesson-title");
    title.setWrapText(true);
    ProgressBar bar = new ProgressBar((double) current / steps.size());
    bar.setMaxWidth(Double.MAX_VALUE);
    bar.getStyleClass().add("lesson-progress");
    Label where = new Label(progress.finished() ? I18n.t("lesson.finished.title")
        : I18n.t("lesson.step", current + 1, steps.size()));
    where.getStyleClass().add("lesson-where");
    content.getChildren().addAll(title, bar, where);

    if (progress.finished()) {
      Label done = new Label(I18n.t("lesson.finished", lesson.title(locale)));
      done.setWrapText(true);
      done.getStyleClass().add("lesson-finished");
      done.setMinHeight(Region.USE_PREF_SIZE);
      content.getChildren().add(done);
    }
    for (int i = 0; i < steps.size(); i++) {
      content.getChildren().add(card(steps.get(i), i, current, locale));
    }
    Hyperlink restart = new Hyperlink(I18n.t("lesson.restart"));
    restart.setGraphic(Icons.of("fth-rotate-ccw"));
    restart.setOnAction(e -> onRestart.run());
    content.getChildren().add(restart);
  }

  private VBox card(Lesson.Step step, int index, int current, Locale locale) {
    boolean done = progress.isDone(step);
    boolean now = index == current;
    Label number = new Label(done ? null : String.valueOf(index + 1),
        done ? Icons.of("fth-check", 14) : null);
    number.getStyleClass().add("lesson-number");
    Label heading = new Label(step.title(locale));
    heading.getStyleClass().add("lesson-step-title");
    heading.setWrapText(true);
    heading.setMinHeight(Region.USE_PREF_SIZE);
    HBox.setHgrow(heading, Priority.ALWAYS);
    HBox head = new HBox(8, number, heading);
    head.setAlignment(Pos.CENTER_LEFT);
    VBox card = new VBox(6, head);
    card.getStyleClass().addAll("lesson-step", done ? "done" : now ? "current" : "later");
    if (now) {
      Label text = new Label(step.text(locale));
      text.setWrapText(true);
      text.setMinHeight(Region.USE_PREF_SIZE);
      text.getStyleClass().add("lesson-text");
      card.getChildren().add(text);
      if (step.code() != null) {
        TextArea code = new TextArea(step.code());
        code.setEditable(false);
        code.setWrapText(false);
        code.getStyleClass().add("lesson-code");
        code.setPrefRowCount((int) step.code().lines().count());
        Button copy = new Button(I18n.t("lesson.copy"), Icons.of("fth-copy"));
        copy.getStyleClass().add("lesson-copy");
        copy.setOnAction(e -> {
          ClipboardContent clip = new ClipboardContent();
          clip.putString(step.code());
          javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
          copy.setText(I18n.t("lesson.copied"));
        });
        card.getChildren().addAll(code, copy);
      }
    }
    return card;
  }
}
