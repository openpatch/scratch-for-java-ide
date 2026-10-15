package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.tiled.TmxDocument;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundIO;

/**
 * The image, sound and map editors keep their undo steps (and a picture its
 * layers) with the saved file: opened again, as after restarting the IDE,
 * they undo what was done before.
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorHistoryIT {

  @TempDir
  Path tmp;

  @BeforeEach
  void setUp() {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    I18n.set(I18n.Language.EN);
    startFx();
  }

  @Test
  void aPictureKeepsItsLayersAndItsUndoSteps() throws Exception {
    Path png = tmp.resolve("assets/images/cat.png");
    Files.createDirectories(png.getParent());
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, 2), "png", png.toFile());
    onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 900, 600);
      view.addLayer();
      view.image().getPixelWriter().setArgb(1, 1, 0xffff0000);
      view.save();
    });
    assertThat(tmp.resolve(".scratch4j/editor/assets/images/cat.png.history")).exists();

    onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 900, 600);
      assertThat(view.layerCount()).as("the layers are back").isEqualTo(2);
      assertThat(view.image().getPixelReader().getArgb(1, 1)).isEqualTo(0xffff0000);
      view.shortcut(KeyCode.Z, true, false);
      assertThat(view.layerCount()).as("adding the layer is undone").isEqualTo(1);
      view.shortcut(KeyCode.Z, true, true);
      assertThat(view.layerCount()).as("and redone").isEqualTo(2);
    });

    // painted elsewhere: the steps belong to another picture
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, 2), "png", png.toFile());
    onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 900, 600);
      assertThat(view.layerCount()).isEqualTo(1);
      view.shortcut(KeyCode.Z, true, false);
      assertThat(view.dirtyProperty().get()).as("nothing to undo").isFalse();
    });
  }

  @Test
  void aSoundKeepsItsUndoSteps() throws Exception {
    Path wav = tmp.resolve("a.wav");
    SoundIO.writeWav(new SoundClip(new float[][] {new float[4410]}, 44100), wav);
    onFx(() -> {
      SoundEditorView view = new SoundEditorView(tmp, wav);
      new Scene(view, 900, 600);
      view.shortcut(KeyCode.A, true, false);
      view.shortcut(KeyCode.DELETE, false, false);
      view.save();
    });
    assertThat(SoundIO.decode(wav).sampleCount()).isLessThan(4410);

    onFx(() -> {
      SoundEditorView view = new SoundEditorView(tmp, wav);
      new Scene(view, 900, 600);
      view.shortcut(KeyCode.Z, true, false);
      view.save();
    });
    assertThat(SoundIO.decode(wav).sampleCount()).isEqualTo(4410);
  }

  @Test
  void aMapKeepsItsUndoSteps() throws Exception {
    Path map = tmp.resolve("level.tmx");
    TmxDocument created = TmxDocument.create(map, 4, 3, 16, 16);
    ((TmxDocument.TileLayer) created.layers.get(0)).gids[0] = 1;
    created.save();
    onFx(() -> {
      MapEditorView view = new MapEditorView(ScratchProject.open(tmp), map, f -> { }, () -> { });
      new Scene(view, 900, 600);
      view.chooseLayer("Ground");
      view.selectTiles(0, 0, 0, 0);
      view.copySelection(true); // cut: the tile goes
      view.save();
    });
    assertThat(((TmxDocument.TileLayer) TmxDocument.open(map).layers.get(0)).gids[0]).isZero();

    onFx(() -> {
      MapEditorView view = new MapEditorView(ScratchProject.open(tmp), map, f -> { }, () -> { });
      new Scene(view, 900, 600);
      view.undo();
      view.save();
    });
    assertThat(((TmxDocument.TileLayer) TmxDocument.open(map).layers.get(0)).gids[0])
        .isEqualTo(1);
  }
}
