package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image editor's frame strip on walk1.png ... walk3.png: switching
 * frames saves the one shown, adding and moving frames renumber the files,
 * and the onion skin tints the frames around (needs a display and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class FrameStripIT {

  @TempDir
  Path root;

  private Path frame(int number) {
    return root.resolve("assets/images/walk" + number + ".png");
  }

  /** A 16x16 frame with one coloured square at x = number * 3 (frames tell apart). */
  private void writeFrame(int number, int rgb) throws Exception {
    BufferedImage picture = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 4; y < 8; y++) {
      for (int x = number * 3; x < number * 3 + 3; x++) {
        picture.setRGB(x, y, 0xff000000 | rgb);
      }
    }
    Files.createDirectories(frame(number).getParent());
    ImageIO.write(picture, "png", frame(number).toFile());
  }

  private int pixel(Path file, int x, int y) throws Exception {
    return ImageIO.read(file.toFile()).getRGB(x, y);
  }

  @Test
  void paintsTheFramesOfANumberedSequenceInOneTab() throws Exception {
    writeFrame(1, 0xff0000);
    writeFrame(2, 0x00ff00);
    writeFrame(3, 0x0000ff);
    Files.writeString(root.resolve("assets/images/walk.png"), "not a frame");
    assertThat(ImageEditorView.sequenceOf(root, root.resolve("assets/images/walk.png"))).isNull();
    assertThat(ImageEditorView.sequenceOf(root, frame(2))).isNotNull();

    LayoutIT.startFx();
    AtomicReference<ImageEditorView> view = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicReference<Tab> tab = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      ImageEditorView editor = new ImageEditorView(ScratchProject.open(root), frame(1));
      view.set(editor);
      EditorTabs tabs = new EditorTabs(null, () -> root);
      tab.set(tabs.openTool("image:" + frame(1), "walk1.png", "fth-image", () -> editor));
      Stage stage = new Stage();
      Scene scene = new Scene(tabs, 1200, 760);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      window.set(stage);
    });
    try {
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        FrameStrip strip = view.get().frameStrip();
        assertThat(strip).isNotNull();
        assertThat(strip.frames()).hasSize(3);
        assertThat(strip.current()).isZero();
        assertThat(strip.lookupAll(".frame-thumb")).hasSize(3);

        // paint into frame 1, then switch: frame 1 is saved, the tab shows walk2.png
        view.get().paintPixelForTest(0, 0, Color.WHITE);
        assertThat(tab.get().getText()).isEqualTo("walk1.png  ●");
        strip.select(1);
        assertThat(view.get().file()).isEqualTo(frame(2).toAbsolutePath().normalize());
        assertThat(tab.get().getText()).isEqualTo("walk2.png");
      });
      assertThat(pixel(frame(1), 0, 0)).isEqualTo(0xffffffff);
      LayoutIT.onFx(() -> {
        FrameStrip strip = view.get().frameStrip();
        // move frame 2 earlier: the green square is now walk1.png, still shown
        strip.move(-1);
        assertThat(strip.current()).isZero();
        // a blank frame at the end
        strip.addBlank();
        assertThat(strip.frames()).hasSize(4);
        assertThat(strip.current()).isEqualTo(3);
        strip.select(1);
      });
      assertThat(pixel(frame(1), 2 * 3, 4)).isEqualTo(0xff00ff00);
      assertThat(pixel(frame(2), 3, 4)).isEqualTo(0xffff0000);
      assertThat(ImageIO.read(frame(4).toFile()).getWidth()).isEqualTo(16);

      // onion skin on frame 2: frame 1 tinted red, frame 3 tinted blue
      LayoutIT.onFx(() -> view.get().setOnionForTest(true));
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        // a fixed size for the picture (a tiling window manager resizes the window)
        var tabs = window.get().getScene().getRoot();
        window.get().getScene().setRoot(new javafx.scene.layout.Pane());
        Scene shot = new Scene((javafx.scene.Parent) tabs, 1200, 720);
        Theme.apply(shot);
        tabs.applyCss();
        tabs.layout();
        UiSmokeIT.snapshot(tabs, Path.of("target/frame-strip.png"));
        shot.setRoot(new javafx.scene.layout.Pane());
        window.get().getScene().setRoot((javafx.scene.Parent) tabs);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        // delete frame 2: walk3.png becomes walk2.png and is shown
        view.get().frameStrip().delete();
        assertThat(view.get().frameStrip().frames()).hasSize(3);
        assertThat(view.get().frameStrip().current()).isEqualTo(1);
      });
      assertThat(pixel(frame(2), 3 * 3, 4)).isEqualTo(0xff0000ff);
      assertThat(Files.list(root.resolve(".scratch4j/trash")).count()).isEqualTo(1);
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }

  @Test
  void tintKeepsAlphaAndMixesHalfWay() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      javafx.scene.image.WritableImage source = new javafx.scene.image.WritableImage(2, 1);
      source.getPixelWriter().setArgb(0, 0, 0x80000000);
      source.getPixelWriter().setArgb(1, 0, 0xffffffff);
      var tinted = ImageEditorView.tint(source, Color.rgb(200, 0, 100));
      assertThat(tinted.getPixelReader().getArgb(0, 0)).isEqualTo(0x80640032);
      assertThat(tinted.getPixelReader().getArgb(1, 0)).isEqualTo(0xffe37fb1);
    });
  }
}
