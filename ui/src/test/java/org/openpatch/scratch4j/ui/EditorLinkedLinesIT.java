package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line of the object chosen in a visual editor shows, also inside the
 * visual editor's tinted block (needs a display — {@code xvfb-run -a} — and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorLinkedLinesIT {

  @TempDir
  Path tmp;

  @Test
  void aLinkedLineInsideABlockIsHighlighted() throws Exception {
    LayoutIT.startFx();
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    String text = "public class MyStage extends Stage {\n"
        + "  public MyStage() {\n"
        + "    // begin stage designer\n"
        + "    Cat cat = new Cat();\n"
        + "    this.add(cat);\n"
        + "    // end stage designer\n"
        + "  }\n"
        + "}\n";
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(tmp.resolve("MyStage.java"), text,
          org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> tmp);
      Stage stage = new Stage();
      Scene scene = new Scene(code, 700, 400);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      editor.set(code);
      window.set(stage);
    });
    try {
      // the block is found in the background
      AtomicReference<String> style = new AtomicReference<>();
      for (int i = 0; i < 50 && style.get() == null; i++) {
        LayoutIT.settle();
        LayoutIT.onFx(() -> style.set(editor.get().regionStyle(4)));
      }
      assertThat(style.get()).isIn("region-line", "region-own");

      // the lines light up beside a visual editor
      LayoutIT.onFx(() -> {
        editor.get().showSidePanel("designer", new javafx.scene.layout.Pane());
        editor.get().showLinkedLines(List.of(4), false);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> UiSmokeIT.snapshot(editor.get(), Path.of("target/linked-line.png")));
      LayoutIT.onFx(() -> {
        Region linked = (Region) editor.get().area().lookup(".paragraph-text.linked-line");
        assertThat(linked).isNotNull();
        assertThat(linked.getStyleClass()).contains(style.get());
        assertThat(linked.getBackground().getFills().get(0).getFill())
            .isEqualTo(Color.web("#a371f7"));
      });

      // back to the code alone: only the caret's line is highlighted
      LayoutIT.onFx(() -> editor.get().hideSidePanel());
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(editor.get().area().lookup(".paragraph-text.linked-line")).isNull();
        assertThat(editor.get().linkedLines()).isEmpty();
      });
    } finally {
      LayoutIT.onFx(() -> window.get().hide());
    }
  }
}
