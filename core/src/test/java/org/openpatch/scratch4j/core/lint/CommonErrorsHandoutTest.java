package org.openpatch.scratch4j.core.lint;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.assertThat;
class CommonErrorsHandoutTest {
  @Test void generatesBothPrintableLanguagesFromTheCorpus() throws Exception {
    Path dir = Path.of(System.getProperty("scratch4j.handoutDir", "target/handouts"));
    CommonErrorsHandout.generate(dir);
    for (String language : new String[]{"en", "de"}) {
      String html = Files.readString(dir.resolve("common-errors-" + language + ".html"));
      assertThat(html).contains("@media print", "&lt;", "<html lang=\"" + language + "\"");
      assertThat(html.split("<article>").length - 1).isEqualTo(SnippetCorpus.load().size());
    }
  }
}
