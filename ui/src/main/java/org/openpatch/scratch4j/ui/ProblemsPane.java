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
 * for a while. A problem with a stack trace keeps it folded away until it is
 * clicked (or Enter is pressed), then shows it with a button to copy it.
 */
final class ProblemsPane extends BorderPane {

  private final ListView<Problem> list = new ListView<>();
  private final Label empty = new Label("", Icons.of("fth-check-circle", 18));
  private final Label stuck = new Label();
  private final Button restore = new Button(I18n.t("problems.stuck.restore"),
      Icons.of("fth-rotate-ccw"));
  private final HBox stuckBar = new HBox(10, stuck, restore);
  private Consumer<Problem> onFix = p -> { };
  private Consumer<Problem> onConsole = p -> {};
  private java.util.function.BiConsumer<Problem, Integer> onHint = (p, level) -> {};
  private java.util.function.BiConsumer<Problem, String> onHelp = (p, action) -> {};
  private boolean hintsOnly;
  private List<Problem> all = List.of();
  private final java.util.Map<Problem, List<Problem>> followUps = new java.util.HashMap<>();
  private final java.util.Set<Problem> unfolded = new java.util.HashSet<>();
  private final java.util.Set<Problem> dismissed = new java.util.HashSet<>();
  private final java.util.Map<String, HintView.State> hints = new java.util.HashMap<>();
  private final java.util.Set<Problem> originals = new java.util.HashSet<>();
  private final java.util.Set<String> nudged = new java.util.HashSet<>();

  void setOnHint(java.util.function.BiConsumer<Problem, Integer> action) { onHint = action; }
  void setOnHelp(java.util.function.BiConsumer<Problem, String> action) { onHelp = action; }
  void setOnConsole(Consumer<Problem> action) { onConsole = action; }
  void setHintsOnly(boolean value) { hintsOnly = value; list.refresh(); }
  void nudge(String identity) { nudged.add(identity); list.refresh(); }
  void resetHints() { hints.clear(); nudged.clear(); dismissed.clear(); list.refresh(); }
  private void revealed(Problem problem, int level) {
    onHint.accept(problem, level);
    if (level == 1) onHelp.accept(problem, "explanation");
  }
  void hint(Problem problem) {
    hints.computeIfAbsent(problem.hintIdentity(), id -> new HintView.State()).advance(3, level -> revealed(problem, level));
    followUps.forEach((owner, children) -> { if (children.contains(problem)) unfolded.add(owner); });
    refreshVisible();
    list.refresh();
    list.getSelectionModel().select(problem);
    list.scrollTo(problem);
  }
  /** Problems whose stack trace is showing. */
  private final java.util.Set<Problem> expanded = new java.util.HashSet<>();

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
        setText(null);
        setGraphic(card(problem));
      }
    });
    list.setOnMouseClicked(e -> {
      // Buttons own their actions; clicking text navigates or unfolds raw details.
      if (e.getTarget() instanceof javafx.scene.Node node) {
        for (javafx.scene.Node n = node; n != null && n != list; n = n.getParent())
          if (n instanceof javafx.scene.control.ButtonBase || n instanceof javafx.scene.control.TextInputControl) return;
      }
      Problem problem = list.getSelectionModel().getSelectedItem();
      if (problem != null && problem.trace() != null && problem.caseKey() == null) toggle(problem);
      else if (problem != null && problem.file() != null && Files.isRegularFile(problem.file())) onOpen.accept(problem);
    });
    list.setOnKeyPressed(e -> {
      Problem problem = list.getSelectionModel().getSelectedItem();
      if (problem != null && problem.trace() != null && e.getTarget() == list
          && (e.getCode() == javafx.scene.input.KeyCode.ENTER
              || e.getCode() == javafx.scene.input.KeyCode.SPACE)) {
        toggle(problem);
        e.consume();
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

  private VBox card(Problem problem) {
    var icon = Icons.of("check.passed".equals(problem.caseKey()) ? "fth-check-circle" : problem.isHint() ? "fth-zap"
        : problem.error() ? "fth-x-circle" : "fth-alert-triangle", 16);
    icon.getStyleClass().add("check.passed".equals(problem.caseKey()) ? "problem-ok" : problem.isHint() ? "problem-hint"
        : problem.error() ? "problem-error" : "problem-warning");
    Label message = new Label(problem.message().strip());
    message.getStyleClass().add("problem-message");
    message.setWrapText(true); message.setMinWidth(0); message.setMaxWidth(Double.MAX_VALUE);
    message.setMinHeight(Region.USE_PREF_SIZE);
    HBox.setHgrow(message, Priority.ALWAYS);
    Label location = new Label(problem.location());
    location.getStyleClass().add("problem-location");
    location.setMaxWidth(140); location.setMinWidth(0);
    location.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
    location.setTooltip(new javafx.scene.control.Tooltip(problem.location()));
    HBox actions = new HBox(2); actions.setAlignment(Pos.CENTER_LEFT);
    actions.setMinWidth(Region.USE_PREF_SIZE);
    HintView hint = null;
    if (problem.error() && problem.file() != null) {
      hint = new HintView(hints.computeIfAbsent(problem.hintIdentity(), id -> new HintView.State()), 3,
          problem::hint, level -> level == 3, level -> revealed(problem, level));
      hint.setNudged(nudged.contains(problem.hintIdentity()));
      actions.getChildren().add(hint.trigger());
    }
    boolean hasFix = problem.fix() != null && !hintsOnly && !dismissed.contains(problem);
    if (hasFix) {
      Button fix = new Button(I18n.t("hints.fix"), Icons.of("fth-tool", 12));
      fix.getStyleClass().addAll("flat", "compact-action", "problem-fix");
      fix.setTooltip(new javafx.scene.control.Tooltip(problem.fixLabel()));
      fix.setAccessibleText(problem.fixLabel());
      fix.setOnAction(e -> onFix.accept(problem));
      actions.getChildren().add(fix);
    }
    List<Problem> children = followUps.getOrDefault(problem, List.of());
    if (!children.isEmpty()) {
      Button related = new Button(I18n.t("problems.related.count", children.size()),
          Icons.of(unfolded.contains(problem) ? "fth-chevron-down" : "fth-chevron-right", 12));
      related.getStyleClass().addAll("flat", "compact-action", "problem-followups");
      related.setTooltip(new javafx.scene.control.Tooltip(I18n.t(unfolded.contains(problem) ? "problems.related.hide" : "problems.related", children.size())));
      related.setOnAction(e -> { if (!unfolded.remove(problem)) unfolded.add(problem); refreshVisible(); });
      actions.getChildren().add(related);
    }
    if (problem.trace() != null) {
      Button details = Icons.button(expanded.contains(problem) ? "fth-chevron-up" : "fth-chevron-down",
          I18n.t(expanded.contains(problem) ? "problems.trace.hide" : "problems.trace.show"), () -> toggle(problem));
      details.getStyleClass().addAll("compact-action", "problem-trace-toggle");
      actions.getChildren().add(details);
      if (problem.caseKey() != null) {
        Button console = Icons.button("fth-terminal", I18n.t("problems.console"), () -> onConsole.accept(problem));
        console.getStyleClass().addAll("compact-action", "problem-console");
        actions.getChildren().add(console);
      }
    }
    if (problem.original() != null || hasFix) {
      var more = new javafx.scene.control.MenuButton(null, Icons.of("fth-more-horizontal", 14));
      more.getStyleClass().addAll("flat", "compact-action", "problem-more");
      more.setTooltip(new javafx.scene.control.Tooltip(I18n.t("problems.more")));
      more.setAccessibleText(I18n.t("problems.more"));
      if (problem.original() != null) {
        var raw = new javafx.scene.control.CheckMenuItem(I18n.t("problems.raw"));
        raw.setSelected(originals.contains(problem));
        raw.setOnAction(e -> { if (!originals.remove(problem)) originals.add(problem); list.refresh(); });
        more.getItems().add(raw);
      }
      if (hasFix) {
        var dismiss = new javafx.scene.control.MenuItem(I18n.t("hints.dismiss"));
        dismiss.setOnAction(e -> { dismissed.add(problem); onHelp.accept(problem, "fix.dismissed"); list.refresh(); });
        more.getItems().add(dismiss);
      }
      actions.getChildren().add(more);
    }
    HBox head = new HBox(8, icon, message, location, actions);
    head.setAlignment(Pos.TOP_LEFT); head.setMinWidth(0);
    head.getStyleClass().add("problem-header");
    VBox card = new VBox(4, head);
    card.setMinWidth(0); card.setMaxWidth(Double.MAX_VALUE);
    if (problem.explanation() != null && !problem.explanation().isBlank()
        && (problem.caseKey() == null || problem.caseKey().startsWith("runtime.") || problem.caseKey().startsWith("check."))) {
      Label explanation = detail(problem.explanation(), "problem-explanation");
      if (hint != null) {
        explanation.visibleProperty().bind(hint.visibleProperty().not());
        explanation.managedProperty().bind(explanation.visibleProperty());
      }
      card.getChildren().add(explanation);
    }
    if (problem.suggestions() != null && !problem.suggestions().isEmpty())
      card.getChildren().add(detail(I18n.t("problems.didyoumean") + " " + String.join(", ", problem.suggestions()), "problem-suggestion"));
    if (hint != null) { card.getChildren().add(hint); VBox.setMargin(hint, new javafx.geometry.Insets(0, 0, 0, 24)); }
    if (problem.original() != null && originals.contains(problem)) card.getChildren().add(detail(problem.original().strip(), "problem-original"));
    if (problem.followUp()) card.getChildren().add(detail(I18n.t("problems.followup"), "problem-followup-note"));
    if (problem.trace() != null && expanded.contains(problem)) card.getChildren().add(ErrorDetails.box(problem.trace()));
    card.setOpacity(problem.followUp() ? 0.6 : 1);
    return card;
  }

  private static Label detail(String text, String style) {
    Label label = new Label(text); label.setWrapText(true); label.setMinHeight(Region.USE_PREF_SIZE);
    label.getStyleClass().add(style);
    VBox.setMargin(label, new javafx.geometry.Insets(0, 0, 0, 24));
    return label;
  }

  private void toggle(Problem problem) {
    if (!expanded.remove(problem)) {
      expanded.add(problem);
      onHelp.accept(problem, "explanation");
    }
    list.refresh();
  }

  /** Runs a problem's fix (the button under it). */
  void setOnFix(Consumer<Problem> action) {
    this.onFix = action;
  }

  void setProblems(List<Problem> problems) {
    all = List.copyOf(problems);
    expanded.retainAll(problems);
    unfolded.retainAll(problems);
    dismissed.retainAll(problems);
    originals.retainAll(problems);
    var ids = problems.stream().map(Problem::hintIdentity).collect(java.util.stream.Collectors.toSet());
    hints.keySet().retainAll(ids);
    nudged.retainAll(ids);
    followUps.clear();
    for (Problem child : all) {
      if (!child.followUp()) continue;
      Problem owner = all.stream().filter(p -> !p.followUp() && p.error()
          && java.util.Objects.equals(p.file(), child.file())
          && org.openpatch.scratch4j.core.lint.FriendlyErrors.SYNTAX_ERRORS.contains(p.diagnosticCode() == null ? "" : p.diagnosticCode()))
          .findFirst().orElseGet(() -> all.stream().filter(p -> !p.followUp() && p.error()
              && java.util.Objects.equals(p.file(), child.file())).findFirst().orElse(null));
      if (owner != null) followUps.computeIfAbsent(owner, p -> new java.util.ArrayList<>()).add(child);
    }
    refreshVisible();
  }

  private void refreshVisible() {
    var hidden = followUps.values().stream().flatMap(List::stream).collect(java.util.stream.Collectors.toSet());
    var visible = new java.util.ArrayList<Problem>();
    for (Problem problem : all) {
      if (hidden.contains(problem)) continue;
      visible.add(problem);
      if (unfolded.contains(problem)) visible.addAll(followUps.getOrDefault(problem, List.of()));
    }
    list.getItems().setAll(visible);
  }

  /**
   * The code changed since the list was made: it is dimmed until the next
   * check, so a fixed error does not look current.
   */
  void setOutdated(boolean outdated) {
    pseudoClassStateChanged(OUTDATED, outdated);
  }

  private static final javafx.css.PseudoClass OUTDATED =
      javafx.css.PseudoClass.getPseudoClass("outdated");

  long errorCount() {
    return all.stream().filter(Problem::error).count();
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
