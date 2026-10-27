package org.openpatch.scratch4j.ui;

import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Help > Show me around: a few cards, each pointing at one part of the window
 * (run, stages, blocks, code, problems, history). Shown once on the first
 * project; a part that is not on screen is left out.
 */
final class Tour {

  /** One card: the part it points at (looked up when shown) and its text keys. */
  record Step(Supplier<Node> target, String key) {}

  private static final double WIDTH = 380;

  private final Window owner;
  private final List<Step> steps = new ArrayList<>();
  private final Runnable onEnd;
  private final Popup popup = new Popup();
  private Node highlighted;
  private int index;

  Tour(Window owner, List<Step> steps, Runnable onEnd) {
    this.owner = owner;
    this.onEnd = onEnd;
    for (Step step : steps) {
      if (shown(step.target().get())) this.steps.add(step);
    }
    popup.setAutoHide(false);
    popup.setHideOnEscape(true);
    popup.setOnHidden(e -> unhighlight());
  }

  /** Steps whose part is on screen now (tests read it). */
  List<Step> steps() {
    return steps;
  }

  void start() {
    if (steps.isEmpty()) {
      onEnd.run();
      return;
    }
    show(0);
  }

  boolean isShowing() {
    return popup.isShowing();
  }

  void next() {
    if (index + 1 < steps.size()) show(index + 1);
    else end();
  }

  void end() {
    popup.hide();
    onEnd.run();
  }

  private void show(int at) {
    index = at;
    Step step = steps.get(at);
    Node target = step.target().get();
    unhighlight();
    if (shown(target)) {
      target.getStyleClass().add("tour-target");
      highlighted = target;
    }

    Label title = new Label(I18n.t("tour." + step.key() + ".title"));
    title.getStyleClass().add("tour-title");
    Label text = new Label(I18n.t("tour." + step.key() + ".text"));
    text.setWrapText(true);
    Label count = new Label((at + 1) + " / " + steps.size());
    count.getStyleClass().add("text-muted");
    Button skip = new Button(I18n.t("tour.skip"));
    skip.getStyleClass().add("flat");
    skip.setOnAction(e -> end());
    Button back = new Button(I18n.t("tour.back"));
    back.setDisable(at == 0);
    back.setOnAction(e -> show(index - 1));
    Button next = new Button(I18n.t(at + 1 < steps.size() ? "tour.next" : "tour.done"));
    next.setId("tour-next");
    next.setDefaultButton(true);
    next.setOnAction(e -> next());
    Region gap = new Region();
    HBox.setHgrow(gap, Priority.ALWAYS);
    for (Region keep : List.of(count, skip, back, next)) keep.setMinWidth(Region.USE_PREF_SIZE);
    HBox buttons = new HBox(6, count, gap, skip, back, next);
    buttons.setAlignment(Pos.CENTER_LEFT);
    VBox card = new VBox(8, title, text, buttons);
    card.getStyleClass().add("tour-card");
    card.setPrefWidth(WIDTH);
    card.setMaxWidth(WIDTH);
    if (owner.getScene() != null) {
      card.getStylesheets().setAll(owner.getScene().getStylesheets());
      card.setStyle(owner.getScene().getRoot().getStyle());
      for (String theme : List.of("dark", "high-contrast")) {
        if (owner.getScene().getRoot().getStyleClass().contains(theme)) {
          card.getStyleClass().add(theme);
        }
      }
    }
    popup.getContent().setAll(card);
    if (!popup.isShowing()) popup.show(owner);
    card.applyCss();
    card.layout();
    double height = card.prefHeight(WIDTH);
    double[] position = place(target, height);
    popup.setX(position[0]);
    popup.setY(position[1]);
    next.requestFocus();
  }

  /** Below the part if there is room, else above it, else inside the window's middle. */
  private double[] place(Node target, double height) {
    double windowX = owner.getX();
    double windowY = owner.getY();
    double windowW = owner.getWidth();
    double windowH = owner.getHeight();
    if (!shown(target)) {
      return new double[] {windowX + (windowW - WIDTH) / 2, windowY + (windowH - height) / 2};
    }
    Bounds b = target.localToScreen(target.getBoundsInLocal());
    double x = Math.max(windowX + 8, Math.min(b.getMinX() + 16, windowX + windowW - WIDTH - 8));
    double below = b.getMaxY() + 8;
    if (b.getHeight() > windowH * 0.5) {
      // a big part (the editor, the sidebar): the card sits inside it
      return new double[] {x, Math.max(windowY + 8, b.getMinY() + 40)};
    }
    if (below + height < windowY + windowH) return new double[] {x, below};
    return new double[] {x, Math.max(windowY + 8, b.getMinY() - height - 8)};
  }

  private void unhighlight() {
    if (highlighted != null) highlighted.getStyleClass().remove("tour-target");
    highlighted = null;
  }

  private static boolean shown(Node node) {
    return node != null && node.getScene() != null && node.isVisible()
        && node.localToScreen(node.getBoundsInLocal()) != null
        && node.getBoundsInLocal().getWidth() > 0;
  }
}
