package org.openpatch.scratch4j.core.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuickFixesTest {

  @Test
  void keywordFixStaysInsideTheDeclarationAndRejectsStaleCode() {
    assertThat(QuickFixes.apply("public class Welt exends Spielwelt {", QuickFixes.KEYWORD, 1, 18, "extends"))
        .isEqualTo("public class Welt extends Spielwelt {");
    assertThat(QuickFixes.apply("public class Welt\n  exends Spielwelt {", QuickFixes.KEYWORD, 1, 18, "extends"))
        .isEqualTo("public class Welt\n  extends Spielwelt {");
    for (String source : List.of("public class Welt extends Spielwelt {", "public class Welt { int exends; }",
        "// public class Welt exends Spielwelt {", "String s = \"public class Welt exends Spielwelt {\";"))
      assertThat(QuickFixes.apply(source, QuickFixes.KEYWORD, 1, 18, "extends")).as(source).isNull();
    assertThat(QuickFixes.apply("public class Welt exends Spielwelt {", QuickFixes.KEYWORD, 1, 18, "implements")).isNull();
    assertThat(QuickFixes.apply("\t\tpublic cass Welt {}", QuickFixes.KEYWORD, 1, 24, "class"))
        .isEqualTo("\t\tpublic class Welt {}");
  }

  @Test
  void previewShowsTheChangedAreaInsteadOfTheWholeFile() {
    String source = "class A {\n  int first;\n  int second;\n  int n = 1\n  int fourth;\n  int fifth;\n}";
    assertThat(QuickFixes.preview(source, QuickFixes.SEMICOLON, 4, 12, null))
        .isEqualTo("  int second;\n  int n = 1;\n  int fourth;");
  }

  @Test
  void semicolonGoesWhereJavacPointed() {
    String source = "class A {\n  void go() {\n    int x = 1\n    int y = 2;\n  }\n}\n";
    // javac's caret for "';' expected" is right after the 1: line 3, column 14
    assertThat(QuickFixes.apply(source, QuickFixes.SEMICOLON, 3, 14, null))
        .isEqualTo("class A {\n  void go() {\n    int x = 1;\n    int y = 2;\n  }\n}\n");
  }

  @Test
  void semicolonSkipsTrailingSpacesAndKeepsComments() {
    assertThat(QuickFixes.apply("int x = 1   // one\n", QuickFixes.SEMICOLON, 1, 10, null))
        .isEqualTo("int x = 1;   // one\n");
    assertThat(QuickFixes.apply("int x = 1  \n", QuickFixes.SEMICOLON, 1, 0, null))
        .isEqualTo("int x = 1;  \n");
  }

  @Test
  void semicolonDoesNotApplyTwice() {
    assertThat(QuickFixes.apply("int x = 1;\n", QuickFixes.SEMICOLON, 1, 11, null)).isNull();
    assertThat(QuickFixes.apply("int x = 1;\n", QuickFixes.SEMICOLON, 7, 1, null)).isNull();
  }

  @Test
  void importGoesAfterTheExistingImports() {
    String source = "import org.openpatch.scratch.*;\n\npublic class A extends Sprite {\n}\n";
    assertThat(QuickFixes.apply(source, QuickFixes.IMPORT, 3, 1, "import java.util.List;"))
        .isEqualTo("import org.openpatch.scratch.*;\nimport java.util.List;\n\n"
            + "public class A extends Sprite {\n}\n");
  }

  @Test
  void importGoesToTheTopOfAFileWithoutImports() {
    assertThat(QuickFixes.apply("public class A {\n}\n", QuickFixes.IMPORT, 1, 1,
        "import java.util.List;"))
        .isEqualTo("import java.util.List;\n\npublic class A {\n}\n");
    assertThat(QuickFixes.apply("package demo;\n\npublic class A {\n}\n", QuickFixes.IMPORT,
        3, 1, "import java.util.List;"))
        .isEqualTo("package demo;\n\nimport java.util.List;\n\npublic class A {\n}\n");
  }

  @Test
  void importAlreadyThereIsStale() {
    assertThat(QuickFixes.apply("import java.util.List;\nclass A {}\n", QuickFixes.IMPORT, 2, 1,
        "import java.util.List;")).isNull();
  }

  @Test
  void renameReplacesTheNameJavacPointedAt() {
    assertThat(QuickFixes.apply("    System.out.println(numer);\n", QuickFixes.RENAME, 1, 24,
        "number")).isEqualTo("    System.out.println(number);\n");
    // for this.mvoe javac points at the dot
    assertThat(QuickFixes.apply("    this.mvoe(4);\n", QuickFixes.RENAME, 1, 9, "move"))
        .isEqualTo("    this.move(4);\n");
  }

  @Test
  void renamePicksTheCaseVariantOrTheOnlySuggestion() {
    assertThat(QuickFixes.pickRename("setsize", List.of("setSize", "setSizeTo"))).isEqualTo("setSize");
    assertThat(QuickFixes.pickRename("numer", List.of("number"))).isEqualTo("number");
    assertThat(QuickFixes.pickRename("numer", List.of("number", "super"))).isEqualTo("number");
    assertThat(QuickFixes.pickRename("numer", List.of("numbr", "numes"))).isNull();
    assertThat(QuickFixes.pickRename("nmr", List.of("number"))).isNull();
    assertThat(QuickFixes.pickRename("number", List.of("number"))).isNull();
    assertThat(QuickFixes.pickRename("", List.of("number"))).isNull();
  }

  @Test
  void foreverUnwrapsTheLoopAndDedentsItsBody() {
    String source = String.join("\n",
        "public class Cat extends Sprite {",
        "  public void run() {",
        "    while (true) {",
        "      if (this.isKeyPressed(KeyCode.RIGHT)) {",
        "        this.move(2); // \"}\" in a string is fine",
        "      }",
        "    }",
        "  }",
        "}", "");
    assertThat(QuickFixes.canRemoveForever(source, 3)).isTrue();
    assertThat(QuickFixes.apply(source, QuickFixes.FOREVER, 3, 0, null)).isEqualTo(String.join("\n",
        "public class Cat extends Sprite {",
        "  public void run() {",
        "    if (this.isKeyPressed(KeyCode.RIGHT)) {",
        "      this.move(2); // \"}\" in a string is fine",
        "    }",
        "  }",
        "}", ""));
  }

  @Test
  void foreverIsOnlyOfferedForAPlainWrapper() {
    assertThat(QuickFixes.canRemoveForever("  while (true) { this.move(2); }\n", 1)).isFalse();
    assertThat(QuickFixes.canRemoveForever("  do {\n    x++;\n  } while (true);\n", 1)).isFalse();
    assertThat(QuickFixes.canRemoveForever("  while (x) {\n  }\n", 1)).isFalse();
    assertThat(QuickFixes.canRemoveForever("  for (;;) {\n    x++;\n  }\n", 1)).isTrue();
    assertThat(QuickFixes.apply("  while (x) {\n  }\n", QuickFixes.FOREVER, 1, 0, null)).isNull();
  }
}
