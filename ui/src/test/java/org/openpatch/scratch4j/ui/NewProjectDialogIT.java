package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openpatch.scratch4j.core.lesson.Lesson;

/** New project offers every guided lesson and names the project after the chosen one. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class NewProjectDialogIT {

  @Test
  void offersEveryLessonAndNamesTheProjectAfterIt() throws Exception {
    I18n.set(I18n.Language.DE);
    startFx();
    javafx.application.Platform.runLater(() -> NewProjectDialog.show(null));
    settle();
    try {
      onFx(() -> {
        var pane = javafx.stage.Window.getWindows().stream()
            .filter(w -> w.getScene() != null
                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
            .findFirst().orElseThrow().getScene().getRoot();
        @SuppressWarnings("unchecked")
        var lessons = (javafx.scene.control.ComboBox<Lesson>) pane.lookupAll(".combo-box")
            .stream().filter(n -> ((javafx.scene.control.ComboBox<?>) n).getItems().stream()
                .anyMatch(Lesson.class::isInstance)).findFirst().orElseThrow();
        assertThat(lessons.getItems()).extracting(Lesson::id)
            .containsExactlyElementsOf(Lesson.bundledIds());
        assertThat(lessons.isDisabled()).as("the lesson card is preselected").isFalse();
        var name = (javafx.scene.control.TextField) pane.lookup("#project-name");
        assertThat(name.getText()).startsWith("Dein-erstes-Programm");
        lessons.getSelectionModel().select(2);
        lessons.getOnAction().handle(null);
        assertThat(name.getText()).startsWith("Fang-die-Muenzen");
        UiSmokeIT.snapshot(pane, Path.of("target/new-project-lessons.png"));
        ((javafx.stage.Stage) pane.getScene().getWindow()).close();
      });
    } finally {
      I18n.set(I18n.Language.EN);
    }
  }

  @Test
  void lessonTitlesBecomeFolderNames() {
    assertThat(NewProjectDialog.projectName("Fang die Münzen")).isEqualTo("Fang-die-Muenzen");
    assertThat(NewProjectDialog.projectName("Hüpfender Igel")).isEqualTo("Huepfender-Igel");
    assertThat(NewProjectDialog.projectName("Rot, Grün")).isEqualTo("Rot-Gruen");
    assertThat(NewProjectDialog.projectName("!!!")).isEqualTo("MyGame");
  }
}
