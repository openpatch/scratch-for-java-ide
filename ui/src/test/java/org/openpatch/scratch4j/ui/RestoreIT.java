package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.io.ProjectSnapshots;
import org.openpatch.scratch4j.core.lint.RuntimeErrors;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

/**
 * Back to the last version that worked: the problems of the code that was
 * replaced go with it, the compile errors and the crash of the last run alike.
 * Run under {@code xvfb-run -a} with {@code -Dscratch4j.uismoke=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class RestoreIT {

  @TempDir
  Path tmp;

  @Test
  void goingBackClearsTheProblemsOfTheReplacedCode() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    Prefs.language(I18n.Language.EN); // StudioApp restores the saved language on startup
    I18n.set(I18n.Language.EN);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "restore",
        LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled")));
    Path root = tmp.resolve("restore");
    Path player = root.resolve("Player.java");
    // the code ran, then the student broke it
    ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
    Files.writeString(player, Files.readString(player) + "\noops\n");

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
    // and the last run crashed in it
    onFx(() -> app.get().showCrash(new RuntimeErrors.Explanation("player is null",
        "The program used player before it was there.", "Make the player first.", player, 3,
        "run")));
    List<Problem> broken = awaitProblems(window.get(), shown -> shown.size() >= 2);
    assertThat(broken).anyMatch(p -> p.message().contains("crashed"));
    assertThat(broken).anyMatch(p -> p.error() && !p.message().contains("crashed"));

    // Project > Back to the last version that worked, confirmed
    Platform.runLater(() -> app.get().restoreLastWorking());
    DialogPane confirm = null;
    for (int i = 0; i < 40 && confirm == null; i++) {
      settle();
      confirm = javafx.stage.Window.getWindows().stream()
          .map(w -> w.getScene() == null ? null : w.getScene().getRoot())
          .filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
          .findFirst().orElse(null);
    }
    assertThat(confirm).as("the confirmation").isNotNull();
    DialogPane ask = confirm;
    onFx(() -> ((javafx.scene.control.Button) ask.lookupButton(I18n.ok())).fire());
    settle(); // the restore goes on once the dialog's loop ends

    assertThat(Files.readString(player)).doesNotContain("oops");
    List<Problem> after = awaitProblems(window.get(),
        shown -> shown.stream().noneMatch(Problem::error));
    assertThat(after).as("problems after going back").noneMatch(Problem::error)
        .noneMatch(p -> p.message().contains("crashed"));
  }

  /** The problems list once {@code until} holds for it (or after ten seconds). */
  @SuppressWarnings("unchecked")
  private static List<Problem> awaitProblems(javafx.stage.Stage window,
      java.util.function.Predicate<List<Problem>> until) throws Exception {
    AtomicReference<List<Problem>> shown = new AtomicReference<>(List.of());
    for (int i = 0; i < 40; i++) {
      settle();
      onFx(() -> {
        ListView<Problem> list = (ListView<Problem>) window.getScene().getRoot()
            .lookup(".problems");
        shown.set(list == null ? List.of() : List.copyOf(list.getItems()));
      });
      if (until.test(shown.get())) break;
    }
    return shown.get();
  }
}
