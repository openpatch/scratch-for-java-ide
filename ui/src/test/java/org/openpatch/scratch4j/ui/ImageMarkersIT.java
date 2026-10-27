package org.openpatch.scratch4j.ui;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The hitbox, rotation centre and facing arrow stand out on dark and light pixels. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class ImageMarkersIT {

  @TempDir
  Path tmp;

  private static void mouse(Node node, javafx.event.EventType<MouseEvent> type, double x,
      double y, MouseButton button) {
    var scene = node.localToScene(x, y);
    boolean down = type != MouseEvent.MOUSE_RELEASED;
    Event.fireEvent(node, new MouseEvent(type, scene.getX(), scene.getY(), 0, 0, button, 1,
        false, false, false, false, down && button == MouseButton.PRIMARY, false,
        down && button == MouseButton.SECONDARY, false, false,
        type == MouseEvent.MOUSE_PRESSED, null));
  }

  private static void click(Node node, double x, double y) {
    click(node, x, y, MouseButton.PRIMARY);
  }

  private static void click(Node node, double x, double y, MouseButton button) {
    mouse(node, MouseEvent.MOUSE_PRESSED, x, y, button);
    mouse(node, MouseEvent.MOUSE_RELEASED, x, y, button);
  }

  private static void key(Node node, javafx.scene.input.KeyCode code, boolean shortcut) {
    boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
    Event.fireEvent(node, new javafx.scene.input.KeyEvent(
        javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false,
        shortcut && mac));
  }

  private ImageEditorView open(Path png) throws Exception {
    ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
    new Scene(view, 1000, 700);
    view.applyCss();
    view.layout();
    return view;
  }

  private Path blank(String name) throws Exception {
    Path png = tmp.resolve(name);
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32, 32,
        java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
    return png;
  }

  private static void tool(ImageEditorView view, String name) {
    for (Node node : view.lookupAll(".tool-button")) {
      if (node instanceof ToggleButton button && name.equals(String.valueOf(button.getUserData()))) {
        button.fire();
      }
    }
  }

  @Test
  void markersShowOnDarkAndLightPixels() throws Exception {
    // left half black, right half white
    var picture = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 32; y++) {
      for (int x = 0; x < 32; x++) picture.setRGB(x, y, x < 16 ? 0xff101010 : 0xfff4f4f4);
    }
    Path png = tmp.resolve("cat.png");
    javax.imageio.ImageIO.write(picture, "png", png.toFile());
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 1000, 700);
      view.applyCss();
      view.layout();
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      tool(view, "HITBOX");
      click(surface, 4 * zoom, 4 * zoom);
      click(surface, 28 * zoom, 6 * zoom);
      click(surface, 26 * zoom, 28 * zoom);
      click(surface, 6 * zoom, 26 * zoom);
      tool(view, "CENTER");
      click(surface, 16 * zoom, 16 * zoom);
      for (Node node : view.lookupAll(".toggle-button")) {
        if (node instanceof ToggleButton button && button.getTooltip() != null
            && button.getTooltip().getText().equals(I18n.t("imageeditor.facing.tooltip"))) {
          button.fire();
        }
      }
      WritableImage shot = surface.snapshot(null, null);
      UiSmokeIT.snapshot(surface, Path.of("target/image-markers.png"));
      // a hitbox point on black: an orange dot, ringed white
      var reader = shot.getPixelReader();
      Color dot = reader.getColor((int) (4 * zoom), (int) (4 * zoom));
      assertThat(dot.getRed()).isGreaterThan(0.9);
      assertThat(dot.getGreen()).isBetween(0.35, 0.6);
      Color ring = reader.getColor((int) (4 * zoom) + 6, (int) (4 * zoom));
      assertThat(ring.getBrightness()).isGreaterThan(0.9);
      assertThat(ring.getSaturation()).isLessThan(0.1);
    });
  }

  @Test
  void aLineShowsAsItWillBePaintedAndUndoes() throws Exception {
    Path png = blank("line.png");
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = open(png);
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      key(surface, javafx.scene.input.KeyCode.L, false);
      // the brush 2 wide: three pixels thick, as plot paints it
      for (Node node : view.lookupAll(".combo-box")) {
        if (node instanceof javafx.scene.control.ComboBox<?> box
            && box.getItems().contains(16)) {
          @SuppressWarnings("unchecked")
          var sizes = (javafx.scene.control.ComboBox<Integer>) box;
          sizes.setValue(2);
        }
      }
      mouse(surface, MouseEvent.MOUSE_PRESSED, 4 * zoom, 10 * zoom, MouseButton.PRIMARY);
      mouse(surface, MouseEvent.MOUSE_DRAGGED, 24 * zoom, 10 * zoom, MouseButton.PRIMARY);
      // while dragging: the line's pixels in the pen's colour, a pixel off the middle too
      Color pen = view.penColorForTest();
      var shot = surface.snapshot(null, null).getPixelReader();
      assertThat(shot.getColor((int) (14.5 * zoom), (int) (11.5 * zoom))).isEqualTo(pen);
      assertThat(view.image().getPixelReader().getArgb(14, 10)).isZero(); // not painted yet
      UiSmokeIT.snapshot(surface, Path.of("target/line-preview.png"));
      mouse(surface, MouseEvent.MOUSE_RELEASED, 24 * zoom, 10 * zoom, MouseButton.PRIMARY);
      assertThat(view.image().getPixelReader().getColor(14, 11)).isEqualTo(pen);
      key(surface, javafx.scene.input.KeyCode.Z, true);
      assertThat(view.image().getPixelReader().getArgb(14, 10)).isZero();
      assertThat(view.image().getPixelReader().getArgb(14, 11)).isZero();
    });
  }

  @Test
  void hitboxPointsMoveAndGo() throws Exception {
    Path png = blank("hitbox.png");
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = open(png);
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      tool(view, "HITBOX");
      click(surface, 4 * zoom, 4 * zoom);
      click(surface, 28 * zoom, 4 * zoom);
      click(surface, 16 * zoom, 28 * zoom);
      var points = view.hitboxPointsForTest();
      assertThat(points).hasSize(3);
      // pressing a point drags it instead of adding one
      mouse(surface, MouseEvent.MOUSE_PRESSED, 28 * zoom + 2, 4 * zoom + 2, MouseButton.PRIMARY);
      mouse(surface, MouseEvent.MOUSE_DRAGGED, 20 * zoom, 8 * zoom, MouseButton.PRIMARY);
      mouse(surface, MouseEvent.MOUSE_RELEASED, 20 * zoom, 8 * zoom, MouseButton.PRIMARY);
      assertThat(points).hasSize(3);
      assertThat(points.get(1)).isEqualTo(new javafx.geometry.Point2D(20, 8));
      // right-click removes a point; Delete the one picked last
      click(surface, 4 * zoom, 4 * zoom, MouseButton.SECONDARY);
      assertThat(points).hasSize(2);
      click(surface, 20 * zoom, 8 * zoom);
      key(surface, javafx.scene.input.KeyCode.DELETE, false);
      assertThat(points).containsExactly(new javafx.geometry.Point2D(16, 28));
    });
  }

  @Test
  void theMarkersShowWithEveryToolOrOnlyTheirOwn() throws Exception {
    Path png = blank("markers.png");
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = open(png);
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      tool(view, "CENTER");
      click(surface, 16 * zoom, 16 * zoom);
      tool(view, "NINE_SLICE");
      tool(view, "PENCIL");
      // the nine-slice guide (8 from the top) and the centre show with the pencil
      var shot = surface.snapshot(null, null).getPixelReader();
      assertThat(shot.getColor((int) (2 * zoom), (int) (8 * zoom)).getSaturation())
          .isGreaterThan(0.5);
      assertThat(shot.getColor((int) (16 * zoom) + 8, (int) (16 * zoom)).getRed())
          .isGreaterThan(0.6);
      UiSmokeIT.snapshot(surface, Path.of("target/markers-shown.png"));
      ToggleButton markers = (ToggleButton) view.lookupAll(".toggle-button").stream()
          .filter(n -> n instanceof ToggleButton b && b.getTooltip() != null && b.getTooltip()
              .getText().equals(I18n.t("imageeditor.markers.tooltip")))
          .findFirst().orElseThrow();
      markers.fire();
      shot = surface.snapshot(null, null).getPixelReader();
      assertThat(shot.getColor((int) (2 * zoom), (int) (8 * zoom)).getSaturation())
          .isLessThan(0.2);
      // with its own tool a marker shows anyway
      tool(view, "NINE_SLICE");
      shot = surface.snapshot(null, null).getPixelReader();
      assertThat(shot.getColor((int) (2 * zoom), (int) (8 * zoom)).getSaturation())
          .isGreaterThan(0.5);
    });
  }

  @Test
  void theEllipseIsAWholeRingAndTextIsPixelSized() throws Exception {
    Path png = blank("ellipse.png");
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = open(png);
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      key(surface, javafx.scene.input.KeyCode.O, false);
      mouse(surface, MouseEvent.MOUSE_PRESSED, 4 * zoom, 4 * zoom, MouseButton.PRIMARY);
      mouse(surface, MouseEvent.MOUSE_DRAGGED, 24 * zoom, 24 * zoom, MouseButton.PRIMARY);
      mouse(surface, MouseEvent.MOUSE_RELEASED, 24 * zoom, 24 * zoom, MouseButton.PRIMARY);
      var pixels = view.image().getPixelReader();
      // all four sides, and the ring is symmetric
      assertThat(pixels.getArgb(14, 4)).isNotZero();
      assertThat(pixels.getArgb(14, 24)).isNotZero();
      assertThat(pixels.getArgb(4, 14)).isNotZero();
      assertThat(pixels.getArgb(24, 14)).isNotZero();
      assertThat(pixels.getArgb(14, 14)).isZero(); // an outline, not a disc
      for (int y = 4; y <= 24; y++) {
        for (int x = 4; x <= 24; x++) {
          boolean set = pixels.getArgb(x, y) != 0;
          assertThat(pixels.getArgb(28 - x, y) != 0).as("mirror of %d,%d", x, y).isEqualTo(set);
          assertThat(pixels.getArgb(x, 28 - y) != 0).as("flip of %d,%d", x, y).isEqualTo(set);
        }
      }
      UiSmokeIT.snapshot(surface, Path.of("target/ellipse.png"));
    });
    Path text = blank("text.png");
    LayoutIT.onFx(() -> {
      ImageEditorView view = open(text);
      view.drawText(1, 1, "Hi");
      var pixels = view.image().getPixelReader();
      // brush 1: an H seven pixels tall, top-left at the click
      int top = 32;
      int bottom = -1;
      for (int y = 0; y < 32; y++) {
        if (pixels.getArgb(1, y) != 0) {
          top = Math.min(top, y);
          bottom = Math.max(bottom, y);
        }
      }
      assertThat(top).isEqualTo(1);
      assertThat(bottom).isEqualTo(7);
      assertThat(pixels.getArgb(0, 1)).isZero();
      UiSmokeIT.snapshot(view.canvasForTest(), Path.of("target/text.png"));
    });
  }

  @Test
  void theTextToolShowsWhereTheTextGoes() throws Exception {
    Path png = blank("sample.png");
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = open(png);
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      key(surface, javafx.scene.input.KeyCode.T, false);
      mouse(surface, MouseEvent.MOUSE_MOVED, 4 * zoom + 1, 6 * zoom + 1, MouseButton.NONE);
      var shot = surface.snapshot(null, null).getPixelReader();
      // the sample's T: its top bar starts at the pixel under the mouse, in the pen's colour
      Color top = shot.getColor((int) (4.5 * zoom), (int) (6.5 * zoom));
      assertThat(top.getBrightness()).isLessThan(0.4);
      assertThat(shot.getColor((int) (6.5 * zoom), (int) (12.5 * zoom)).getBrightness())
          .isLessThan(0.4); // the T's stem, six pixels lower
      assertThat(view.image().getPixelReader().getArgb(4, 6)).isZero(); // nothing painted
      UiSmokeIT.snapshot(surface, Path.of("target/text-sample.png"));
      // gone once the mouse leaves
      mouse(surface, MouseEvent.MOUSE_EXITED, 0, 0, MouseButton.NONE);
      shot = surface.snapshot(null, null).getPixelReader();
      assertThat(shot.getColor((int) (4.5 * zoom), (int) (6.5 * zoom)).getBrightness())
          .isGreaterThan(0.6);
    });
  }
}
