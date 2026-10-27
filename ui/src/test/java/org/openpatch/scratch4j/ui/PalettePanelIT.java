package org.openpatch.scratch4j.ui;

import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image editor's palette panel in a window: after "from this picture"
 * the swatches on screen are the picture's colours, four rows tall (needs a
 * display and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class PalettePanelIT {

  @TempDir
  Path root;

  @Test
  void showsThePicturesColoursAfterMakingAPaletteFromIt() throws Exception {
    BufferedImage picture = new BufferedImage(20, 2, BufferedImage.TYPE_INT_ARGB);
    for (int x = 0; x < 20; x++) {
      // twenty colours, the leftmost the most common
      picture.setRGB(x, 0, 0xff000000 | (x * 12) << 16 | 0x40 << 8 | 0x20);
      picture.setRGB(x, 1, 0xff000000 | (x == 0 ? 0 : x * 12) << 16 | 0x40 << 8 | 0x20);
    }
    LayoutIT.startFx();
    AtomicReference<PalettePanel> panel = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    SimpleObjectProperty<Color> colour = new SimpleObjectProperty<>(Color.BLACK);
    LayoutIT.onFx(() -> {
      panel.set(new PalettePanel(root, colour));
      Stage stage = new Stage();
      Scene scene = new Scene(panel.get(), 220, 360);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      window.set(stage);
    });
    try {
      LayoutIT.settle();
      LayoutIT.onFx(() -> panel.get().fromPicture(picture, "stripes"));
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(panel.get().paletteId()).isEqualTo("assets/palettes/stripes.gpl");
        var swatches = panel.get().lookupAll(".palette-swatch-small").stream().toList();
        assertThat(swatches).hasSize(20);
        // the first swatch on screen is the most common colour, not a Scratch one
        var first = swatches.getFirst();
        var bounds = first.localToScene(first.getBoundsInLocal());
        var shot = window.get().getScene().snapshot(null);
        Color pixel = shot.getPixelReader().getColor((int) bounds.getCenterX(),
            (int) bounds.getCenterY());
        assertThat(pixel.getRed()).isCloseTo(0, org.assertj.core.data.Offset.offset(0.02));
        assertThat(pixel.getGreen()).isCloseTo(0x40 / 255.0,
            org.assertj.core.data.Offset.offset(0.02));
        // 20 swatches, 8 per row: the third row is on screen too
        var last = swatches.getLast();
        var lastBounds = last.localToScene(last.getBoundsInLocal());
        assertThat(lastBounds.getMaxY()).isLessThan(
            panel.get().lookup(".palette-swatches").localToScene(
                panel.get().lookup(".palette-swatches").getBoundsInLocal()).getMaxY() + 1);
        UiSmokeIT.snapshot(window.get().getScene().getRoot(),
            Path.of("target/palette-panel.png"));
      });
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }
}
