package org.openpatch.scratch4j.ui;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selecting with the mouse, also from next to a bracket (needs a display —
 * {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorMouseSelectionIT {

  @TempDir
  Path tmp;

  @Test
  void aDragFromNextToABracketSelects() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    String text = "class Cat {\n  void run() {\n    move(10);\n    say(\"hello there\");\n  }\n}\n";
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), text,
          org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> tmp);
      Stage stage = new Stage();
      Scene scene = new Scene(code, 700, 400);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      editor.set(code);
      window.set(stage);
    });
    LayoutIT.settle();
    try {
      // press right after "(" of move(10), drag to the end of the say line
      int from = text.indexOf("move(") + "move(".length();
      int to = text.indexOf("there\");") + "there\");".length();
      AtomicReference<Bounds> start = new AtomicReference<>();
      AtomicReference<Bounds> end = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        area.requestFocus();
        start.set(area.getCharacterBoundsOnScreen(from, from + 1).orElseThrow());
        end.set(area.getCharacterBoundsOnScreen(to - 1, to).orElseThrow());
      });
      LayoutIT.onFx(() -> {
        Robot robot = new Robot();
        robot.mouseMove(start.get().getMinX() + 1, start.get().getCenterY());
        robot.mousePress(MouseButton.PRIMARY);
      });
      LayoutIT.settle();
      for (int step = 1; step <= 10; step++) {
        double t = step / 10.0;
        double x = start.get().getMinX() + (end.get().getMaxX() - start.get().getMinX()) * t;
        double y = start.get().getCenterY() + (end.get().getCenterY() - start.get().getCenterY()) * t;
        LayoutIT.onFx(() -> new Robot().mouseMove(x, y));
        LayoutIT.settle();
      }
      LayoutIT.onFx(() -> new Robot().mouseRelease(MouseButton.PRIMARY));
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(editor.get().area().getSelectedText())
          .isEqualTo(text.substring(from, to)));
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }
}
