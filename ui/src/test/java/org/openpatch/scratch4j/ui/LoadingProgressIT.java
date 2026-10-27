package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

/**
 * A project opens behind a loading card; a starting program shows a bar
 * beside the status until its first frame (needs a display — {@code xvfb-run
 * -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class LoadingProgressIT {

  @TempDir
  Path tmp;

  @Test
  void openingAndStartingShowTheirProgress() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    Path project = BundledTemplates.create("demo-tiled", tmp, "tiled",
        LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled")));
    LayoutIT.startFx();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<javafx.stage.Stage> window = new AtomicReference<>();
    AtomicBoolean cardWhileOpening = new AtomicBoolean();
    LayoutIT.onFx(() -> {
      StudioApp studio = new StudioApp();
      javafx.stage.Stage stage = new javafx.stage.Stage();
      studio.start(stage);
      app.set(studio);
      window.set(stage);
      studio.openProjectAt(project);
      cardWhileOpening.set(studio.loadingShowing());
    });
    try {
      assertThat(cardWhileOpening).as("card while the project opens").isTrue();
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(app.get().loadingShowing()).as("card gone once open").isFalse();
        assertThat(window.get().getScene().getRoot().lookup(".designer"))
            .as("the start stage's designer").isNotNull();
      });

      // the green flag: the bar is up while it compiles (the program itself cannot
      // start here, the template's library is not complete in the tests)
      AtomicBoolean barWhileStarting = new AtomicBoolean();
      LayoutIT.onFx(() -> {
        app.get().runForTests();
        barWhileStarting.set(app.get().runProgressShowing());
        app.get().stopForTests();
      });
      assertThat(barWhileStarting).as("bar while the program compiles").isTrue();
      LayoutIT.settle();

      // the heartbeats of a starting program: no window yet, loading screen, first frame
      LayoutIT.onFx(() -> {
        StudioApp studio = app.get();
        studio.startingForTests("Tiled", true);
        studio.watchdog(-1, false);
        assertThat(studio.runProgressShowing()).as("no window yet").isTrue();
        studio.watchdog(0, true);
        assertThat(studio.runProgressShowing()).as("loading screen").isTrue();
        assertThat(studio.statusForTests()).isEqualTo(I18n.t("run.phase.loading", "Tiled"));
        studio.watchdog(3, false);
        assertThat(studio.runProgressShowing()).as("first frame").isFalse();
        assertThat(studio.statusForTests()).isEqualTo(I18n.t("status.running", "Tiled"));
        // a program that ends before its first frame takes the bar along
        studio.startingForTests("Tiled", true);
        studio.startingForTests("Tiled", false);
        assertThat(studio.runProgressShowing()).as("ended").isFalse();
      });

      // the flag pressed twice while it compiles: one program, not two
      LayoutIT.onFx(() -> {
        app.get().runForTests();
        app.get().runForTests();
      });
      waitUntilStopped(app.get());
      assertThat(app.get().keptRunsForTests()).as("programs from a double start").isEqualTo(1);
      // Stop while it compiles: no program comes up afterwards
      LayoutIT.onFx(() -> {
        app.get().runForTests();
        app.get().stopForTests();
      });
      waitUntilStopped(app.get());
      assertThat(app.get().keptRunsForTests()).as("programs after Stop").isEqualTo(1);
    } finally {
      LayoutIT.onFx(() -> {
        app.get().stopForTests();
        window.get().hide();
      });
    }
  }

  /** Until the program ended (here it cannot get far: the template's library is incomplete). */
  private static void waitUntilStopped(StudioApp app) throws Exception {
    AtomicBoolean running = new AtomicBoolean(true);
    for (int i = 0; i < 120 && running.get(); i++) {
      Thread.sleep(500);
      LayoutIT.onFx(() -> running.set(app.runningForTests()));
    }
    Thread.sleep(1000); // a replaced start ends after its compile: let it report
    LayoutIT.settle();
  }
}
