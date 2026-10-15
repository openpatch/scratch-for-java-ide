package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * How a class's tab shows: its code, the code beside its visual editor, or
 * the visual editor alone. The switch sits at the right end of the editor
 * tab strip and follows the selected tab; only the view shown is named, the
 * others are icons with tooltips. A window class has its settings button
 * there instead, every other tab nothing.
 */
final class ViewSwitch extends HBox {

  enum View { CODE, SPLIT, VISUAL }

  private final ToggleGroup group = new ToggleGroup();
  private final ToggleButton code = segment(View.CODE, "fth-code");
  private final ToggleButton split = segment(View.SPLIT, "fth-columns");
  private final ToggleButton visual = segment(View.VISUAL, "fth-monitor");
  private final HBox segments = new HBox(code, split, visual);
  private final Button windowSettings;
  private final java.util.Map<ToggleButton, String> labels = new java.util.HashMap<>();
  private Path file;
  private View view = View.CODE;

  /**
   * {@code onView} is asked for another view of a file; it answers with
   * {@link #showFor} (it may keep the view, e.g. for an unsaved hitbox).
   */
  ViewSwitch(BiConsumer<Path, View> onView, Consumer<Path> onWindowSettings) {
    getStyleClass().add("view-switch-bar");
    setAlignment(Pos.CENTER_RIGHT);
    segments.getStyleClass().add("view-switch");
    segments.setAlignment(Pos.CENTER);
    label(code, I18n.t("mode.code"));
    label(split, I18n.t("view.split"));
    code.setTooltip(new Tooltip(I18n.t("view.code.tooltip")));
    for (ToggleButton segment : List.of(code, split, visual)) {
      segment.setOnAction(e -> {
        View wanted = (View) segment.getUserData();
        if (file != null && wanted != view) onView.accept(file, wanted);
        select(view);
      });
    }
    windowSettings = Icons.labeled("fth-settings", I18n.t("stages.window.settings"),
        () -> onWindowSettings.accept(file));
    windowSettings.getStyleClass().add("view-switch-action");
    showFor(null, VisualMode.Kind.NONE, View.CODE);
  }

  private ToggleButton segment(View of, String icon) {
    ToggleButton segment = new ToggleButton(null, Icons.of(icon));
    segment.setUserData(of);
    segment.setToggleGroup(group);
    segment.getStyleClass().add("view-segment");
    segment.setFocusTraversable(true);
    return segment;
  }

  /** The switch for {@code file}'s class shown at {@code view}; nothing for other tabs. */
  void showFor(Path file, VisualMode.Kind kind, View view) {
    this.file = file;
    this.view = view;
    boolean switchable = file != null
        && (kind == VisualMode.Kind.STAGE || kind == VisualMode.Kind.SPRITE);
    if (switchable) {
      boolean stage = kind == VisualMode.Kind.STAGE;
      label(visual, I18n.t(stage ? "view.stage" : "view.sprite"));
      visual.setGraphic(Icons.of(stage ? "fth-monitor" : "fth-image"));
      visual.setTooltip(new Tooltip(I18n.t(stage ? "view.stage.tooltip"
          : "view.sprite.tooltip")));
      split.setTooltip(new Tooltip(I18n.t(stage ? "view.split.tooltip.stage"
          : "view.split.tooltip.sprite")));
      getChildren().setAll(segments);
      select(view);
    } else if (file != null && kind == VisualMode.Kind.WINDOW) {
      getChildren().setAll(windowSettings);
    } else {
      getChildren().clear();
    }
    setVisible(!getChildren().isEmpty());
    setManaged(isVisible());
  }

  /** A segment's name: its text while it is the view shown, read out by screen readers always. */
  private void label(ToggleButton segment, String text) {
    labels.put(segment, text);
    segment.setAccessibleText(text);
  }

  private void select(View shown) {
    ToggleButton on = shown == View.SPLIT ? split : shown == View.VISUAL ? visual : code;
    on.setSelected(true);
    for (ToggleButton segment : List.of(code, split, visual)) {
      segment.setText(segment == on ? labels.get(segment) : null);
    }
  }

  /** The view the switch shows (tests). */
  View view() {
    return view;
  }

  /** The file the switch is for, null when hidden (tests). */
  Path file() {
    return file;
  }

  /** Clicks a segment as the student does (tests). */
  void choose(View wanted) {
    (wanted == View.SPLIT ? split : wanted == View.VISUAL ? visual : code).fire();
  }
}
