package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code is shown in the bundled Monaspace family chosen in Settings (needs a
 * display — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class CodeFontIT {

  @TempDir
  Path tmp;

  @Test
  void everyFamilyLoadsWithBoldAndItalic() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      for (Prefs.CodeFont font : Prefs.CodeFont.values()) {
        String family = Theme.codeFamily(font);
        assertThat(family).startsWith("Monaspace " + font.label());
        assertThat(Font.font(family, FontWeight.BOLD, 14).getStyle()).contains("Bold");
        assertThat(Font.font(family, FontPosture.ITALIC, 14).getStyle()).contains("Italic");
      }
    });
  }

  @Test
  void theEditorDrawsCodeInTheChosenFamily() throws Exception {
    LayoutIT.startFx();
    Prefs.CodeFont before = Theme.codeFont();
    try {
      LayoutIT.onFx(() -> {
        CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), "class Cat {}",
            org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> tmp);
        Scene scene = new Scene(code, 400, 200);
        Stage stage = new Stage();
        stage.setScene(scene);
        Theme.setCodeFont(scene, Prefs.CodeFont.XENON);
        stage.show();
        code.applyCss();
        code.layout();
        String family = Theme.codeFamily(Prefs.CodeFont.XENON);
        // the code, keywords in bold too
        assertThat(code.area().lookupAll(".text"))
            .filteredOn(org.fxmisc.richtext.TextExt.class::isInstance).isNotEmpty()
            .allSatisfy(text -> assertThat(((Text) text).getFont().getFamily()).isEqualTo(family));
        // and the line numbers beside it
        assertThat(code.area().lookupAll(".lineno")).isNotEmpty()
            .allSatisfy(n -> assertThat(((javafx.scene.control.Label) n).getFont().getFamily())
                .isEqualTo(family));
        stage.hide();
      });
    } finally {
      LayoutIT.onFx(() -> Theme.setCodeFont(new Scene(new javafx.scene.layout.Pane()), before));
    }
  }
}
