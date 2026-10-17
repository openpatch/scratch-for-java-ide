package org.openpatch.scratch4j.core.lint;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KeywordTyposTest {
  @Test void commonSingleLetterMistakesAreRecognizedWithoutListingEachMisspelling() {
    for (String keyword : List.of("public", "class", "extends", "implements")) {
      String template = switch (keyword) {
        case "public" -> "%s class Welt {}";
        case "class" -> "public %s Welt {}";
        default -> "public class Welt %s Parent {}";
      };
      int column = keyword.equals("public") ? 1 : keyword.equals("class") ? 8 : 18;
      for (String word : misspellings(keyword)) {
        String source = template.formatted(word);
        var typo = KeywordTypos.find(source, 1, column);
        assertThat(typo).as(source).isNotNull();
        assertThat(typo.keyword()).as(source).isEqualTo(keyword);
        assertThat(QuickFixes.apply(source, QuickFixes.KEYWORD, 1, column, keyword))
            .as(source).isEqualTo(template.formatted(keyword));
      }
    }
  }

  @Test void declarationsCanHaveCommentsAnnotationsGenericsAndQualifiedParents() {
    for (String source : List.of(
        "@Deprecated pulic class Welt {}",
        "@java.lang.SuppressWarnings(\"all\") pulic class Welt {}",
        "/* public cass Fake {} */ pulic /* modifier */ class Welt {}",
        "class Outer { pulic static class Welt {} }")) {
      int at = source.indexOf("pulic");
      var typo = KeywordTypos.find(source, 1, at + 1);
      assertThat(typo).as(source).isNotNull();
      assertThat(typo.keyword()).isEqualTo("public");
    }
    for (String source : List.of(
        "public class Welt<T> exends java.util.ArrayList<T> {}",
        "non-sealed class Welt exends Parent {}",
        "class Outer { class Welt exends Parent {} }",
        "class Welt extends Parent implments Runnable {}")) {
      String word = source.contains("exends") ? "exends" : "implments";
      var typo = KeywordTypos.find(source, 1, source.indexOf(word) + 1);
      assertThat(typo).as(source).isNotNull();
      assertThat(typo.keyword()).isEqualTo(word.equals("exends") ? "extends" : "implements");
    }
    assertThat(KeywordTypos.find("public\r\ncass Welt {}", 2, 1).keyword()).isEqualTo("class");
  }

  @Test void namesLiteralsCommentsAndUnrelatedErrorsAreNotKeywordCorrections() {
    for (String source : List.of(
        "class Welt { public Class world() { return null; } }",
        "class Welt { public Record world() { return null; } }",
        "class Welt { public cass world; }", "class Welt { int pulic; }",
        "class Cass {}", "class Pulic {}", "public class Welt extends Parent {}",
        "// public cass Welt {}", "/*\npublic cass Welt {}\n*/",
        "String text = \"public cass Welt {}\";",
        "String text = \"\"\"\npublic cass Welt {}\n\"\"\";")) {
      for (int column = 1; column <= source.length(); column++)
        assertThat(KeywordTypos.find(source, 1, column)).as("%s at %s", source, column).isNull();
    }
    assertThat(KeywordTypos.find("public cass Welt { int n = ; }", 1, 27)).isNull();
    assertThat(KeywordTypos.find("@Ann(1 + ) pulic class Welt {}", 1, 10))
        .as("an earlier annotation error should not be replaced by a later keyword typo").isNull();
    assertThat(QuickFixes.apply("public class Welt {}", QuickFixes.KEYWORD, 1, 8, "class")).isNull();
  }

  private static java.util.Set<String> misspellings(String word) {
    var result = new LinkedHashSet<String>();
    result.add(word.toUpperCase(java.util.Locale.ROOT));
    for (int i = 0; i < word.length(); i++) {
      result.add(word.substring(0, i) + word.substring(i + 1));
      result.add(word.substring(0, i) + "z" + word.substring(i + 1));
      if (i + 1 < word.length())
        result.add(word.substring(0, i) + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2));
    }
    for (int i = 0; i <= word.length(); i++) result.add(word.substring(0, i) + "z" + word.substring(i));
    result.remove(word);
    return result;
  }
}
