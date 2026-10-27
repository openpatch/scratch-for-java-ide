package org.openpatch.scratch4j.ui;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.ImageCursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.tiled.TmxDocument;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The paint tools' cursors: their icons, working at the tip. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class ToolCursorsIT {

  @TempDir
  Path tmp;

  @Test
  void drawsEachToolSharpAtTheCursorSizeWithItsHotSpotAtTheTip() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      for (int size : new int[] {32, 64}) {
        for (String literal : new String[] {ToolCursors.PENCIL, ToolCursors.ERASER,
            ToolCursors.FILL, ToolCursors.PICKER}) {
          Image image = ToolCursors.image(literal, size);
          // drawn at the cursor's size: the system need not scale it up
          assertThat(image.getWidth()).isEqualTo(size);
          assertThat(image.getHeight()).isEqualTo(size);
          ToolCursors.Tip tip = literal.equals(ToolCursors.FILL)
              ? ToolCursors.Tip.BOTTOM_RIGHT : ToolCursors.Tip.BOTTOM_LEFT;
          Point2D hot = ToolCursors.hotSpot(image, tip);
          // in the lower third, on the tip's side
          assertThat(hot.getY()).isGreaterThan(size * 2 / 3.0);
          if (tip == ToolCursors.Tip.BOTTOM_LEFT) {
            assertThat(hot.getX()).isLessThan(size / 2.0);
          } else {
            assertThat(hot.getX()).isGreaterThan(size / 2.0);
          }
          write(image, "cursor-" + size + "-" + literal);
        }
        for (String literal : new String[] {"mdi2v-vector-line", "mdi2f-format-text",
            "mdi2s-selection", "fth-crop"}) {
          Image image = ToolCursors.badgeImage(literal, size);
          assertThat(image.getWidth()).isEqualTo(size);
          write(image, "cursor-" + size + "-badge-" + literal);
        }
      }
      Cursor pencil = ToolCursors.pencil();
      assertThat(pencil).isInstanceOf(ImageCursor.class).isSameAs(ToolCursors.pencil());
      assertThat(ToolCursors.badge("mdi2v-vector-line")).isInstanceOf(ImageCursor.class);
    });
  }

  private static void write(Image image, String name) throws java.io.IOException {
    javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null),
        "png", Path.of("target/" + name + ".png").toFile());
  }

  private static void key(Node target, KeyCode code, boolean shortcut) {
    boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
    Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
        false, shortcut && !mac, false, shortcut && mac));
  }

  private static void move(Node node, double x, double y) {
    var scene = node.localToScene(x, y);
    Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_MOVED, scene.getX(), scene.getY(),
        0, 0, MouseButton.NONE,
 0, false, false, false, false, false, false, false,
        false, false, false, null));
  }

  @Test
  void theImageEditorsCursorShowsTheToolAndWhatSelectDoes() throws Exception {
    Path png = tmp.resolve("a.png");
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16, 16, 2), "png", png.toFile());
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      ImageEditorView view = new ImageEditorView(ScratchProject.open(tmp), png);
      new Scene(view, 900, 600);
      view.applyCss();
      view.layout();
      Node surface = view.canvasForTest();
      assertThat(surface.getCursor()).isSameAs(ToolCursors.pencil());
      key(surface, KeyCode.E, false);
      assertThat(surface.getCursor()).isSameAs(ToolCursors.eraser());
      key(surface, KeyCode.G, false);
      assertThat(surface.getCursor()).isSameAs(ToolCursors.fill());
      key(surface, KeyCode.I, false);
      assertThat(surface.getCursor()).isSameAs(ToolCursors.picker());
      // tools that work at a point: a crosshair with the tool beside it
      key(surface, KeyCode.T, false);
      assertThat(surface.getCursor()).isSameAs(ToolCursors.badge("mdi2f-format-text"));
      key(surface, KeyCode.L, false);
      assertThat(surface.getCursor()).isSameAs(ToolCursors.badge("mdi2v-vector-line"));
      // Select: inside the selection it moves, at its corner it scales
      key(surface, KeyCode.M, false);
      key(surface, KeyCode.A, true);
      double zoom = view.zoomForTest();
      move(surface, 8 * zoom, 8 * zoom);
      assertThat(surface.getCursor()).isSameAs(Cursor.MOVE);
      move(surface, 16 * zoom - 1, 16 * zoom - 1);
      assertThat(surface.getCursor()).isSameAs(Cursor.SE_RESIZE);
    });
  }

  @Test
  void theMapEditorsCursorShowsTheToolAndPanning() throws Exception {
    Path map = tmp.resolve("level.tmx");
    TmxDocument.create(map, 4, 3, 16, 16).save();
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      MapEditorView view = new MapEditorView(ScratchProject.open(tmp), map, f -> { }, () -> { });
      new Scene(view, 900, 600);
      view.applyCss();
      view.layout();
      Node canvas = view.canvas();
      assertThat(canvas.getCursor()).isSameAs(ToolCursors.pencil());
      for (Node node : view.lookupAll(".toggle-button")) {
        if (node instanceof ToggleButton button && "PICK".equals(
            String.valueOf(button.getUserData()))) {
          button.fire();
        }
      }
      assertThat(canvas.getCursor()).isSameAs(ToolCursors.picker());
      // Space held: the hand that pans
      key(canvas, KeyCode.SPACE, false);
      assertThat(canvas.getCursor()).isSameAs(Cursor.OPEN_HAND);
      Event.fireEvent(canvas, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.SPACE,
          false, false, false, false));
      assertThat(canvas.getCursor()).isSameAs(ToolCursors.picker());
    });
  }

}
