package org.openpatch.scratch4j.core.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerServiceTest {

  @TempDir
  Path tmp;

  private final CompilerService compiler = new CompilerService();

  @Test
  void compilesValidSourcesToClassFiles() throws IOException {
    Path src = tmp.resolve("Hello.java");
    Files.writeString(src, """
        public class Hello {
          public static void main(String[] args) {
            System.out.println("hello");
          }
        }
        """);
    CompileResult result = compiler.compile(List.of(src), List.of(), tmp.resolve("out"));
    assertThat(result.success()).isTrue();
    assertThat(result.diagnostics()).isEmpty();
    assertThat(tmp.resolve("out/Hello.class")).isRegularFile();
  }

  @Test
  void reportsDiagnosticsWithPositionAndCode() throws IOException {
    Path src = tmp.resolve("Broken.java");
    Files.writeString(src, """
        public class Broken {
          public static void main(String[] args) {
            int x =
          }
        }
        """);
    CompileResult result = compiler.compile(List.of(src), List.of(), tmp.resolve("out"));
    assertThat(result.success()).isFalse();
    assertThat(result.errors()).isNotEmpty();
    CompilerDiagnostic error = result.errors().get(0);
    assertThat(error.path()).endsWith("Broken.java");
    assertThat(error.line()).isPositive();
    assertThat(error.column()).isPositive();
    assertThat(error.code()).startsWith("compiler.err");
    assertThat(error.message()).isNotBlank();
  }

  @Test
  void emptySourceListIsANoOpSuccess() throws IOException {
    assertThat(compiler.compile(List.of(), List.of(), tmp.resolve("out")).success()).isTrue();
  }
}
