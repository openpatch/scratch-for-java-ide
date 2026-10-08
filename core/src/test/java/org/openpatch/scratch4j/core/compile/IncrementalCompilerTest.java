package org.openpatch.scratch4j.core.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalCompilerTest {

  @TempDir
  Path tmp;

  private final Map<String, String> files = new LinkedHashMap<>();

  private Map<Path, String> texts() throws IOException {
    Map<Path, String> texts = new LinkedHashMap<>();
    for (var e : files.entrySet()) {
      Path file = tmp.resolve("src").resolve(e.getKey() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue());
      texts.put(file, e.getValue());
    }
    return texts;
  }

  /** File, line and javac code of each error: what the problems pane shows. */
  private static List<String> errors(CompileResult result) {
    return result.errors().stream()
        .map(d -> Path.of(d.path()).getFileName() + ":" + d.line() + " " + d.code())
        .sorted().toList();
  }

  /** The incremental result after the current edit, checked against a full compile. */
  private CompileResult step(IncrementalCompiler incremental) throws IOException {
    Map<Path, String> texts = texts();
    CompileResult result = incremental.compile(texts, List.of(), Locale.ROOT);
    try (IncrementalCompiler full = new IncrementalCompiler(tmp.resolve("full"))) {
      CompileResult expected = full.compile(texts, List.of(), Locale.ROOT);
      assertThat(errors(result)).as("same errors as a full compile").isEqualTo(errors(expected));
      assertThat(result.success()).isEqualTo(expected.success());
    }
    return result;
  }

  @Test
  void compilesOnlyWhatAChangeCanReachAndMatchesAFullCompile() throws IOException {
    files.put("A", "public class A { public int f() { return 1; } }");
    files.put("B", "public class B { A a = new A(); public A getA() { return a; } int g() { return a.f(); } }");
    files.put("C", "public class C { void h() { } }");
    // D reaches A only through B: it never names A
    files.put("D", "public class D { int k(B b) { return b.getA().f(); } }");
    try (IncrementalCompiler compiler = new IncrementalCompiler(tmp.resolve("out"))) {
      assertThat(step(compiler).success()).isTrue();
      assertThat(compiler.lastStats().full()).isTrue();

      // nothing changed: nothing compiled
      step(compiler);
      assertThat(compiler.lastStats().compiled()).isZero();

      // C is named by nobody: only C
      files.put("C", "public class C { void h() { int x = 2; } }");
      step(compiler);
      assertThat(compiler.lastStats().compiled()).isEqualTo(1);

      // A loses f(): B names A, D names B, so all three are compiled and both fail
      files.put("A", "public class A { }");
      CompileResult broken = step(compiler);
      assertThat(errors(broken)).anyMatch(e -> e.startsWith("B.java"))
          .anyMatch(e -> e.startsWith("D.java"));
      assertThat(compiler.lastStats().compiled()).isEqualTo(3);

      // a syntax error elsewhere while the others are still broken
      files.put("C", "public class C { void h() { int x = 2 } }");
      step(compiler);

      // everything fixed again
      files.put("A", "public class A { public int f() { return 1; } }");
      files.put("C", "public class C { void h() { } }");
      assertThat(step(compiler).success()).isTrue();

      // A removed: whoever named it fails
      files.remove("A");
      Files.delete(tmp.resolve("src/A.java"));
      assertThat(errors(step(compiler))).anyMatch(e -> e.startsWith("B.java"));
      assertThat(tmp.resolve("out/A.class")).doesNotExist();

      // back again, and a second top-level class in C that B starts to use
      files.put("A", "public class A { public int f() { return 1; } }");
      files.put("C", "public class C { void h() { } }\nclass Helper { static int two() { return 2; } }");
      files.put("B", "public class B { A a = new A(); public A getA() { return a; } int g() { return a.f() + Helper.two(); } }");
      assertThat(step(compiler).success()).isTrue();

      // Helper changes: B names it although no file is called Helper.java
      files.put("C", "public class C { void h() { } }\nclass Helper { }");
      assertThat(errors(step(compiler))).anyMatch(e -> e.startsWith("B.java"));
    }
  }

  @Test
  void aNewLanguageOrClasspathCompilesEverythingAgain() throws IOException {
    files.put("A", "public class A { }");
    files.put("B", "public class B { A a; }");
    try (IncrementalCompiler compiler = new IncrementalCompiler(tmp.resolve("out"))) {
      compiler.compile(texts(), List.of(), Locale.ROOT);
      compiler.compile(texts(), List.of(), Locale.GERMAN);
      assertThat(compiler.lastStats().full()).isTrue();
      assertThat(compiler.lastStats().compiled()).isEqualTo(2);
      compiler.compile(texts(), List.of(tmp), Locale.GERMAN);
      assertThat(compiler.lastStats().full()).isTrue();
    }
  }
}
