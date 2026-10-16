package org.openpatch.scratch4j.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A card over the work area while a project opens: its name, a progress bar
 * and the step it is on. It takes the clicks meanwhile, so nothing half
 * loaded is used. The opening runs in steps on the FX thread; {@link #next}
 * lets a frame be drawn between two of them, so the card shows each step.
 */
final class LoadingOverlay extends StackPane {

  private final Label title = new Label();
  private final ProgressBar bar = new ProgressBar(0);
  private final Label detail = new Label();

  LoadingOverlay() {
    getStyleClass().add("loading-overlay");
    title.getStyleClass().add("loading-title");
    title.setWrapText(true);
    detail.getStyleClass().add("form-hint");
    bar.setMaxWidth(Double.MAX_VALUE);
    VBox card = new VBox(10, title, bar, detail);
    card.getStyleClass().add("loading-card");
    card.setMaxSize(380, USE_PREF_SIZE);
    card.setPrefWidth(380);
    getChildren().add(card);
    StackPane.setAlignment(card, Pos.CENTER);
    setVisible(false);
  }

  /** Shows the card: {@code what} is loading, now at {@code step} ({@code progress} 0..1). */
  void show(String what, String step, double progress) {
    title.setText(what);
    step(step, progress);
    setVisible(true);
  }

  void step(String step, double progress) {
    detail.setText(step);
    bar.setProgress(progress);
  }

  void hide() {
    setVisible(false);
  }

  /** Whether the card is up (tests). */
  boolean showing() {
    return isVisible();
  }

  /**
   * Runs {@code action} after the next frame: a runLater alone may run before
   * the card was drawn, a short pause lets the frame through.
   */
  static void next(Runnable action) {
    PauseTransition frame = new PauseTransition(Duration.millis(20));
    frame.setOnFinished(e -> action.run());
    frame.play();
  }
}
