package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundIO;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sound stops when its tab closes, not when another tab is shown (needs a
 * display — {@code xvfb-run -a} — {@code -Dscratch4j.uismoke=true} and an
 * audio device; without one it is skipped).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class SoundEditorCloseIT {

  @TempDir
  Path tmp;

  @Test
  void closingTheTabStopsTheSound() throws Exception {
    float rate = 22050;
    float[] samples = new float[(int) (rate * 5)];
    for (int i = 0; i < samples.length; i++) {
      samples[i] = (float) (0.05 * Math.sin(2 * Math.PI * 440 * i / rate));
    }
    Path wav = tmp.resolve("assets/sounds/beep.wav");
    java.nio.file.Files.createDirectories(wav.getParent());
    SoundIO.writeWav(new SoundClip(new float[][] {samples}, rate), wav);

    LayoutIT.startFx();
    AtomicReference<SoundEditorView> view = new AtomicReference<>();
    AtomicReference<TabPane> tabs = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicBoolean playing = new AtomicBoolean();
    LayoutIT.onFx(() -> {
      try {
        view.set(new SoundEditorView(tmp, wav));
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      TabPane pane = new TabPane(new Tab("beep.wav", view.get()), new Tab("other"));
      Stage stage = new Stage();
      stage.setScene(new Scene(pane, 700, 400));
      stage.show();
      tabs.set(pane);
      window.set(stage);
      // Space plays
      view.get().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, " ", "", KeyCode.SPACE,
          false, false, false, false));
      playing.set(view.get().playing());
    });
    try {
      Assumptions.assumeTrue(playing.get(), "no audio device");
      LayoutIT.onFx(() -> tabs.get().getSelectionModel().select(1));
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(view.get().playing()).as("another tab shown").isTrue());
      LayoutIT.onFx(() -> tabs.get().getTabs().remove(0));
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(view.get().playing()).as("tab closed").isFalse());
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }

  @Test
  void spacePausesAndResumesWhereItWasAndHomeGoesBackToTheStart() throws Exception {
    float rate = 22050;
    Path wav = tmp.resolve("beep.wav");
    SoundIO.writeWav(new SoundClip(new float[][] {new float[(int) (rate * 5)]}, rate), wav);
    LayoutIT.startFx();
    AtomicReference<SoundEditorView> view = new AtomicReference<>();
    AtomicBoolean playing = new AtomicBoolean();
    LayoutIT.onFx(() -> {
      try {
        view.set(new SoundEditorView(tmp, wav));
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      new Scene(view.get(), 700, 400);
      view.get().shortcut(KeyCode.SPACE, false, false);
      playing.set(view.get().playing());
    });
    try {
      Assumptions.assumeTrue(playing.get(), "no audio device");
      Thread.sleep(400);
      LayoutIT.onFx(() -> {
        view.get().shortcut(KeyCode.SPACE, false, false);
        assertThat(view.get().playing()).as("paused").isFalse();
        assertThat(view.get().pausedAt()).as("paused partway").isPositive();
        view.get().shortcut(KeyCode.HOME, false, false);
        assertThat(view.get().pausedAt()).as("back at the start").isEqualTo(-1);
        view.get().shortcut(KeyCode.SPACE, false, false);
        assertThat(view.get().playing()).as("playing again").isTrue();
      });
    } finally {
      LayoutIT.onFx(() -> view.get().shortcut(KeyCode.ESCAPE, false, false));
    }
  }
}
