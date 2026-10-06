package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerDiagnostic;
import org.openpatch.scratch4j.core.compile.CompilerService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticsExplanationsTest {

  @TempDir
  Path tmp;

  private final CompilerService compiler = new CompilerService();

  @Test
  void everyExplanationKeyExistsInBothLanguages() {
    for (DiagnosticsExplanations.Language language : DiagnosticsExplanations.Language.values()) {
      for (String code : List.of(
          "compiler.err.expected",
          "compiler.err.premature.eof",
          "compiler.err.cant.resolve.symbol",
          "compiler.err.cant.resolve.location",
          "compiler.err.prob.found.req",
          "compiler.err.missing.ret.stmt",
          "compiler.err.unreachable.stmt",
          "compiler.err.not.stmt",
          "compiler.err.illegal.start.of.expr",
          "compiler.err.already.defined",
          "compiler.err.class.public.should.be.in.file",
          "compiler.err.cannot.apply.symbol",
          "compiler.err.cant.apply.symbol",
          "compiler.err.cant.apply.symbols",
          "compiler.err.cant.resolve.args",
          "compiler.err.cant.resolve.location.args",
          "compiler.err.var.might.not.have.been.initialized",
          "compiler.err.else.without.if",
          "compiler.err.illegal.start.of.type",
          "compiler.err.abstract.cant.be.instantiated",
          "compiler.err.does.not.override.abstract",
          "compiler.err.unreported.exception.need.to.catch")) {
        var explanation = DiagnosticsExplanations.explain(code, language);
        assertThat(explanation).as("%s has a %s explanation", code, language).isPresent();
        assertThat(explanation.orElseThrow()).isNotBlank();
      }
    }
  }

  @Test
  void englishAndGermanTextsDiffer() {
    String en = DiagnosticsExplanations.explain("compiler.err.expected",
        DiagnosticsExplanations.Language.EN).orElseThrow();
    String de = DiagnosticsExplanations.explain("compiler.err.expected",
        DiagnosticsExplanations.Language.DE).orElseThrow();
    assertThat(en).isNotEqualTo(de);
  }

  @Test
  void warningsAndUnknownCodesHaveNoExplanation() {
    assertThat(DiagnosticsExplanations.explain("compiler.warn.prob.found.req",
        DiagnosticsExplanations.Language.EN)).isEmpty();
    assertThat(DiagnosticsExplanations.explain("compiler.err.some.future.code",
        DiagnosticsExplanations.Language.DE)).isEmpty();
    assertThat(DiagnosticsExplanations.explain(null,
        DiagnosticsExplanations.Language.EN)).isEmpty();
  }

  /** The classic beginner mistakes must produce codes we can explain. */
  @Test
  void realBeginnerErrorsAreAllExplained() throws IOException {
    record Case(String fileName, String source) {}
    List<Case> corpus = List.of(
        new Case("MissingSemi.java", """
            public class MissingSemi {
              public void go() {
                int x = 1
              }
            }
            """),
        new Case("Typo.java", """
            public class Typo {
              public void go() {
                int number = 1;
                System.out.println(numer);
              }
            }
            """),
        new Case("WrongType.java", """
            public class WrongType {
              public void go() {
                int x = "hello";
              }
            }
            """),
        new Case("MissingReturn.java", """
            public class MissingReturn {
              public int go() {
                int x = 1;
                if (x > 0) {
                  return x;
                }
              }
            }
            """),
        new Case("Unreachable.java", """
            public class Unreachable {
              public void go() {
                return;
                int x = 1;
              }
            }
            """),
        new Case("WrongFileName.java", """
            public class OtherName {
            }
            """),
        new Case("UnclosedBrace.java", """
            public class UnclosedBrace {
              public void go() {
                int x = 1;
            }
            """),
        new Case("MethodTypo.java", """
            public class MethodTypo {
              public void go() {
                this.og();
              }
            }
            """),
        new Case("WrongArguments.java", """
            public class WrongArguments {
              public void go(int steps) {
                this.go("ten");
              }
            }
            """),
        new Case("Uninitialized.java", """
            public class Uninitialized {
              public int go() {
                int score;
                return score;
              }
            }
            """),
        new Case("StatementInClass.java", """
            public class StatementInClass {
              System.out.println("hi");
            }
            """));

    List<String> unexplained = new java.util.ArrayList<>();
    for (Case c : corpus) {
      Path src = tmp.resolve(c.fileName());
      Files.writeString(src, c.source());
      CompileResult result = compiler.compile(List.of(src), List.of(), tmp.resolve("out"));
      assertThat(result.errors()).as("%s has errors", c.fileName()).isNotEmpty();
      for (CompilerDiagnostic error : result.errors()) {
        var explanation = DiagnosticsExplanations.explain(error.code(),
            DiagnosticsExplanations.Language.EN);
        if (explanation.isEmpty()) {
          unexplained.add(c.fileName() + " -> " + error.code());
        }
      }
    }
    assertThat(unexplained).as("codes without a beginner explanation").isEmpty();
  }

  /**
   * Asking javac for Locale.ENGLISH falls back to the system locale's bundle
   * on a German machine; the default compile must be English regardless.
   */
  @Test
  void compilerMessagesAreEnglishByDefaultAndGermanOnRequest() throws IOException {
    Locale saved = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      Path src = tmp.resolve("Typo.java");
      Files.writeString(src, "public class Typo { void go() { this.og(); } }");
      String english = compiler.compile(List.of(src), List.of(), tmp.resolve("out"))
          .errors().get(0).message();
      String german = compiler.compile(List.of(src), List.of(), tmp.resolve("out"),
          Locale.GERMAN).errors().get(0).message();
      assertThat(english).startsWith("cannot find symbol");
      assertThat(german).isNotEqualTo(english);
    } finally {
      Locale.setDefault(saved);
    }
  }
}
