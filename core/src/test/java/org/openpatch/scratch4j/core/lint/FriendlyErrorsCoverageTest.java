package org.openpatch.scratch4j.core.lint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import static org.assertj.core.api.Assertions.assertThat;

class FriendlyErrorsCoverageTest {
  @TempDir Path tmp;

  @Test void corpusMeasuresRawFallbacksAndEveryFixCompiles() throws Exception {
    var compiler = new CompilerService();
    var gaps = new ArrayList<String>();
    var coverage = new org.assertj.core.api.SoftAssertions();
    for (var s : SnippetCorpus.load()) {
      Path dir = Files.createDirectories(tmp.resolve(s.id()));
      Path file = dir.resolve(s.className() + ".java");
      Files.writeString(file, s.broken());
      var errors = compiler.compile(List.of(file), List.of(), dir.resolve("out"), Locale.ROOT).errors();
      assertThat(errors).as(s.id()).isNotEmpty();
      var d = errors.getFirst();
      var friendly = new FriendlyErrors(DiagnosticsExplanations.Language.EN).describe(d,
          new FriendlyErrors.Context(s.broken().lines().toList(), s.className(), n -> null, List.of()));
      if (friendly.caseKey() == null) gaps.add(s.id() + " | " + d.code());
      System.out.println(s.id() + " | " + d.code() + " | " + friendly.caseKey());
      coverage.assertThat(friendly.caseKey()).as(s.id()).isEqualTo(s.caseKey());
      Files.writeString(file, s.fixed());
      coverage.assertThat(compiler.compile(List.of(file), List.of(), dir.resolve("out"), Locale.ROOT).errors())
          .as("fixed %s", s.id()).isEmpty();
    }
    Path report = Path.of("target/friendly-errors-gaps.txt");
    Files.createDirectories(report.getParent());
    Files.write(report, gaps);
    coverage.assertAll();
  }
}
