package org.openpatch.scratch4j.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class SyntaxHighlighterTest {
  @Test
  void longAndUnfinishedTokensDoNotOverflowTheStack() {
    String body = "some words\n".repeat(10_000);
    check("/*" + body + "*/", SyntaxHighlighter.Language.JAVA, "comment");
    check("/*" + body, SyntaxHighlighter.Language.JAVA, "comment");
    check("/*" + body + "*/", SyntaxHighlighter.Language.GLSL, "comment");
    check("\"\"\"\n" + body + "\"\"\"", SyntaxHighlighter.Language.JAVA, "string");
    check("\"\"\"\n" + body, SyntaxHighlighter.Language.JAVA, "string");
    check("<!--" + body + "-->", SyntaxHighlighter.Language.XML, "comment");
    check("<!--" + body, SyntaxHighlighter.Language.XML, "comment");
    String escaped = "word\\\"\\\\".repeat(10_000);
    check("\"" + escaped + "\"", SyntaxHighlighter.Language.JAVA, "string");
    check("\"" + escaped, SyntaxHighlighter.Language.JAVA, "string");
    check("\"" + escaped + "\"", SyntaxHighlighter.Language.JSON, "string");
    check("\"" + escaped + "\": true", SyntaxHighlighter.Language.JSON, "key");
  }

  @Test
  void escapedDelimitersStayInsideStringsAndFollowingCodeIsHighlighted() {
    String source = "\"a\\\" /* not a comment */\"; public class Cat {}";
    var spans = SyntaxHighlighter.highlight(source, SyntaxHighlighter.Language.JAVA);
    assertThat(styleAt(spans, source.indexOf("not"))).containsExactly("string");
    assertThat(styleAt(spans, source.indexOf("public"))).containsExactly("keyword");
    assertThat(styleAt(spans, source.indexOf("Cat"))).containsExactly("type");
    source = "\"unfinished\npublic class Cat {}";
    spans = SyntaxHighlighter.highlight(source, SyntaxHighlighter.Language.JAVA);
    assertThat(styleAt(spans, source.indexOf("public"))).containsExactly("keyword");
  }

  private static void check(String text, SyntaxHighlighter.Language language, String style) {
    var spans = SyntaxHighlighter.highlight(text, language);
    assertThat(spans.length()).isEqualTo(text.length());
    assertThat(styleAt(spans, 0)).containsExactly(style);
    assertThat(styleAt(spans, text.length() / 2)).containsExactly(style);
  }

  private static Collection<String> styleAt(StyleSpans<Collection<String>> spans, int at) {
    for (var span : spans) {
      if (at < span.getLength()) return span.getStyle();
      at -= span.getLength();
    }
    throw new AssertionError("Missing style");
  }
}
