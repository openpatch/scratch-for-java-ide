package org.openpatch.scratch4j.ui;

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
 * The image editor's sprite-sheet animation preview on a 32x16 sheet of two
 * 16x16 frames (like Ninja Adventure's cat): the animation the code already
 * makes is loaded, tiles are added by clicking, the preview plays, and the
 * addAnimation line is copied (needs a display and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class SheetAnimationIT {

  @TempDir
  Path root;

  @Test
  void previewsAndCopiesAnAnimationOfTheSheet() throws Exception {
    Path sheet = root.resolve("assets/actor/animal/cat/sprite-sheet.png");
    Files.createDirectories(sheet.getParent());
    BufferedImage picture = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
    for (int y = 4; y < 12; y++) {
      for (int x = 4; x < 12; x++) {
        picture.setRGB(x, y, 0xffe08030);
        picture.setRGB(x + 16, y + 2, 0xffe08030);
      }
    }
    ImageIO.write(picture, "png", sheet.toFile());
    Files.writeString(root.resolve("Cat.java"), """
        import org.openpatch.scratch.Sprite;

        public class Cat extends Sprite {
          public Cat() {
            this.addAnimation("sit", "assets/actor/animal/cat/sprite-sheet.png", 16, 16, 1);
          }
        }
        """);

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
        assertThat(view.get().frameStrip()).as("a sheet is no numbered sequence").isNull();
        view.get().showSheetAnimation(true);
        SheetAnimationPanel panel = view.get().sheetPanel();
        // the animation the code makes of this sheet is the starting point
        assertThat(panel.tileWidth()).isEqualTo(16);
        assertThat(panel.frames()).containsExactly(1);
        assertThat(panel.code()).isEqualTo("this.addAnimation(\"sit\", "
            + "\"assets/actor/animal/cat/sprite-sheet.png\", 16, 16, 1);");
      });
      // the tiles live in a scroll pane: they join the scene with its skin
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        SheetAnimationPanel panel = view.get().sheetPanel();
        // a tile click adds that tile: 1, 0
        var tiles = panel.lookupAll(".frame-thumb").stream().toList();
        assertThat(tiles).hasSize(2);
        LayoutIT.click(tiles.getFirst());
        assertThat(panel.frames()).containsExactly(1, 0);
        panel.copyCode();
        assertThat(javafx.scene.input.Clipboard.getSystemClipboard().getString())
            .isEqualTo("this.addAnimation(\"sit\", "
                + "\"assets/actor/animal/cat/sprite-sheet.png\", 16, 16, 1, 0);");
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(view.get().sheetPanel().playing()).isTrue();
        // a fixed size for the picture (a tiling window manager resizes the window)
        var root = window.get().getScene().getRoot();
        window.get().getScene().setRoot(new javafx.scene.layout.Pane());
        Scene shot = new Scene((javafx.scene.Parent) root, 1300, 760);
        Theme.apply(shot);
        root.applyCss();
        root.layout();
        UiSmokeIT.snapshot(root, Path.of("target/sheet-animation.png"));
        shot.setRoot(new javafx.scene.layout.Pane());
        window.get().getScene().setRoot((javafx.scene.Parent) root);
        // hidden, it stops playing
        view.get().showSheetAnimation(false);
        assertThat(view.get().sheetPanel().playing()).isFalse();
      });
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }
}
