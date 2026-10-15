package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A small window over the main one while an import runs on a worker thread:
 * what is imported, a progress bar and the file or costume it is on. The bar
 * moves back and forth until the import says how far it is. The main window
 * takes no input meanwhile. Cancel (or closing the window) stops the import
 * at its next step; the window goes when the import ends.
 */
final class ProgressWindow {

  private record Step(String item, int done, int total) {}

  /** Thrown into the import from {@link #step} once it is cancelled. */
  static final class Cancelled extends RuntimeException {
    Cancelled() {
      super("cancelled", null, false, false);
    }
  }

  private final Stage window = new Stage(StageStyle.UTILITY);
  private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
  private final Label detail = new Label(I18n.t("import.preparing"));
  /** The newest step not shown yet: a busy import posts one repaint, not one per file. */
  private final AtomicReference<Step> pending = new AtomicReference<>();
  private final javafx.scene.control.Button cancel = new javafx.scene.control.Button(
      I18n.t("button.cancel"));
  private volatile boolean cancelled;

  ProgressWindow(Window owner, String what) {
    Label title = new Label(what);
    title.getStyleClass().add("form-label");
    title.setWrapText(true);
    bar.setMaxWidth(Double.MAX_VALUE);
    detail.getStyleClass().add("form-hint");
    detail.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> cancel());
    javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(cancel);
    buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
    VBox content = new VBox(10, title, bar, detail, buttons);
    content.setPadding(new Insets(18));
    content.setPrefWidth(420);
    Scene scene = new Scene(content);
    Theme.style(scene);
    window.setScene(scene);
    window.setTitle(I18n.t("import.title"));
    window.setResizable(false);
    if (owner != null) {
      window.initOwner(owner);
      window.initModality(Modality.WINDOW_MODAL);
    }
    window.setOnCloseRequest(e -> {
      e.consume(); // the window goes when the import has stopped
      cancel();
    });
    window.getProperties().put("bar", bar);
    window.getProperties().put("detail", detail);
  }

  /** The window itself, for tests. */
  Stage window() {
    return window;
  }

  /** Asks the import to stop: it ends at its next step. */
  void cancel() {
    cancelled = true;
    cancel.setDisable(true);
    bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
    detail.setText(I18n.t("import.cancelling"));
  }

  boolean cancelled() {
    return cancelled;
  }

  void show() {
    window.show();
  }

  /**
   * From any thread: {@code done} of {@code total} steps, now on {@code item}.
   * Throws {@link Cancelled} once the import is cancelled.
   */
  void step(String item, int done, int total) {
    if (cancelled) {
      throw new Cancelled();
    }
    if (pending.getAndSet(new Step(item, done, total)) == null) {
      Platform.runLater(this::showStep);
    }
  }

  private void showStep() {
    Step step = pending.getAndSet(null);
    if (step == null || cancelled) {
      return;
    }
    if (step.total() > 0) {
      bar.setProgress((double) step.done() / step.total());
      detail.setText(I18n.t("import.step", step.done() + 1, step.total(), step.item()));
    } else {
      bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
      detail.setText(step.item());
    }
  }

  void close() {
    pending.set(null);
    window.hide();
  }
}
