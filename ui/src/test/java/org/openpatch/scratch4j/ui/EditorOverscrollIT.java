package org.openpatch.scratch4j.ui;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Settings for the code's lines: with "scroll past the first and last
 * line" on, the code scrolls half a view past its first and last line, so both
 * can be centered; off (the default), it stops at both ends as a normal code
 * editor does. A larger line height gives every line more room
 * (needs a display — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorOverscrollIT {

  @TempDir
  Path tmp;

  @Test
  void theFirstAndLastLineScrollToTheMiddle() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    LayoutIT.onFx(() -> CodeEditor.SCROLL_PAST_END.set(true));
    StringBuilder text = new StringBuilder("class Cat {\n");
    for (int i = 0; i < 100; i++) text.append("  // line ").append(i).append('\n');
    text.append('}');
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), text.toString(),
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
      // a file opens with its first line at the top; above it is room to scroll
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        assertThat(area.getCharacterBoundsOnScreen(0, 1).orElseThrow().getMinY())
            .isLessThan(area.localToScreen(area.getLayoutBounds()).getMinY() + 30);
        area.scrollYToPixel(0);
      });
      LayoutIT.settle();
      AtomicReference<Double> middle = new AtomicReference<>();
      AtomicReference<Bounds> first = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        Bounds view = area.localToScreen(area.getLayoutBounds());
        middle.set(view.getMinY() + view.getHeight() / 2);
        first.set(area.getCharacterBoundsOnScreen(0, 1).orElseThrow());
      });
      // at the top, the first line stands in the middle of the view
      assertThat(first.get().getMinY()).isCloseTo(middle.get(), within(first.get().getHeight()));

      // the first jump goes by the estimated height (the last line's room not yet measured)
      LayoutIT.onFx(() -> editor.get().area().scrollYBy(1e6));
      LayoutIT.settle();
      LayoutIT.onFx(() -> editor.get().area().scrollYBy(1e6));
      LayoutIT.settle();
      AtomicReference<Bounds> last = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        last.set(area.getCharacterBoundsOnScreen(area.getLength() - 1, area.getLength())
            .orElseThrow());
      });
      // scrolled to the end, the last line stands there too
      assertThat(last.get().getMaxY()).isCloseTo(middle.get(), within(last.get().getHeight()));

      // going to the last line from the top centres it as well
      LayoutIT.onFx(() -> editor.get().area().scrollYToPixel(0));
      LayoutIT.settle();
      LayoutIT.onFx(() -> editor.get().gotoLine(102));
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        Bounds line = area.getCharacterBoundsOnScreen(area.getLength() - 1, area.getLength())
            .orElseThrow();
        assertThat(line.getCenterY()).isCloseTo(middle.get(), within(line.getHeight() * 1.5));
      });
    } finally {
      LayoutIT.onFx(() -> {
        window.get().hide();
        CodeEditor.SCROLL_PAST_END.set(false);
      });
    }
  }

  @Test
  void offByDefaultTheCodeStopsAtItsFirstAndLastLine() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    StringBuilder text = new StringBuilder("class Cat {\n");
    for (int i = 0; i < 100; i++) text.append("  // line ").append(i).append('\n');
    text.append('}');
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), text.toString(),
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
      LayoutIT.onFx(() -> editor.get().area().scrollYBy(-1e6));
      LayoutIT.settle();
      AtomicReference<Bounds> first = new AtomicReference<>();
      AtomicReference<Bounds> view = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        first.set(area.getCharacterBoundsOnScreen(0, 1).orElseThrow());
        view.set(area.localToScreen(area.getLayoutBounds()));
      });
      // no room above the first line: it stays at the top of the view
      assertThat(first.get().getMinY()).isLessThan(view.get().getMinY() + first.get().getHeight());

      LayoutIT.onFx(() -> editor.get().area().scrollYBy(1e6));
      LayoutIT.settle();
      AtomicReference<Bounds> last = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        last.set(area.getCharacterBoundsOnScreen(area.getLength() - 1, area.getLength())
            .orElseThrow());
        view.set(area.localToScreen(area.getLayoutBounds()));
      });
      // no room below the last line: it stays at the bottom of the view
      assertThat(last.get().getMaxY()).isGreaterThan(view.get().getMaxY() - last.get().getHeight());
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }

  @Test
  void aLargerLineHeightGivesEveryLineMoreRoom() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), "a\nb\nc",
          org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> tmp);
      Stage stage = new Stage();
      Scene scene = new Scene(code, 400, 200);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      editor.set(code);
      window.set(stage);
    });
    LayoutIT.settle();
    try {
      AtomicReference<Double> tight = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        tight.set(area.getCharacterBoundsOnScreen(2, 3).orElseThrow().getMinY()
            - area.getCharacterBoundsOnScreen(0, 1).orElseThrow().getMinY());
      });
      LayoutIT.onFx(() -> CodeEditor.LINE_HEIGHT.set(1.5));
      LayoutIT.settle();
      AtomicReference<Double> roomy = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        var area = editor.get().area();
        roomy.set(area.getCharacterBoundsOnScreen(2, 3).orElseThrow().getMinY()
            - area.getCharacterBoundsOnScreen(0, 1).orElseThrow().getMinY());
      });
      // one line grows by half a font size (a 1.5 line height on a 14px font: 7px)
      assertThat(roomy.get() - tight.get())
          .isCloseTo(CodeEditor.FONT_SIZE.get() * 0.5, within(2.0));
    } finally {
      LayoutIT.onFx(() -> {
        window.get().hide();
        CodeEditor.LINE_HEIGHT.set(1);
      });
    }
  }
}
