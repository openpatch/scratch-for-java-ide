package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoPairsTest {

  /** {@code before} with "|" for the caret (or "[...]" for a selection), after typing {@code c}. */
  private static String type(String before, char c) {
    int from = before.contains("|") ? before.indexOf('|') : before.indexOf('«');
    int to = before.contains("|") ? from : before.indexOf('»') - 1;
    String text = before.replace("|", "").replace("«", "").replace("»", "");
    var edit = AutoPairs.typed(text, from, to, c);
    if (edit == null) {
      String typed = text.substring(0, from) + c + text.substring(to);
      return typed.substring(0, from + 1) + "|" + typed.substring(from + 1);
    }
    return apply(text, edit);
  }

  private static String backspace(String before) {
    int caret = before.indexOf('|');
    String text = before.replace("|", "");
    var edit = AutoPairs.backspace(text, caret);
    if (edit == null) {
      return text.substring(0, caret - 1) + "|" + text.substring(caret);
    }
    return apply(text, edit);
  }

  private static String apply(String text, AutoPairs.Edit edit) {
    String result = text.substring(0, edit.start()) + edit.text() + text.substring(edit.end());
    if (edit.anchor() == edit.caret()) {
      return result.substring(0, edit.caret()) + "|" + result.substring(edit.caret());
    }
    return result.substring(0, edit.anchor()) + "«" + result.substring(edit.anchor(),
        edit.caret()) + "»" + result.substring(edit.caret());
  }

  @Test
  void openersBringTheirPartner() {
    assertThat(type("move|", '(')).isEqualTo("move(|)");
    assertThat(type("int|", '[')).isEqualTo("int[|]");
    assertThat(type("if (x) |", '{')).isEqualTo("if (x) {|}");
    assertThat(type("say(|);", '"')).isEqualTo("say(\"|\");");
    assertThat(type("char c = |", '\'')).isEqualTo("char c = '|'");
    assertThat(type("f(|)", '(')).isEqualTo("f((|))");
  }

  @Test
  void noPartnerInFrontOfAWordOrInsideStringsAndComments() {
    assertThat(type("|x", '(')).isEqualTo("(|x");
    assertThat(type("say(\"Hi |\");", '(')).isEqualTo("say(\"Hi (|\");");
    assertThat(type("// see |", '(')).isEqualTo("// see (|");
    assertThat(type("/* it|", '\'')).isEqualTo("/* it'|");
    assertThat(type("/* a */ f|", '(')).isEqualTo("/* a */ f(|)");
    // a quote right after a word or another quote stays alone ("""text blocks""")
    assertThat(type("x|", '"')).isEqualTo("x\"|");
    assertThat(type("\"\"|", '"')).isEqualTo("\"\"\"|");
  }

  @Test
  void aTypedCloserStepsOverTheOneThere() {
    assertThat(type("move(10|)", ')')).isEqualTo("move(10)|");
    assertThat(type("a[i|]", ']')).isEqualTo("a[i]|");
    assertThat(type("{|}", '}')).isEqualTo("{}|");
    assertThat(type("say(\"Hi|\")", '"')).isEqualTo("say(\"Hi\"|)");
    // an escaped quote stays in the string
    assertThat(type("\"a\\|\"", '"')).isEqualTo("\"a\\\"|\"");
    // the line misses a ")": this one goes in
    assertThat(type("f(g(x|)", ')')).isEqualTo("f(g(x)|)");
    assertThat(type("say(\")\"|)", ')')).isEqualTo("say(\")\")|");
  }

  @Test
  void aSelectionIsWrapped() {
    assertThat(type("x = «a + b»;", '(')).isEqualTo("x = («a + b»);");
    assertThat(type("say(«Hi»);", '"')).isEqualTo("say(\"«Hi»\");");
  }

  @Test
  void backspaceRemovesAnEmptyPair() {
    assertThat(backspace("move(|)")).isEqualTo("move|");
    assertThat(backspace("say(\"|\")")).isEqualTo("say(|)");
    assertThat(backspace("move(|x)")).isEqualTo("move|x)");
    assertThat(backspace("\"(|)\"")).isEqualTo("\"|)\"");
  }
}
