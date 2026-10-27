package org.openpatch.scratch4j.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
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
 * Picking a sprite sheet's animation on the image editor's canvas, on a
 * sheet laid out like Ninja Adventure's boy (4 columns x 7 rows of 16x16,
 * an animation runs down a column): a drag takes a column, a click on a
 * picked tile takes it out, Ctrl+Z brings it back, the strip's x removes a
 * frame, and the line is copied (needs a display and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class SheetAnimationIT {

  @TempDir
  Path root;

  private static final String SHEET = "assets/actor/character/boy/sprite-sheet.png";

  /** The middle of tile (column, row) in the canvas's coordinates. */
  private static double[] at(ImageEditorView view, int column, int row) {
    double zoom = view.zoomForTest();
    return new double[] {(column * 16 + 8) * zoom, (row * 16 + 8) * zoom};
  }

  private static void drag(ImageEditorView view, int c0, int r0, int c1, int r1) {
    double[] from = at(view, c0, r0);
    double[] to = at(view, c1, r1);
    LayoutIT.drag(view.canvasForTest(), from[0], from[1], to[0], to[1]);
  }

  private static void click(ImageEditorView view, int column, int row) {
    drag(view, column, row, column, row);
  }

  @Test
  void picksTheFramesOnThePicture() throws Exception {
    Path sheet = root.resolve(SHEET);
    Files.createDirectories(sheet.getParent());
    BufferedImage picture = new BufferedImage(64, 112, BufferedImage.TYPE_INT_ARGB);
    for (int tile = 0; tile < 28; tile++) {
      // every tile its own colour, so the preview can be checked
      int rgb = 0xff000000 | (tile * 9) << 16 | 0x80 << 8 | (255 - tile * 9);
      for (int y = 2; y < 14; y++) {
        for (int x = 2; x < 14; x++) {
          picture.setRGB(tile % 4 * 16 + x, tile / 4 * 16 + y, rgb);
        }
      }
    }
    ImageIO.write(picture, "png", sheet.toFile());

    LayoutIT.startFx();
    AtomicReference<ImageEditorView> view = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      view.set(new ImageEditorView(ScratchProject.open(root), sheet));
      Stage stage = new Stage();
      Scene scene = new Scene(view.get(), 1300, 760);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      window.set(stage);
    });
    try {
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        view.get().showSheetAnimation(true);
        SheetAnimationPanel panel = view.get().sheetPanel();
        assertThat(panel.tileWidth()).isEqualTo(16);
        assertThat(panel.columns()).isEqualTo(4);
        assertThat(panel.rows()).isEqualTo(7);
        assertThat(panel.frames()).isEmpty();
        assertThat(panel.picking()).isTrue();

        // "walk down": one drag down the first column
        drag(view.get(), 0, 0, 0, 3);
        assertThat(panel.frames()).containsExactly(0, 4, 8, 12);
        // a mis-click adds tile 1; clicking it again takes it out
        click(view.get(), 1, 0);
        assertThat(panel.frames()).containsExactly(0, 4, 8, 12, 1);
        click(view.get(), 1, 0);
        assertThat(panel.frames()).containsExactly(0, 4, 8, 12);
        // Ctrl+Z undoes picking (not painting) while the panel picks
        assertThat(view.get().shortcut(javafx.scene.input.KeyCode.Z, true, false)).isTrue();
        assertThat(panel.frames()).containsExactly(0, 4, 8, 12, 1);
        assertThat(view.get().shortcut(javafx.scene.input.KeyCode.Z, true, true)).isTrue();
        assertThat(panel.frames()).containsExactly(0, 4, 8, 12);
        // Shift-drag adds a run, a drag up a column comes in reverse
        double[] from = at(view.get(), 1, 3);
        double[] to = at(view.get(), 1, 1);
        LayoutIT.shiftDrag(view.get().canvasForTest(), from[0], from[1], to[0], to[1]);
        assertThat(panel.frames()).containsExactly(0, 4, 8, 12, 13, 9, 5);
        // a plain drag replaces: "walk right" along row 1
        drag(view.get(), 0, 1, 3, 1);
        assertThat(panel.frames()).containsExactly(4, 5, 6, 7);
        assertThat(panel.code()).isEqualTo("this.addAnimation(\"idle\", \"" + SHEET
            + "\", 16, 16, 4, 5, 6, 7);\nthis.setAnimationInterval(0.125);");
      });
      // the strip's frames live in a scroll pane: they join the scene with its skin
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        SheetAnimationPanel panel = view.get().sheetPanel();
        var cells = panel.lookupAll(".frame-cell").stream().toList();
        assertThat(cells).hasSize(4);
        // the x of the third frame (tile 6) removes it
        Node remove = cells.get(2).lookup(".frame-remove");
        ((javafx.scene.control.Button) remove).fire();
        assertThat(panel.frames()).containsExactly(4, 5, 7);
        panel.move(2, 0);
        assertThat(panel.frames()).containsExactly(7, 4, 5);
        // the interval follows the preview's speed
        panel.setFps(4);
        panel.copyCode();
        assertThat(javafx.scene.input.Clipboard.getSystemClipboard().getString())
            .isEqualTo("this.addAnimation(\"idle\", \"" + SHEET + "\", 16, 16, 7, 4, 5);\n"
                + "this.setAnimationInterval(0.25);");
        // picking off: the tools paint again (a click is a pencil stroke, not a frame)
        panel.setPicking(false);
        click(view.get(), 0, 6);
        assertThat(panel.frames()).containsExactly(7, 4, 5);
        assertThat(view.get().dirtyProperty().get()).isTrue();
        assertThat(view.get().shortcut(javafx.scene.input.KeyCode.Z, true, false)).isTrue();
        assertThat(view.get().dirtyProperty().get()).isTrue();
        panel.setPicking(true);
        drag(view.get(), 0, 0, 0, 3);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(view.get().sheetPanel().playing()).isTrue();
        // a fixed size for the picture (a tiling window manager resizes the window)
        var root = window.get().getScene().getRoot();
        window.get().getScene().setRoot(new javafx.scene.layout.Pane());
        Scene shot = new Scene((javafx.scene.Parent) root, 1300, 860);
        Theme.apply(shot);
        root.applyCss();
        root.layout();
        UiSmokeIT.snapshot(root, Path.of("target/sheet-animation.png"));
        shot.setRoot(new javafx.scene.layout.Pane());
        window.get().getScene().setRoot((javafx.scene.Parent) root);
        view.get().showSheetAnimation(false);
        assertThat(view.get().sheetPanel().playing()).isFalse();
      });
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }

  @Test
  void startsFromTheAnimationTheCodeMakesOfTheSheet() throws Exception {
    Path sheet = root.resolve("assets/cat.png");
    Files.createDirectories(sheet.getParent());
    ImageIO.write(new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB), "png", sheet.toFile());
    Files.writeString(root.resolve("Cat.java"), """
        import org.openpatch.scratch.Sprite;

        public class Cat extends Sprite {
          public Cat() {
            this.addAnimation("sit", "assets/cat.png", 16, 16, 1);
            this.setAnimationInterval(0.25);
          }
        }
        """);
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(root), sheet);
      assertThat(view.frameStrip()).as("a sheet is no numbered sequence").isNull();
      view.showSheetAnimation(true);
      assertThat(view.sheetPanel().frames()).containsExactly(1);
      assertThat(view.sheetPanel().code())
          .isEqualTo("this.addAnimation(\"sit\", \"assets/cat.png\", 16, 16, 1);\n"
              + "this.setAnimationInterval(0.25);");
    });
  }
}
