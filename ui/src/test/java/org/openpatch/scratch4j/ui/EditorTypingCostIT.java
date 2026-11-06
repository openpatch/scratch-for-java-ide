package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Typing stays cheap on slow computers: a key restyles only the lines it
 * changed (not the whole file, which rebuilt every line on screen), and the
 * gutters on screen are the only ones that follow the marks — gutters built
 * again for lines that moved must not leave listeners behind
 * (needs a display — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorTypingCostIT {

  @TempDir
  Path tmp;

  @Test
  void aKeyRebuildsOnlyWhatItChanged() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    StringBuilder text = new StringBuilder("public class Cat extends Sprite {\n");
    for (int i = 0; i < 200; i++) {
      text.append("  public void m").append(i).append("() { int x = ").append(i)
          .append("; // note\n  }\n");
    }
    text.append('}');
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), text.toString(),
          org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> tmp);
      Stage stage = new Stage();
      Scene scene = new Scene(code, 700, 500);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      editor.set(code);
      window.set(stage);
    });
    LayoutIT.settle();
    try {
      AtomicInteger before = new AtomicInteger();
      // typing a letter inside a line: that line's gutter at most
      LayoutIT.onFx(() -> {
        before.set(editor.get().gutterBuilds());
        var area = editor.get().area();
        area.insertText(5, 10, "y");
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(editor.get().gutterBuilds() - before.get())
          .as("gutters rebuilt by one typed letter").isLessThanOrEqualTo(2));

      // many Enters with fixes in the file: only the gutters on screen listen
      for (int round = 0; round < 30; round++) {
        LayoutIT.onFx(() -> {
          Map<Integer, String> fixes = new HashMap<>();
          for (int line = 1; line < 300; line += 3) fixes.put(line, "Fix");
          editor.get().setFixes(fixes);
          var area = editor.get().area();
          area.insertText(3, 0, "\n");
        });
        Thread.sleep(60);
      }
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        int visible = editor.get().area().getVisibleParagraphs().size();
        assertThat(editor.get().liveGutterCount())
            .as("live gutters (%d lines on screen)", visible)
            .isLessThanOrEqualTo(visible + 4);
      });
    } finally {
      LayoutIT.onFx(() -> window.get().close());
    }
  }
}
