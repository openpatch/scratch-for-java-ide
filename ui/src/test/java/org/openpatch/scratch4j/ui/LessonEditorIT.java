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
}
