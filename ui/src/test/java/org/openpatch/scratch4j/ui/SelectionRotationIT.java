package org.openpatch.scratch4j.ui;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A selection turns by its handle or the angle field, Fast or RotSprite, and undoes. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class SelectionRotationIT {

  @TempDir
  Path tmp;

  private static void mouse(Node node, javafx.event.EventType<MouseEvent> type, double x,
      double y, boolean shift) {
    var scene = node.localToScene(x, y);
    boolean down = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
    Event.fireEvent(node, new MouseEvent(type, scene.getX(), scene.getY(), 0, 0,
        MouseButton.PRIMARY, 1, shift, false, false, false, down, false, false, false, false,
        type == MouseEvent.MOUSE_PRESSED, null));
  }

  private static void key(Node node, KeyCode code, boolean shortcut) {
    boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
    Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false,
        shortcut && !mac, false, shortcut && mac));
  }

  @Test
  void theHandleTurnsTheSelectionAndUndoTakesItBack() throws Exception {
    // a 64 x 64 picture with a 20 x 6 red bar in the middle
    var picture = new java.awt.image.BufferedImage(64, 64,
        java.awt.image.BufferedImage.TYPE_INT_ARGB);
    for (int y = 29; y < 35; y++) {
      for (int x = 22; x < 42; x++) picture.setRGB(x, y, 0xffff0000);
    }
    Path png = tmp.resolve("bar.png");
    javax.imageio.ImageIO.write(picture, "png", png.toFile());
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 1000, 800);
      view.applyCss();
      view.layout();
      Node surface = view.canvasForTest();
      double zoom = view.zoomForTest();
      key(surface, KeyCode.M, false);
      // select the bar with some room around it
      mouse(surface, MouseEvent.MOUSE_PRESSED, 20 * zoom, 20 * zoom, false);
      mouse(surface, MouseEvent.MOUSE_DRAGGED, 43 * zoom, 43 * zoom, false);
      mouse(surface, MouseEvent.MOUSE_RELEASED, 43 * zoom, 43 * zoom, false);
      // the handle sits above the middle; drag it round to the right: a quarter turn
      double cx = 32 * zoom;
      double top = 20 * zoom - 22;
      mouse(surface, MouseEvent.MOUSE_MOVED, cx, top, false);
      assertThat(surface.getCursor()).isSameAs(ToolCursors.rotate());
      mouse(surface, MouseEvent.MOUSE_PRESSED, cx, top, false);
      // Shift snaps: a little short of a quarter still turns 90 degrees
      mouse(surface, MouseEvent.MOUSE_DRAGGED, 32 * zoom + 200, 31 * zoom + 12, true);
      assertThat(view.rotationForTest()).isEqualTo(90);
      mouse(surface, MouseEvent.MOUSE_RELEASED, 32 * zoom + 200, 31 * zoom + 12, true);
      key(surface, KeyCode.ENTER, false); // place it
      var pixels = view.image().getPixelReader();
      // the bar stands upright now: 6 wide, 20 tall, about the same centre
      assertThat(pixels.getArgb(31, 24)).isEqualTo(0xffff0000);
      assertThat(pixels.getArgb(31, 40)).isEqualTo(0xffff0000);
      assertThat(pixels.getArgb(24, 31)).isZero();
      assertThat(pixels.getArgb(40, 31)).isZero();
      key(surface, KeyCode.Z, true);
      assertThat(view.image().getPixelReader().getArgb(24, 31)).isEqualTo(0xffff0000);
      assertThat(view.image().getPixelReader().getArgb(31, 24)).isZero();
    });
  }

  @Test
  void fastAndRotSpriteSideBySide() throws Exception {
    Path scroll = Path.of("../core/src/main/resources/org/openpatch/scratch4j/core/templates/"
        + "demo-tiled/assets/ScrollFire.png");
    Path png = tmp.resolve("scroll.png");
    // the sprite in the middle of a picture with room to turn
    var sprite = javax.imageio.ImageIO.read(scroll.toFile());
    var picture = new java.awt.image.BufferedImage(48, 48,
        java.awt.image.BufferedImage.TYPE_INT_ARGB);
    picture.getGraphics().drawImage(sprite, 8, 8, null);
    javax.imageio.ImageIO.write(picture, "png", png.toFile());
    LayoutIT.startFx();
    for (SpriteRotation.Algorithm algorithm : SpriteRotation.Algorithm.values()) {
      LayoutIT.onFx(() -> {
        ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
        new Scene(view, 1000, 800);
        view.applyCss();
        view.layout();
        Node surface = view.canvasForTest();
        key(surface, KeyCode.M, false);
        key(surface, KeyCode.A, true);
        view.rotationAlgorithmForTest().setValue(algorithm);
        view.rotateSelection(30);
        key(surface, KeyCode.ENTER, false);
        var image = view.image();
        var out = new java.awt.image.BufferedImage(48, 48,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 48; y++) {
          for (int x = 0; x < 48; x++) out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        Files.createDirectories(Path.of("target"));
        javax.imageio.ImageIO.write(out, "png",
            Path.of("target/rotate-30-" + algorithm + ".png").toFile());
      });
    }
    assertThat(Path.of("target/rotate-30-ROTSPRITE.png")).exists();
  }
}
