package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoIndentTest {

  private static String newLine(String before) {
    return AutoIndent.forNewLine(before, before.length(), "  ");
  }

  @Test
  void aNewLineIndentsOneStepPerOpenBlock() {
    assertThat(newLine("class Cat {")).isEqualTo("  ");
    assertThat(newLine("class Cat {\n  void run() {")).isEqualTo("    ");
    assertThat(newLine("class Cat {\n  void run() {\n    move(1);")).isEqualTo("    ");
    assertThat(newLine("class Cat {\n  void run() {\n  }")).isEqualTo("  ");
    assertThat(newLine("")).isEmpty();
  }

  @Test
  void aLineThatIsOffDoesNotPassItsIndentationOn() {
    // pasted at the left edge inside a method: the next line still goes in the method
    assertThat(newLine("class Cat {\n  void run() {\nmove(1);")).isEqualTo("    ");
  }

  @Test
  void aBrokenExpressionContinuesTwoSteps() {
    assertThat(newLine("class Cat {\n  void run() {\n    say(\"hi\",")).isEqualTo("        ");
  }

  @Test
  void bracketsInStringsCharsAndCommentsDoNotCount() {
    assertThat(newLine("class Cat {\n  String s = \"{{\";")).isEqualTo("  ");
    assertThat(newLine("class Cat {\n  char c = '{';")).isEqualTo("  ");
    assertThat(newLine("class Cat {\n  // {\n  /* { */")).isEqualTo("  ");
    assertThat(newLine("class Cat {\n  String s = \"\\\"{\";")).isEqualTo("  ");
  }

  @Test
  void aClosingBraceGoesUnderTheLineThatOpenedItsBlock() {
    String text = "class Cat {\n  void run() {\n    move(1);\n";
    assertThat(AutoIndent.forCloser(text, text.length())).isEqualTo("  ");
    String outer = "class Cat {\n  void run() {\n  }\n";
    assertThat(AutoIndent.forCloser(outer, outer.length())).isEmpty();
  }

  @Test
  void theFilesOwnStepIsKept() {
    assertThat(AutoIndent.forNewLine("class Cat {\n    void run() {", 28, "    "))
        .isEqualTo("        ");
    assertThat(AutoIndent.forNewLine("class Cat {\n\tvoid run() {", 25, "\t"))
        .isEqualTo("\t\t");
  }
}
