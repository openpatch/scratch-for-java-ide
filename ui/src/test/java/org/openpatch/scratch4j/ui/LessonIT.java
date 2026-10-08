package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.lesson.Lesson;
import org.openpatch.scratch4j.core.project.BundledTemplates;

/** A lesson project opens on its Lesson tab and ticks steps off after the IDE's checks. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class LessonIT {

  @TempDir
  Path tmp;

  @Test
  void theLessonTabFollowsTheStudentsCode() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    I18n.set(I18n.Language.EN);
    Lesson lesson = Lesson.bundled("first-steps");
    Path root = BundledTemplates.create(lesson.template(), tmp, "lesson",
        org.openpatch.scratch4j.core.project.NewProject.classpathJar(
            org.openpatch.scratch.internal.BuiltinAssets.class));
    Lesson.install(root, lesson.id());
    // the first run is done already (a run needs a graphics driver)
    Files.writeString(root.resolve(".scratch4j/lesson-progress.json"),
        "{\"lesson\":\"first-steps\",\"done\":[\"run\"],\"runs\":1,\"runsAtStepStart\":1}");
    startFx();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<javafx.stage.Stage> window = new AtomicReference<>();
    onFx(() -> {
      StudioApp studio = new StudioApp();
      javafx.stage.Stage stage = new javafx.stage.Stage();
      studio.start(stage);
      stage.setWidth(1360);
      stage.setHeight(840);
      studio.openProjectAt(root);
      app.set(studio);
      window.set(stage);
    });
    settle();
    onFx(() -> {
      var scene = window.get().getScene();
      LessonPanel panel = (LessonPanel) scene.getRoot().lookup(".lesson-panel");
      assertThat(panel).as("lesson tab").isNotNull();
      assertThat(panel.isVisible()).as("the Lesson tab is the one shown").isTrue();
      assertThat(panel.progress().current()).isEqualTo(1);
      assertThat(panel.lookupAll(".lesson-step.current")).hasSize(1);
      assertThat(panel.lookupAll(".lesson-step.done")).hasSize(1);
    });

    // the student makes the bunny faster; the IDE's check ticks the step off
    Path bunny = root.resolve("Bunny.java");
    Files.writeString(bunny, Files.readString(bunny).replace("this.move(4);", "this.move(8);"));
    onFx(() -> app.get().recheckForTests());
    for (int i = 0; i < 20; i++) {
      settle();
      AtomicReference<Integer> current = new AtomicReference<>();
      onFx(() -> current.set(((LessonPanel) window.get().getScene().getRoot()
          .lookup(".lesson-panel")).progress().current()));
      if (current.get() == 2) break;
    }
    onFx(() -> {
      LessonPanel panel = (LessonPanel) window.get().getScene().getRoot().lookup(".lesson-panel");
      assertThat(panel.progress().current()).as("faster is done, try it out is next").isEqualTo(2);
      assertThat(panel.lookupAll(".lesson-step.done")).hasSize(2);
      UiSmokeIT.snapshot(window.get().getScene().getRoot(), Path.of("target/lesson.png"));
    });
  }
}
