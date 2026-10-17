package org.openpatch.scratch4j.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations;
import org.openpatch.scratch4j.core.lint.ProjectCheck;
import org.openpatch.scratch4j.core.project.ScratchProject;
import static org.assertj.core.api.Assertions.assertThat;

class ProblemHintsTest {
  @TempDir Path tmp;

  @Test void declarationTyposHaveLocationHintsAndFocusedFixPreviews() throws Exception {
    I18n.Language previous = I18n.current(); I18n.set(I18n.Language.EN);
    try {
      for (var example : Map.of("public cass Welt extends Object {}", "class",
          "pulic class Welt extends Object {}", "public").entrySet()) {
        Path file = tmp.resolve("Welt.java"); Files.writeString(file, example.getKey());
        var found = ProjectCheck.check(ScratchProject.open(tmp), DiagnosticsExplanations.Language.EN).getFirst();
        Problem problem = new Problem(found.file(), found.line(), found.column(), found.message(), found.explanation(),
            found.suggestions(), found.error(), found.fix(), found.original(), found.followUp(), found.fixData(),
            found.trace(), found.caseKey(), found.diagnosticCode());
        assertThat(problem.caseKey()).isEqualTo("keyword." + example.getValue());
        assertThat(problem.hint(1)).contains("Welt");
        assertThat(problem.hint(2)).contains("before the class name").doesNotContain("parent type");
        assertThat(problem.hint(3)).contains("public class Welt extends Object {}").doesNotContain("cass", "pulic");
        assertThat(Files.readString(file)).as("a hint is a preview until Fix is clicked").isEqualTo(example.getKey());
      }
    } finally { I18n.set(previous); }
  }
}
