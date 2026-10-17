package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.io.EventLog;
import org.openpatch.scratch4j.core.project.ScratchProject;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class LearnerSupportIT {
  @TempDir Path tmp;
  @Test void keyboardCountsExcludeNavigationReloadsAndPaletteInsertion() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Example.java"), "", org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> tmp);
      var count = new java.util.concurrent.atomic.AtomicInteger();
      code.setOnTypedEdit(count::incrementAndGet);
      var area = code.area();
      javafx.event.Event.fireEvent(area, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_TYPED,
          "x", "", javafx.scene.input.KeyCode.UNDEFINED, false, false, false, false));
      assertThat(area.getText()).isEqualTo("x");
      assertThat(count.get()).isOne();
      javafx.event.Event.fireEvent(area, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
          "", "", javafx.scene.input.KeyCode.LEFT, false, false, false, false));
      code.reload("class Example { void go() {} }");
      area.moveTo(area.getText().indexOf("{}") + 1);
      code.insertBlock("int score = 5;");
      assertThat(count.get()).isOne();
      javafx.event.Event.fireEvent(area, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
          "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
      assertThat(count.get()).isEqualTo(2);
    });
  }

  @Test void followUpsCollapseAndHintsRevealWithoutApplyingAFix() throws Exception {
    LayoutIT.startFx(); I18n.set(I18n.Language.EN);
    Path file = tmp.resolve("Example.java");
    Files.writeString(file, "class Example { void go() { int n=1\n} }");
    var primary = new Problem(file, 1, 0, "A semicolon is missing", "Finish the statement with ;", List.of(), true,
        "semicolon", null, false, null, null, "expected.semicolon", "compiler.err.expected");
    var followUp = new Problem(file, 2, 0, "An echo", "Fix the first error", List.of(), true,
        null, null, true, null, null, "expression", "compiler.err.illegal.start.of.expr");
    AtomicReference<Stage> stage = new AtomicReference<>();
    var levels = new java.util.ArrayList<Integer>();
    var fixes = new java.util.concurrent.atomic.AtomicInteger();
    try {
      LayoutIT.onFx(() -> {
        ProblemsPane pane = new ProblemsPane(p -> {});
        pane.setOnHint((p, level) -> levels.add(level)); pane.setOnFix(p -> fixes.incrementAndGet());
        pane.setProblems(List.of(primary, followUp));
        Stage window = new Stage(); Scene scene = new Scene(pane, 800, 600); Theme.apply(scene); window.setScene(scene); window.show(); stage.set(window);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        ProblemsPane pane = (ProblemsPane) stage.get().getScene().getRoot();
        ListView<?> list = (ListView<?>) pane.lookup(".problems");
        assertThat(list.getItems()).hasSize(1); assertThat(pane.errorCount()).isEqualTo(2);
        ((Button) pane.lookup(".problem-followups")).fire();
        assertThat(list.getItems()).hasSize(2);
        pane.hint(primary); pane.hint(primary); pane.hint(primary);
        assertThat(levels).containsExactly(1, 2, 3); assertThat(fixes.get()).isZero();
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        ProblemsPane pane = (ProblemsPane) stage.get().getScene().getRoot();
        assertThat(pane.lookup(".problem-fix")).isNotNull();
        UiSmokeIT.snapshot(pane, Path.of("target/learner-support-hints.png"));
        pane.setHintsOnly(true);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(stage.get().getScene().getRoot().lookup(".problem-fix")).isNull());
      assertThat(Files.readString(file)).isEqualTo("class Example { void go() { int n=1\n} }");
    } finally { if (stage.get() != null) LayoutIT.onFx(() -> stage.get().close()); }
  }
  @Test void optInCountsOnlyExplicitRunsAndHighlightsAfterThreeWithoutOpeningAHint() throws Exception {
    LayoutIT.startFx(); I18n.set(I18n.Language.EN);
    String previous = System.getProperty("scratch4j.settingsDir");
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    var project = ScratchProject.open(Files.createDirectories(tmp.resolve("project")));
    Path file = project.root().resolve("Example.java");
    Files.writeString(file, "class Example { int n = 1.5; }");
    var result = new CompilerService().compile(List.of(file), List.of(), tmp.resolve("out"));
    var error = new Problem(file, 1, 25, "double does not fit into int", "Use double for the number", List.of(), true,
        null, null, false, null, null, "lossy", "compiler.err.prob.found.req");
    var learning = new LearningSupport();
    try {
      LayoutIT.onFx(() -> {
        ProblemsPane pane = new ProblemsPane(p -> {}); pane.setProblems(List.of(error));
        learning.connect(pane, new LessonPanel(), () -> {});
        learning.open(project);
        assertThat(project.root().resolve(EventLog.DIRECTORY)).doesNotExist();
        assertThat(tmp.resolve("settings/research-id")).doesNotExist();
        project.settings().researchLog = true;
        learning.open(project);
        learning.checked(List.of(error)); learning.checked(List.of(error));
        learning.typed(file); learning.typed(file);
      });
      var log = learning.log();
      assertThat(log.contents()).doesNotContain("\"type\":\"run\"");
      for (int i = 1; i <= 3; i++) learning.ran(project, log, i, result);
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(learning.isNudged(error)).isTrue());
      String events = log.contents();
      assertThat(events.lines().filter(l -> l.contains("\"type\":\"run\"")).count()).isEqualTo(3);
      assertThat(events).contains("\"type\":\"edit\"");
      assertThat(events.lines().filter(l -> l.contains("\"type\":\"edit\"")).count()).isOne();
      assertThat(events.lines().filter(l -> l.contains("\"type\":\"nudge\"")).count()).isOne();
      assertThat(events).doesNotContain("\"type\":\"hint\"", "Example", "1.5", "Use double", "does not fit");
      var crash = new org.openpatch.scratch4j.core.lint.RuntimeErrors.Explanation("No index 5", "Counter reached 5", "Use < length",
          file, 2, "go", "java.lang.ArrayIndexOutOfBoundsException", "index.bounds", "raw trace");
      var runtime = new Problem(file, 2, 0, crash.title(), crash.explanation(), List.of(), true,
          null, null, false, null, crash.trace(), "runtime.index.bounds", null);
      LayoutIT.onFx(() -> {
        for (int i = 0; i < 3; i++) { learning.attempt(); learning.crash(crash, runtime); }
        learning.checked(List.of(runtime));
        assertThat(learning.isNudged(runtime)).isTrue();
      });
      assertThat(log.contents().lines().filter(l -> l.contains("\"type\":\"nudge\"")).count()).isEqualTo(2);
      assertThat(log.contents()).doesNotContain("raw trace", "Example.java", "Counter reached");
    } finally {
      LayoutIT.onFx(learning::close);
      if (previous == null) System.clearProperty("scratch4j.settingsDir"); else System.setProperty("scratch4j.settingsDir", previous);
    }
  }
}
