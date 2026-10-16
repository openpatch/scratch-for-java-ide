package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last check's marks follow the edits until the next check (needs a
 * display — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorProblemsIT {

  @TempDir
  Path tmp;

  @Test
  void anEditedLineLosesItsMarksAndTheLinesBelowKeepTheirs() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      var apiIndex = org.openpatch.scratch4j.core.api.ApiIndex.load();
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"), "a\nb\nc\nd", apiIndex,
          () -> tmp);
      var area = code.area();
      code.setDiagnostics(List.of(
          new CodeEditor.Diagnostic(1, 1, "one", "", true),
          new CodeEditor.Diagnostic(2, 1, "two", "", true),
          new CodeEditor.Diagnostic(4, 1, "four", "", false)));
      code.setFixes(Map.of(1, "fix one", 4, "fix four"));

      // typing on line 2 fixes it: its mark goes at once
      area.insertText(area.getAbsolutePosition(1, 1), ";");
      assertThat(code.errorLineNumbers()).containsExactly(0);
      // a new line above line 4 moves its mark down; its light bulb waits for the check
      area.insertText(area.getAbsolutePosition(2, 1), "\nnew");
      assertThat(code.errorLineNumbers()).containsExactly(0);
      assertThat(code.warningLineNumbers()).containsExactly(4);
      assertThat(code.fixes()).containsOnlyKeys(1);
    });
  }
}
