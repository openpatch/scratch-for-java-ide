package org.openpatch.scratch4j.ui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** One hint at a time, shared by problem cards and lesson steps. State survives list refreshes. */
final class HintView extends VBox {
  static final class State {
    private int level;
    private boolean open;

    void show(int level, int total, IntConsumer onReveal) {
      this.level = Math.clamp(level, 1, total);
      open = true;
      onReveal.accept(this.level);
    }
    void advance(int total, IntConsumer onReveal) { show(level + 1, total, onReveal); }
  }

  private final State state;
  private final int total;
  private final IntFunction<String> hints;
  private final IntPredicate codeAt;
  private final IntConsumer onReveal;
  private final Button trigger = new Button(I18n.t("hints.button"), Icons.of("fth-help-circle", 12));
  private final Label progress = new Label();
  private final Label body = new Label();
  // Measure at the offered width before layout; short hints shrink, long hints scroll.
  private final ScrollPane scroll = new ScrollPane(body) {
    @Override public Orientation getContentBias() { return Orientation.HORIZONTAL; }
    @Override protected double computePrefHeight(double width) {
      return Math.clamp(body.prefHeight(Math.max(100, width - 16)), 18, 88)
          + snappedTopInset() + snappedBottomInset();
    }
  };
  private final Button previous;
  private final Button next;

  HintView(State state, int total, IntFunction<String> hints, IntPredicate codeAt, IntConsumer onReveal) {
    this.state = state; this.total = total; this.hints = hints; this.codeAt = codeAt; this.onReveal = onReveal;
    getStyleClass().add("hint-panel");
    setSpacing(4);
    trigger.getStyleClass().addAll("flat", "compact-action", "problem-hint-button");
    trigger.setOnAction(e -> {
      if (state.open) state.open = false;
      else state.show(Math.max(1, state.level), total, onReveal);
      refresh();
    });
    previous = navigation("fth-chevron-left", "hints.previous", () -> show(state.level - 1));
    previous.getStyleClass().add("hint-previous");
    next = navigation("fth-chevron-right", "hints.next", () -> show(state.level + 1));
    next.getStyleClass().add("hint-next");
    Button close = navigation("fth-x", "hints.close", () -> { state.open = false; refresh(); trigger.requestFocus(); });
    close.getStyleClass().add("hint-close");
    progress.getStyleClass().add("hint-progress");
    Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar = new HBox(2, progress, spacer, previous, next, close);
    bar.setAlignment(Pos.CENTER_LEFT);
    body.setWrapText(true);
    body.setMinWidth(0);
    body.setMaxWidth(Double.MAX_VALUE);
    body.setMinHeight(Region.USE_PREF_SIZE);
    body.getStyleClass().add("hint-text");
    scroll.setFitToWidth(true);
    scroll.setMinHeight(Region.USE_PREF_SIZE);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    scroll.getStyleClass().add("hint-scroll");
    getChildren().addAll(bar, scroll);
    refresh();
  }

  Button trigger() { return trigger; }
  void setNudged(boolean nudged) { if (nudged) trigger.getStyleClass().add("hint-nudge"); }
  private static Button navigation(String icon, String key, Runnable action) {
    Button button = Icons.button(icon, I18n.t(key), action);
    button.getStyleClass().add("hint-navigation");
    button.setAccessibleText(I18n.t(key));
    return button;
  }
  private void show(int level) { state.show(level, total, onReveal); refresh(); }
  private void refresh() {
    setVisible(state.open); setManaged(state.open);
    trigger.setTooltip(new Tooltip(I18n.t(state.open ? "hints.close" : "hints.open")));
    trigger.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), state.open);
    if (!state.open) return;
    progress.setText(I18n.t("hints.progress", state.level, total, I18n.t("hints.level." + state.level)));
    body.getStyleClass().remove("hint-code");
    if (codeAt.test(state.level)) body.getStyleClass().add("hint-code");
    body.setText(hints.apply(state.level));
    previous.setDisable(state.level <= 1); next.setDisable(state.level >= total);
    scroll.setVvalue(0);
  }
}
