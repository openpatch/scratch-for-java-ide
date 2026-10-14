package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundIO;

/** The image and sound editors' keyboard shortcuts, pressed as real key events. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorShortcutsIT {

  @TempDir
  Path tmp;

  private static void press(Node target, KeyCode code, boolean shortcut) {
    boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
    Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
        false, shortcut && !mac, false, shortcut && mac));
  }

  @Test
  void imageEditorSavesSelectsCopiesAndPastesFromTheKeyboard() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    I18n.set(I18n.Language.EN);
    Path png = tmp.resolve("a.png");
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, 2), "png", png.toFile());
    long before = Files.size(png);
    startFx();
    onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 900, 600);
      view.applyCss();
      view.layout();
      Node surface = view.lookup(".paint-surface");
      view.image().getPixelWriter().setArgb(1, 1, 0xffff0000);
      press(surface, KeyCode.M, false); // select tool
      press(surface, KeyCode.A, true);
      press(surface, KeyCode.C, true);
      press(surface, KeyCode.V, true); // pasting is an edit
      assertThat(view.dirtyProperty().get()).isTrue();
      press(surface, KeyCode.ENTER, false); // place the pasted copy
      press(surface, KeyCode.S, true);
      assertThat(view.dirtyProperty().get()).isFalse();
    });
    assertThat(Files.size(png)).isNotEqualTo(before);
    assertThat(javax.imageio.ImageIO.read(png.toFile()).getRGB(1, 1)).isEqualTo(0xffff0000);
  }

  @Test
  void soundEditorSelectsAllAndDeletesFromTheKeyboard() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    I18n.set(I18n.Language.EN);
    Path wav = tmp.resolve("a.wav");
    SoundIO.writeWav(new SoundClip(new float[][] {new float[4410]}, 44100), wav);
    startFx();
    onFx(() -> {
      SoundEditorView view = new SoundEditorView(tmp, wav);
      new Scene(view, 900, 600);
      view.applyCss();
      view.layout();
      Node wave = view.lookup(".wave-box").lookup("Canvas");
      press(wave, KeyCode.A, true);
      press(wave, KeyCode.DELETE, false);
      assertThat(view.dirtyProperty().get()).isTrue();
      press(wave, KeyCode.Z, true);
      press(wave, KeyCode.S, true);
      assertThat(view.dirtyProperty().get()).isFalse();
    });
    assertThat(SoundIO.decode(wav).sampleCount()).isEqualTo(4410);
  }
}
