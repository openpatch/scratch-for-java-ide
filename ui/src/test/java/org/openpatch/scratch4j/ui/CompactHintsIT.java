package org.openpatch.scratch4j.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.lesson.Lesson;
import org.openpatch.scratch4j.core.lesson.LessonProgress;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations;
import org.openpatch.scratch4j.core.lint.ProjectCheck;
import org.openpatch.scratch4j.core.project.ScratchProject;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class CompactHintsIT {
  @TempDir Path tmp;

  @Test void theReportedExtendsTypoHasOneCompactHintWithNavigationAndAnExplicitFix() throws Exception {
    LayoutIT.startFx();
    I18n.Language previousLanguage = I18n.current(); I18n.set(I18n.Language.EN);
    Path source = tmp.resolve("Welt.java");
    Files.writeString(source, "public class Welt exends Spielwelt {");
    var project = ScratchProject.open(tmp);
    List<Problem> found = ProjectCheck.check(project, DiagnosticsExplanations.Language.EN).stream()
        .map(p -> new Problem(p.file(), p.line(), p.column(), p.message(), p.explanation(), p.suggestions(),
            p.error(), p.fix(), p.original(), p.followUp(), p.fixData(), p.trace(), p.caseKey(), p.diagnosticCode())).toList();
    assertThat(found.getFirst().message()).isEqualTo("Write extends instead of exends");
    var levels = new ArrayList<Integer>();
    var fixes = new java.util.concurrent.atomic.AtomicInteger();
    AtomicReference<Stage> window = new AtomicReference<>();
    try {
      LayoutIT.onFx(() -> {
        ProblemsPane pane = new ProblemsPane(p -> {});
        pane.setProblems(found); pane.setOnHint((p, level) -> levels.add(level)); pane.setOnFix(p -> fixes.incrementAndGet());
        Stage stage = new Stage(); Scene scene = new Scene(pane, 800, 260); Theme.apply(scene);
        stage.setScene(scene); stage.show(); window.set(stage);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        var pane = (ProblemsPane) window.get().getScene().getRoot();
        assertThat(primaryCell(pane).getHeight()).as("collapsed error fits in a compact row").isLessThan(64);
        var trigger = (Button) pane.lookup(".problem-hint-button");
        var fix = (Button) pane.lookup(".problem-fix");
        assertThat(trigger.localToScene(trigger.getBoundsInLocal()).getMinY())
            .isCloseTo(fix.localToScene(fix.getBoundsInLocal()).getMinY(), org.assertj.core.data.Offset.offset(2.0));
        assertThat(pane.lookup(".problem-original")).as("raw javac text is behind More").isNull();
        trigger.fire();
        assertThat(hintText(pane)).contains("Welt inherits from Spielwelt");
        ((Button) pane.lookup(".hint-next")).fire();
        assertThat(hintText(pane)).contains("between the class name").doesNotContain("inherits from");
        ((Button) pane.lookup(".hint-next")).fire();
        assertThat(hintText(pane)).contains("public class Welt extends Spielwelt {").doesNotContain("exends");
        assertThat(pane.lookupAll(".hint-panel").stream().filter(javafx.scene.Node::isVisible).count()).isOne();
        assertThat(levels).containsExactly(1, 2, 3);
        assertThat(fixes.get()).isZero();
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        var pane = (ProblemsPane) window.get().getScene().getRoot();
        assertThat(pane.lookup(".hint-panel").getLayoutBounds().getHeight()).as("short examples leave no empty panel space").isLessThan(100);
        assertThat(pane.lookup(".hint-panel").lookupAll(".scroll-bar").stream().filter(javafx.scene.Node::isVisible))
            .as("short examples need no scrollbars").isEmpty();
        assertThat(primaryCell(pane).getHeight()).as("example stays inside the shallow problems area").isLessThan(170);
        UiSmokeIT.snapshot(pane, Path.of("target/compact-hints-exends.png"));
        ((Button) pane.lookup(".hint-previous")).fire();
        assertThat(hintText(pane)).contains("between the class name");
        ((Button) pane.lookup(".hint-close")).fire();
        assertThat(pane.lookupAll(".hint-panel").stream().filter(javafx.scene.Node::isVisible)).isEmpty();
        ((Button) pane.lookup(".problem-hint-button")).fire();
        assertThat(hintText(pane)).contains("between the class name");
        assertThat(levels).as("only explicit hint views are recorded").containsExactly(1, 2, 3, 2, 2);
      });
      assertThat(Files.readString(source)).isEqualTo("public class Welt exends Spielwelt {");
    } finally {
      if (window.get() != null) LayoutIT.onFx(() -> window.get().close());
      I18n.set(previousLanguage);
    }
  }

  @Test void longHintsScrollAndGermanActionsFitAtANarrowWidth() throws Exception {
    LayoutIT.startFx();
    I18n.Language previousLanguage = I18n.current(); I18n.set(I18n.Language.DE);
    AtomicReference<Stage> window = new AtomicReference<>();
    try {
      LayoutIT.onFx(() -> {
        Problem p = new Problem(tmp.resolve("Welt.java"), 1, 18, "Schreibe extends statt exends",
            "Mit extends erbt Welt von Spielwelt. ".repeat(40), List.of(), true, "keyword", "'{' expected", false,
            "extends", null, "keyword.extends", "compiler.err.expected");
        ProblemsPane pane = new ProblemsPane(problem -> {}); pane.setProblems(List.of(p));
        Stage stage = new Stage(); Scene scene = new Scene(pane, 620, 260); Theme.apply(scene);
        stage.setScene(scene); stage.show(); window.set(stage);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> ((Button) window.get().getScene().getRoot().lookup(".problem-hint-button")).fire());
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        var pane = (ProblemsPane) window.get().getScene().getRoot();
        HintView hint = (HintView) pane.lookup(".hint-panel");
        assertThat(hint.getHeight()).as("long explanation uses a bounded, readable viewport").isBetween(100.0, 140.0);
        var actions = pane.lookup(".problem-fix");
        assertThat(actions.localToScene(actions.getBoundsInLocal()).getMaxX()).isLessThanOrEqualTo(620);
        assertThat(((Label) pane.lookup(".hint-progress")).getText()).contains("Erklärung");
        UiSmokeIT.snapshot(pane, Path.of("target/compact-hints-long-de.png"));
      });
    } finally {
      if (window.get() != null) LayoutIT.onFx(() -> window.get().close());
      I18n.set(previousLanguage);
    }
  }

  @Test void lessonHintsShowOneLevelAndKeepTheirPlaceAcrossProgressRefreshes() throws Exception {
    LayoutIT.startFx(); I18n.Language previousLanguage = I18n.current(); I18n.set(I18n.Language.EN);
    var step = new Lesson.Step("inherit", Map.of("en", "Make a world"), Map.of("en", "Add the class."), null,
        new Lesson.ClassExists("Welt", "Spielwelt", 1), Map.of("en", List.of("First teacher hint", "Second teacher hint", "Third teacher hint")));
    var progress = LessonProgress.load(new Lesson("test", "", Map.of("en", "Lesson"), List.of(step)), tmp);
    var levels = new ArrayList<Integer>();
    try {
      LayoutIT.onFx(() -> {
        LessonPanel panel = new LessonPanel(); panel.setOnHint((id, level) -> levels.add(level)); panel.show(progress);
        // A scene gives the ScrollPane its skin, exposing the current step for lookup.
        Scene scene = new Scene(panel, 320, 600); Theme.apply(scene); panel.applyCss(); panel.layout();
        panel.nudge(step.id()); panel.applyCss(); panel.layout();
        assertThat(panel.lookup(".hint-panel").isVisible()).isFalse();
        ((Button) panel.lookup(".problem-hint-button")).fire();
        assertThat(hintText(panel)).isEqualTo("First teacher hint");
        ((Button) panel.lookup(".hint-next")).fire();
        panel.refresh(); panel.applyCss(); panel.layout();
        assertThat(hintText(panel)).isEqualTo("Second teacher hint");
        assertThat(panel.lookupAll(".hint-text")).hasSize(1);
        assertThat(levels).containsExactly(1, 2);
      });
    } finally { I18n.set(previousLanguage); }
  }

  private static String hintText(javafx.scene.Parent pane) { return ((Label) pane.lookup(".hint-text")).getText(); }
  private static ListCell<?> primaryCell(ProblemsPane pane) {
    return pane.lookupAll(".list-cell").stream().filter(n -> n instanceof ListCell<?> c && c.getItem() instanceof Problem p && !p.followUp())
        .map(n -> (ListCell<?>) n).findFirst().orElseThrow();
  }
}
