package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openpatch.scratch4j.core.lesson.Lesson;

/** The lesson editor opens on the first step and tests it against the code. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class LessonEditorIT {

  @Test
  void showsTheStepsAndTestsTheSelectedOne() throws Exception {
    I18n.set(I18n.Language.EN);
    startFx();
    Lesson lesson = Lesson.bundled("first-steps");
    javafx.application.Platform.runLater(() -> LessonEditorDialog.show(null, lesson, "x",
        List.of("Bunny.java", "MyStage.java"), () -> new Lesson.Facts(
            Map.of("Bunny.java", "class Bunny { void run() { this.move(8); } }"), true, false)));
    settle();
    onFx(() -> {
      var pane = javafx.stage.Window.getWindows().stream()
          .filter(w -> w.getScene() != null
              && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
          .findFirst().orElseThrow().getScene().getRoot();
      @SuppressWarnings("unchecked")
      var list = (javafx.scene.control.ListView<LessonEditorDialog.Draft>) pane.lookup(".list-view");
      assertThat(list.getItems()).hasSize(7);
      list.getSelectionModel().select(1); // "Make the bunny faster": a number changed
      var test = pane.lookupAll(".button").stream()
          .filter(b -> b instanceof javafx.scene.control.Button button
              && "Test with the current code".equals(button.getText()))
          .map(b -> (javafx.scene.control.Button) b).findFirst().orElseThrow();
      test.fire();
      var result = (javafx.scene.control.Label) pane.lookup(".lessoneditor-result");
      assertThat(result.getText()).contains("Done with the code as it is now");
      UiSmokeIT.snapshot(pane, Path.of("target/lesson-editor.png"));
      ((javafx.stage.Stage) pane.getScene().getWindow()).close();
    });
  }

  @Test
  void aNewLessonInAGermanIdeIsGermanOnlyAndChecksForANewSpriteClass() throws Exception {
    I18n.set(I18n.Language.DE);
    startFx();
    javafx.application.Platform.runLater(() -> LessonEditorDialog.show(null, null, "x",
        List.of("Bunny.java", "Coin.java"), () -> new Lesson.Facts(Map.of(
            "Bunny.java", "public class Bunny extends Sprite { }",
            "Coin.java", "public class Coin extends Sprite { }"), true, false)));
    settle();
    try {
      onFx(() -> {
        var pane = javafx.stage.Window.getWindows().stream()
            .filter(w -> w.getScene() != null
                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
            .findFirst().orElseThrow().getScene().getRoot();
        var boxes = pane.lookupAll(".check-box").stream()
            .map(n -> (javafx.scene.control.CheckBox) n)
            .filter(b -> b.getText().equals("Deutsch") || b.getText().equals("Englisch")).toList();
        assertThat(boxes).hasSize(2);
        assertThat(boxes).filteredOn(javafx.scene.control.CheckBox::isSelected)
            .extracting(javafx.scene.control.CheckBox::getText).containsExactly("Deutsch");
        // no English field is shown
        assertThat(pane.lookupAll(".label").stream()
            .filter(n -> n.isVisible() && "EN".equals(((javafx.scene.control.Label) n).getText())))
            .isEmpty();
        @SuppressWarnings("unchecked")
        var kind = (javafx.scene.control.ComboBox<LessonEditorDialog.Kind>) pane.lookupAll(
            ".combo-box").stream().filter(n -> ((javafx.scene.control.ComboBox<?>) n)
                .getItems().contains(LessonEditorDialog.Kind.CLASS)).findFirst().orElseThrow();
        kind.setValue(LessonEditorDialog.Kind.CLASS);
        @SuppressWarnings("unchecked")
        var base = (javafx.scene.control.ComboBox<String>) pane.lookupAll(".combo-box").stream()
            .filter(n -> ((javafx.scene.control.ComboBox<?>) n).getItems().contains("Sprite"))
            .findFirst().orElseThrow();
        base.getEditor().setText("Sprite");
        @SuppressWarnings("unchecked")
        var min = (javafx.scene.control.Spinner<Integer>) pane.lookup(".spinner");
        min.getValueFactory().setValue(2);
        var test = pane.lookupAll(".button").stream()
            .filter(b -> b instanceof javafx.scene.control.Button button
                && "Mit dem aktuellen Code testen".equals(button.getText()))
            .map(b -> (javafx.scene.control.Button) b).findFirst().orElseThrow();
        test.fire();
        var result = (javafx.scene.control.Label) pane.lookup(".lessoneditor-result");
        assertThat(result.getText()).contains("Mit dem aktuellen Code geschafft");
        UiSmokeIT.snapshot(pane, Path.of("target/lesson-editor-de.png"));
        ((javafx.stage.Stage) pane.getScene().getWindow()).close();
      });
    } finally {
      I18n.set(I18n.Language.EN);
    }
  }
}
