package org.openpatch.scratch4j.ui;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.api.ApiIndex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Blocks dragged from the palette over the code: the preview opens the lines
 * the block would take, a drop puts it there, and where it can't go a note
 * says why (needs a display — {@code xvfb-run -a} — and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class BlockDropIT {

  private static final String CALL = "this.turnRight(15);\n";
  private static final String EVENT = "public void whenClicked() {\n  \n}\n";

  private static final String CAT = """
      import org.openpatch.scratch.*;

      public class Cat extends Sprite {
        private int speed = 2;

        public Cat() {
          this.addCostume("cat", "cat.png");
        }

        public void run() {
          this.move(speed);
          if (this.isTouchingEdge()) {
            this.turnLeft(180);
          }
        }
      }
      """;

  @TempDir
  Path tmp;

  private static int line(String start) {
    List<String> lines = CAT.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().startsWith(start)) return i;
    }
    throw new AssertionError(start);
  }

  private static Bounds lineBounds(CodeEditor code, int line) {
    return code.area().getParagraphBoundsOnScreen(line).orElseThrow();
  }

  /** A point in the upper or lower quarter of a line, right of the gutter. */
  private static double[] over(CodeEditor code, int line, boolean upper) {
    Bounds b = lineBounds(code, line);
    return new double[] {b.getMinX() + 240,
        b.getMinY() + b.getHeight() * (upper ? 0.25 : 0.75)};
  }

  private static List<String> ghostLines(CodeEditor code) {
    List<String> lines = new ArrayList<>();
    code.dropLayer().lookupAll(".drop-ghost-code")
        .forEach(n -> lines.add(((Label) n).getText()));
    return lines;
  }

  @Test
  void aStackBlockOpensALineInsideTheMethodAndDropsThere() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    List<String> dropped = new ArrayList<>();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), CAT, ApiIndex.load(), () -> tmp);
      code.setOnBlockDropped(dropped::add);
      Scene scene = new Scene(code, 760, 520);
      Theme.apply(scene);
      Stage stage = new Stage();
      stage.setScene(scene);
      stage.show();
      editor.set(code);
    });
    LayoutIT.settle();
    CodeEditor code = editor.get();
    int ifLine = line("if (this.isTouchingEdge");
    AtomicReference<double[]> pointer = new AtomicReference<>();
    AtomicReference<Double> row = new AtomicReference<>();
    AtomicReference<Double> ifTop = new AtomicReference<>();

    // the upper half of the if: a new line above it, the code shown in it
    LayoutIT.onFx(() -> {
      row.set(lineBounds(code, ifLine).getHeight());
      ifTop.set(lineBounds(code, ifLine).getMinY());
      pointer.set(over(code, ifLine, true));
      var target = code.previewBlock(CALL, "cat-motion", pointer.get()[0], pointer.get()[1]);
      assertThat(target.valid()).isTrue();
      assertThat(target.slot()).isEqualTo(new BlockDrop.Slot(ifLine, false));
      assertThat(ghostLines(code)).containsExactly("this.turnRight(15);");
    });
    LayoutIT.settle();
    LayoutIT.onFx(() -> {
      // the line above opened up by one line: the if moved down by one
      assertThat(lineBounds(code, ifLine - 1).getHeight()).isCloseTo(2 * row.get(), within(1.0));
      assertThat(lineBounds(code, ifLine).getMinY()).isCloseTo(ifTop.get() + row.get(),
          within(1.0));
      // the code sits in the new line, at the indent of the statements around
      Bounds ghost = code.dropLayer().lookup(".drop-ghost-code").localToScreen(
          code.dropLayer().lookup(".drop-ghost-code").getLayoutBounds());
      assertThat(ghost.getMinY()).isCloseTo(ifTop.get(), within(1.0));
      int moveStart = CAT.indexOf("this.move(speed)");
      Bounds move = code.area().getCharacterBoundsOnScreen(moveStart, moveStart + 1).orElseThrow();
      assertThat(ghost.getMinX()).isCloseTo(move.getMinX(), within(1.5));
      UiSmokeIT.snapshot(code, Path.of("target/block-drop-preview.png"));

      // the pointer now lies in the opened line: the preview stays as it is
      var again = code.previewBlock(CALL, "cat-motion", pointer.get()[0], pointer.get()[1]);
      assertThat(again.slot()).isEqualTo(new BlockDrop.Slot(ifLine, false));

      assertThat(code.dropBlock(CALL, pointer.get()[0], pointer.get()[1])).isTrue();
      assertThat(code.area().getText()).contains("""
              this.move(speed);
              this.turnRight(15);
              if (this.isTouchingEdge()) {
          """);
      assertThat(code.area().getCaretPosition())
          .isEqualTo(code.area().getText().indexOf("this.turnRight(15);") + CALL.length() - 1);
      assertThat(dropped).containsExactly(CALL);
      assertThat(code.dropLayer().getChildren()).isEmpty();
      assertThat(code.area().getParagraph(ifLine - 1).getParagraphStyle())
          .noneMatch(s -> s.startsWith("drop-gap"));
      // one step back takes the block out again
      code.undo();
      assertThat(code.area().getText()).isEqualTo(CAT);
    });
    LayoutIT.settle();
    LayoutIT.onFx(() ->
        assertThat(lineBounds(code, ifLine - 1).getHeight()).isCloseTo(row.get(), within(1.0)));

    // the class body takes no calls: a note, no lines, no drop
    LayoutIT.onFx(() -> {
      double[] field = over(code, line("private int speed"), true);
      var target = code.previewBlock(CALL, "cat-motion", field[0], field[1]);
      assertThat(target.problem()).isEqualTo(BlockDrop.Problem.BODY);
      assertThat(ghostLines(code)).isEmpty();
      Label note = (Label) code.dropLayer().lookup(".drop-note");
      assertThat(note.getText()).isEqualTo(I18n.t("drop.body"));
      UiSmokeIT.snapshot(code, Path.of("target/block-drop-refused.png"));
      assertThat(code.dropBlock(CALL, field[0], field[1])).isFalse();
      assertThat(code.area().getText()).isEqualTo(CAT);
    });
  }

  @Test
  void anEventBlockGoesBetweenMethodsAndAReporterInline() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), CAT, ApiIndex.load(), () -> tmp);
      Scene scene = new Scene(code, 760, 520);
      Theme.apply(scene);
      Stage stage = new Stage();
      stage.setScene(scene);
      stage.show();
      editor.set(code);
    });
    LayoutIT.settle();
    CodeEditor code = editor.get();
    int blank = line("public void run()") - 1;
    AtomicReference<Double> row = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      // inside a method: refused
      double[] inRun = over(code, line("this.move(speed)"), false);
      assertThat(code.previewBlock(EVENT, "cat-events", inRun[0], inRun[1]).problem())
          .isEqualTo(BlockDrop.Problem.CLASS_BODY);

      // the blank line between two methods: the method fills it, with blank lines around
      row.set(lineBounds(code, blank).getHeight());
      double[] between = over(code, blank, true);
      var target = code.previewBlock(EVENT, "cat-events", between[0], between[1]);
      assertThat(target.slot()).isEqualTo(new BlockDrop.Slot(blank, true));
      assertThat(ghostLines(code)).containsExactly("public void whenClicked() {", "  ", "}");
    });
    LayoutIT.settle();
    LayoutIT.onFx(() -> {
      // four lines more (bold and plain lines differ by a fraction of a pixel)
      assertThat(lineBounds(code, blank).getHeight()).isCloseTo(5 * row.get(), within(3.0));
      UiSmokeIT.snapshot(code, Path.of("target/block-drop-event.png"));
      double[] between = over(code, blank, true);
      assertThat(code.dropBlock(EVENT, between[0], between[1])).isTrue();
      assertThat(code.area().getText()).contains("""
            }

            public void whenClicked() {
             \s
            }

            public void run() {
          """);
      code.undo();
    });
    LayoutIT.settle();
    LayoutIT.onFx(() -> {
      // a reporter: its code in the line where it goes, a caret at its start
      String reporter = "this.getX()";
      int argument = CAT.indexOf("this.move(") + "this.move(".length();
      Bounds character = code.area().getCharacterBoundsOnScreen(argument, argument + 1)
          .orElseThrow();
      double x = character.getMinX() + 1;
      double y = character.getMinY() + character.getHeight() / 2;
      var inline = code.previewBlock(reporter, "cat-motion", x, y);
      assertThat(inline.valid()).isTrue();
      assertThat(code.dropLayer().lookup(".drop-caret")).isNotNull();
      Label value = (Label) code.dropLayer().lookup(".drop-ghost-value");
      assertThat(value.getText()).isEqualTo(reporter);
      Bounds shown = value.localToScreen(value.getLayoutBounds());
      assertThat(shown.getMinY()).isCloseTo(lineBounds(code, line("this.move(speed)")).getMinY(),
          within(1.0));
      assertThat(shown.getMinX()).isCloseTo(character.getMinX(), within(1.5));
      UiSmokeIT.snapshot(code, Path.of("target/block-drop-value.png"));
      Bounds after = code.area().getCharacterBoundsOnScreen(argument, argument + 1).orElseThrow();
      x = after.getMinX() + 1;
      y = after.getMinY() + after.getHeight() / 2;
      assertThat(code.dropBlock(reporter, x, y)).isTrue();
      assertThat(code.area().getText()).contains("this.move(this.getX()speed);");
    });
  }

  @Test
  void aDoubleClickedBlockGoesInOnlyWhereTheCaretMayTakeIt() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), CAT, ApiIndex.load(), () -> tmp);
      Scene scene = new Scene(code, 760, 520);
      Theme.apply(scene);
      Stage stage = new Stage();
      stage.setScene(scene);
      stage.show();
      editor.set(code);
    });
    LayoutIT.settle();
    CodeEditor code = editor.get();
    LayoutIT.onFx(() -> {
      var area = code.area();
      // the class body: refused, with a note under the caret saying what to do
      int field = line("private int speed");
      area.moveTo(field, area.getParagraph(field).length());
      assertThat(code.insertBlock(CALL)).isFalse();
      assertThat(area.getText()).isEqualTo(CAT);
      assertThat(code.caretNoteText()).isEqualTo(I18n.t("caret.body"));
      Bounds note = code.dropLayer().lookup(".drop-note").localToScreen(
          code.dropLayer().lookup(".drop-note").getLayoutBounds());
      assertThat(note.getMinY()).isGreaterThanOrEqualTo(lineBounds(code, field).getMaxY());
      UiSmokeIT.snapshot(code, Path.of("target/block-insert-refused.png"));
      // moving the caret takes the note away
      area.moveTo(line("this.move(speed)"), area.getParagraph(line("this.move(speed)")).length());
      assertThat(code.caretNoteText()).isNull();
      assertThat(code.dropLayer().lookup(".drop-note")).isNull();

      // the end of a statement: on a new line below it
      assertThat(code.insertBlock(CALL)).isTrue();
      assertThat(area.getText()).contains("""
              this.move(speed);
              this.turnRight(15);
              if (this.isTouchingEdge()) {
          """);
      code.undo();

      // a reporter in a string: refused
      int path = CAT.indexOf("cat.png");
      area.moveTo(path);
      assertThat(code.insertBlock("this.getX()")).isFalse();
      assertThat(code.caretNoteText()).isEqualTo(I18n.t("caret.text"));
      assertThat(area.getText()).isEqualTo(CAT);

      // an event block on the blank line between two methods fills it
      int blank = line("public void run()") - 1;
      area.moveTo(blank, 0);
      assertThat(code.insertBlock(EVENT)).isTrue();
      assertThat(area.getText()).contains("""
            }

            public void whenClicked() {
             \s
            }

            public void run() {
          """);
      code.undo();
      assertThat(area.getText()).isEqualTo(CAT);
    });
  }

  /** A mouse event at a screen point, with the primary button down while it is dragged. */
  private static void mouse(javafx.scene.Node node,
      javafx.event.EventType<javafx.scene.input.MouseEvent> type, double screenX, double screenY) {
    javafx.geometry.Point2D local = node.screenToLocal(screenX, screenY);
    var eventType = type;
    boolean down = type != javafx.scene.input.MouseEvent.MOUSE_RELEASED;
    javafx.event.Event.fireEvent(node, new javafx.scene.input.MouseEvent(eventType,
        local.getX(), local.getY(), screenX, screenY, javafx.scene.input.MouseButton.PRIMARY, 1,
        false, false, false, false, down, false, false, true, false, true, null));
  }

  @Test
  void aBlockDraggedFromThePaletteFollowsThePointerAndDropsWhereItsPreviewShowed()
      throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<BlockPalette> palette = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), CAT, ApiIndex.load(), () -> tmp);
      BlockPalette blocks = new BlockPalette(ApiIndex.load(), m -> { });
      code.dockPalette(blocks);
      Scene scene = new Scene(code, 1000, 520);
      Theme.apply(scene);
      Stage stage = new Stage();
      stage.setScene(scene);
      stage.show();
      editor.set(code);
      palette.set(blocks);
    });
    LayoutIT.settle();
    CodeEditor code = editor.get();
    AtomicReference<javafx.scene.control.ListCell<?>> cell = new AtomicReference<>();
    AtomicReference<String> snippet = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      // the first stack block shown
      for (var node : palette.get().lookupAll(".list-cell")) {
        if (node instanceof javafx.scene.control.ListCell<?> c
            && c.getItem() instanceof BlockPalette.Entry entry && !entry.isHeading()
            && BlockDrop.kindOf(BlockPalette.snippet(entry.method())) == BlockDrop.Kind.STATEMENT) {
          cell.set(c);
          snippet.set(BlockPalette.snippet(entry.method()));
          break;
        }
      }
      assertThat(cell.get()).isNotNull();
    });
    int ifLine = line("if (this.isTouchingEdge");
    LayoutIT.onFx(() -> {
      Bounds start = cell.get().localToScreen(cell.get().getLayoutBounds());
      double[] over = over(code, ifLine, true);
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_PRESSED,
          start.getMinX() + 20, start.getMinY() + 10);
      // a little way: not yet a drag
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
          start.getMinX() + 22, start.getMinY() + 11);
      assertThat(code.lookup(".block-drag-chip")).isNull();
      // over the palette: the block follows, the code shows nothing
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
          start.getMinX() + 60, start.getMinY() + 30);
      assertThat(code.lookup(".block-drag-chip")).isNotNull();
      assertThat(ghostLines(code)).isEmpty();
      // over the code: the preview opens a line above the if
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_DRAGGED, over[0], over[1]);
      assertThat(ghostLines(code)).containsExactly(snippet.get().strip());
    });
    LayoutIT.settle();
    LayoutIT.onFx(() -> {
      double[] over = over(code, ifLine, true);
      // the line opened under the pointer: moving within it keeps the preview
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_DRAGGED, over[0] + 5, over[1]);
      assertThat(ghostLines(code)).containsExactly(snippet.get().strip());
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_RELEASED, over[0] + 5, over[1]);
      assertThat(code.lookup(".block-drag-chip")).isNull();
      assertThat(code.dropLayer().getChildren()).isEmpty();
      assertThat(code.area().getText()).contains("    this.move(speed);\n    "
          + snippet.get().strip() + "\n    if (this.isTouchingEdge()) {");
      code.undo();

      // let go over the palette: nothing goes in
      Bounds start = cell.get().localToScreen(cell.get().getLayoutBounds());
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_PRESSED,
          start.getMinX() + 20, start.getMinY() + 10);
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_DRAGGED, over[0], over[1]);
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
          start.getMinX() + 40, start.getMinY() + 10);
      assertThat(ghostLines(code)).isEmpty();
      mouse(cell.get(), javafx.scene.input.MouseEvent.MOUSE_RELEASED,
          start.getMinX() + 40, start.getMinY() + 10);
      assertThat(code.area().getText()).isEqualTo(CAT);
      assertThat(code.lookup(".block-drag-chip")).isNull();
    });
  }
}
