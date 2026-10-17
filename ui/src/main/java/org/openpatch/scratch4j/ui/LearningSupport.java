package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.io.ClassReport;
import org.openpatch.scratch4j.core.io.EventLog;
import org.openpatch.scratch4j.core.lesson.LessonProgress;
import org.openpatch.scratch4j.core.lint.*;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Project-scoped hints and opt-in logging. The event API never accepts source, paths or messages. */
final class LearningSupport {
  private ScratchProject project;
  private volatile EventLog log;
  private final Label indicator = new Label();
  private final StuckRule stuck = new StuckRule();
  private final Set<String> nudged = new HashSet<>();
  private final Map<String, Integer> crashRuns = new HashMap<>();
  private final Map<String, Integer> crashAttempts = new HashMap<>();
  private ProblemsPane problems;
  private LessonPanel lesson;
  private Runnable onHighlight = () -> {};
  private int attempts;
  private boolean unavailable;
  private final Map<String, Integer> typedCounts = new HashMap<>();
  private final Map<Path, String> typedKinds = new HashMap<>();
  synchronized void typed(Path file) {
    if (log != null && log.active()) typedCounts.merge(typedKinds.computeIfAbsent(file, LearningSupport::kind), 1, Integer::sum);
  }
  private synchronized void flushEdits(EventLog target) {
    if (target == null || target != log) return;
    typedCounts.forEach((kind, count) -> target.record("edit", Map.of("count", count, "kind", kind)));
    typedCounts.clear();
  }

  Label indicator() { return indicator; }
  EventLog log() { return log; }
  int attempt() { return ++attempts; }
  boolean isNudged(Problem problem) { return nudged.contains(problem.hintIdentity()); }
  void connect(ProblemsPane problems, LessonPanel lesson, Runnable onHighlight) {
    this.problems = problems; this.lesson = lesson; this.onHighlight = onHighlight;
    problems.setOnHint((p, level) -> event("hint", Map.of("level", level, "case", key(p), "step", "")));
    problems.setOnHelp((p, action) -> help(action, p));
    lesson.setOnHint((step, level) -> event("hint", Map.of("level", level, "case", "", "step", stepId(step))));
    refreshIndicator();
  }
  void open(ScratchProject next) {
    close();
    project = next;
    unavailable = false;
    typedKinds.clear(); typedCounts.clear();
    attempts = 0; stuck.clear(); nudged.clear(); crashRuns.clear(); crashAttempts.clear(); problems.resetHints();
    problems.setHintsOnly(next.settings().hintsOnly);
    if (next.settings().researchLog) {
      try {
        var lesson = org.openpatch.scratch4j.core.lesson.Lesson.load(next.root());
        log = EventLog.open(next.root(), EventLog.settingsDirectory(),
            lesson == null ? next.settings().lesson : lesson.id(),
            I18n.current() == I18n.Language.DE ? "de" : "en",
            Optional.ofNullable(StudioApp.class.getPackage().getImplementationVersion()).orElse("development"));
      } catch (IOException | RuntimeException e) {
        unavailable = true;
        indicator.setText(I18n.t("log.failed"));
        indicator.setVisible(true); indicator.setManaged(true);
        indicator.setTooltip(new Tooltip(I18n.t("log.failed")));
        return;
      }
    }
    refreshIndicator();
  }
  void attach(LessonProgress progress) {
    EventLog captured = log;
    if (progress != null) progress.setOnStep((step, state) -> write(captured, "lesson.step",
        Map.of("step", EventLog.reference(progress.lesson().id() + "/" + step), "state", state)));
  }
  private String stepId(String step) {
    var progress = lesson.progress();
    return EventLog.reference((progress == null ? "" : progress.lesson().id()) + "/" + step);
  }
  void checked(List<Problem> found) {
    typedKinds.clear();
    EventLog captured = log;
    if (captured != null) {
      captured.errorsChanged(found.stream().filter(p -> p.error() && p.diagnosticCode() != null)
          .map(p -> error(p.caseKey(), p.diagnosticCode(), p.line(), p.file())).toList());
      captured.transitions(found.stream().map(Problem::caseKey).filter(Objects::nonNull)
          .filter(k -> k.startsWith("transition.")).map(k -> k.substring(11)).toList());
      refreshIndicator();
    }
    Set<String> current = found.stream().map(Problem::hintIdentity).collect(java.util.stream.Collectors.toSet());
    nudged.retainAll(current);
    for (String id : nudged) problems.nudge(id);
  }
  static String kind(Path file) {
    if (file == null) return "other";
    return switch (VisualMode.kind(file)) {
      case STAGE -> "stage"; case SPRITE -> "sprite"; case WINDOW -> "window"; case NONE -> "other";
    };
  }
  private static Map<String, Object> error(String key, String code, long line, Path file) {
    return Map.of("case", key == null ? "" : key, "code", code == null ? "" : code,
        "line", Math.max(0, line), "kind", kind(file));
  }
  /** Called once per explicit run's compilation, never by the background check. */
  void ran(ScratchProject p, EventLog captured, int attempt, CompileResult result) {
    boolean success = result == null || result.success();
    List<Problem> errors = new ArrayList<>();
    if (result != null && !result.errors().isEmpty()) {
      try {
        List<Path> sources = p.javaSources();
        var library = new org.openpatch.scratch4j.core.project.JavaImports.Library(p.libs());
        for (var d : result.errors()) {
          Path file = sources.stream().filter(f -> d.path() != null
              && d.path().replace('\\', '/').endsWith(f.getFileName().toString())).findFirst().orElse(null);
          var friendly = new FriendlyErrors(I18n.current() == I18n.Language.DE ? DiagnosticsExplanations.Language.DE : DiagnosticsExplanations.Language.EN)
              .describe(d, new FriendlyErrors.Context(file == null ? List.of() : Files.readAllLines(file),
                  file == null ? "" : file.getFileName().toString().replaceFirst("\\.java$", ""),
                  name -> { String qualified = library.qualified(name); return qualified == null ? null : "import " + qualified + ";"; },
                  org.openpatch.scratch4j.core.api.ApiIndex.load().methodNames()));
          errors.add(new Problem(file, d.line(), d.column(), friendly.title(), friendly.explanation(), List.of(), true,
              null, null, false, null, null, friendly.caseKey(), d.code()));
        }
      } catch (IOException ignored) { /* the run still has its compile result */ }
    }
    if (captured != null) {
      flushEdits(captured);
      captured.errorsChanged(errors.stream().map(e -> error(e.caseKey(), e.diagnosticCode(), e.line(), e.file())).toList());
      write(captured, "run", Map.of("success", success, "errorCount", result == null ? 0 : result.errors().size(), "attempt", attempt));
    }
    Platform.runLater(() -> {
      if (project != p) return;
      var progress = lesson.progress();
      String step = progress == null || progress.finished() ? "" : progress.lesson().steps().get(progress.current()).id();
      String stepKey = step.isEmpty() ? "" : stepId(step);
      Map<String, Problem> byId = new HashMap<>();
      errors.forEach(e -> byId.put(e.hintIdentity(), e));
      var fired = stuck.run(byId.keySet(), stepKey);
      for (String id : fired.errors()) {
        nudged.add(id); problems.nudge(id);
        write(captured, "nudge", Map.of("case", key(byId.get(id)), "step", "", "rule", "error.3runs"));
      }
      if (fired.step()) {
        lesson.nudge(step);
        write(captured, "nudge", Map.of("case", "", "step", stepKey, "rule", "step.3runs"));
      }
      onHighlight.run();
      refreshIndicator();
    });
  }
  void crash(org.openpatch.scratch4j.core.lint.RuntimeErrors.Explanation e, Problem problem) {
    String exception = e.exception().matches("java\\.(?:lang|util|io)\\.[A-Za-z]+(?:Exception|Error)") ? e.exception() : "other";
    event("crash", Map.of("exception", exception, "case", e.caseKey(), "line", Math.max(0, e.line()), "kind", kind(e.file())));
    String identity = problem.hintIdentity();
    Integer previous = crashAttempts.put(identity, attempts);
    if (previous == null || previous != attempts) {
      int count = previous != null && previous == attempts - 1 ? crashRuns.getOrDefault(identity, 0) + 1 : 1;
      crashRuns.put(identity, count);
      if (count == 3) {
        nudged.add(identity); problems.nudge(identity);
        event("nudge", Map.of("case", key(problem), "step", "", "rule", "error.3runs"));
      }
    }
  }
  private static String key(Problem p) { return p.caseKey() == null ? "" : p.caseKey(); }
  void help(String action, Problem problem) { event("help", Map.of("action", action, "case", problem == null ? "" : key(problem))); }
  void event(String type, Map<String, ?> data) { write(log, type, data); }
  private void write(EventLog target, String type, Map<String, ?> data) {
    if (target == null) return;
    target.record(type, data);
    if (Platform.isFxApplicationThread()) refreshIndicator();
    else Platform.runLater(this::refreshIndicator);
  }
  void close() {
    EventLog previous = log;
    flushEdits(previous);
    log = null;
    if (previous != null) previous.close();
  }
  void closeProject() { close(); project = null; refreshIndicator(); }
  private void refreshIndicator() {
    boolean optedIn = project != null && project.settings().researchLog;
    indicator.setText(I18n.t(log != null && log.active() ? "log.on" : "log.off"));
    if (unavailable || (log != null && log.failure() != null)) indicator.setText(I18n.t("log.failed"));
    indicator.setVisible(optedIn); indicator.setManaged(optedIn);
    indicator.getStyleClass().setAll("logging-indicator");
  }

  Menu menu(Window owner) {
    Menu menu = new Menu(I18n.t("log.menu"));
    MenuItem open = new MenuItem(I18n.t("log.open"));
    open.setOnAction(e -> {
      if (project == null) return;
      try {
        flushEdits(log);
        TextArea text = new TextArea(log == null ? EventLog.contents(project.root()) : log.contents()); text.setEditable(false); text.setPrefSize(800, 480);
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(owner); dialog.setTitle(I18n.t("log.open"));
        dialog.getDialogPane().setContent(text); dialog.getDialogPane().getButtonTypes().add(I18n.close());
        Theme.style(dialog); dialog.showAndWait();
      } catch (IOException error) { error(error); }
    });
    MenuItem delete = new MenuItem(I18n.t("log.delete"));
    delete.setOnAction(e -> {
      if (project == null) return;
      Alert ask = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("log.delete.confirm"), I18n.ok(), I18n.cancel());
      Theme.style(ask);
      if (ask.showAndWait().orElse(null) != I18n.ok()) return;
      try { if (log != null) log.delete(); else EventLog.delete(project.root()); refreshIndicator(); }
      catch (IOException error) { error(error); }
    });
    MenuItem submit = new MenuItem(I18n.t("log.handin"));
    submit.setOnAction(e -> {
      if (project == null) return;
      flushEdits(log);
      TextInputDialog code = new TextInputDialog(); code.setTitle(I18n.t("log.handin"));
      code.setHeaderText(I18n.t("log.shared")); code.setContentText(I18n.t("log.code")); Theme.style(code);
      Optional<String> answer = code.showAndWait();
      if (answer.isEmpty()) return;
      FileChooser chooser = new FileChooser(); chooser.setTitle(I18n.t("log.handin"));
      chooser.setInitialFileName("learning-log.jsonl");
      if (project.root().getParent() != null) chooser.setInitialDirectory(project.root().getParent().toFile());
      chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Learning log", "*.jsonl"));
      var file = chooser.showSaveDialog(owner);
      if (file == null) return;
      try { if (log != null) log.handIn(file.toPath(), answer.get()); else EventLog.handIn(project.root(), file.toPath(), answer.get()); }
      catch (IOException error) { error(error); }
    });
    menu.getItems().addAll(open, delete, submit);
    menu.setOnShowing(e -> { boolean disabled = project == null || EventLog.segments(project.root()).isEmpty(); open.setDisable(disabled); delete.setDisable(disabled); submit.setDisable(disabled); });
    return menu;
  }
  MenuItem reportItem(Window owner) {
    MenuItem item = new MenuItem(I18n.t("log.report"));
    item.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle(I18n.t("log.report"));
      var folder = chooser.showDialog(owner);
      if (folder == null) return;
      boolean de = I18n.current() == I18n.Language.DE;
      Thread.ofVirtual().start(() -> {
        try {
          String report = ClassReport.build(folder.toPath(), de);
          Platform.runLater(() -> {
            ClipboardContent clipboard = new ClipboardContent(); clipboard.putString(report);
            Clipboard.getSystemClipboard().setContent(clipboard);
            Alert done = new Alert(Alert.AlertType.INFORMATION, I18n.t("log.report.copied"), I18n.ok()); Theme.style(done); done.show();
          });
        } catch (IOException error) { Platform.runLater(() -> error(error)); }
      });
    });
    return item;
  }
  MenuItem flag(boolean research) {
    CheckMenuItem item = new CheckMenuItem(I18n.t(research ? "log.flag" : "hints.only"));
    item.setOnAction(e -> {
      if (project == null) return;
      if (research) project.settings().researchLog = item.isSelected();
      else project.settings().hintsOnly = item.isSelected();
      try {
        project.settings().save(project.root());
        if (research) { open(project); attach(lesson.progress()); }
        else { problems.setHintsOnly(item.isSelected()); onHighlight.run(); }
      } catch (IOException error) { error(error); }
    });
    item.setOnMenuValidation(e -> {
      item.setDisable(project == null);
      item.setSelected(project != null && (research ? project.settings().researchLog : project.settings().hintsOnly));
    });
    return item;
  }
  private static void error(Exception error) {
    Alert alert = new Alert(Alert.AlertType.ERROR, error.getMessage(), I18n.ok()); Theme.style(alert); alert.showAndWait();
  }
}
