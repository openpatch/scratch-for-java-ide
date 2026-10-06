package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewClassTest {

  @TempDir
  Path tmp;

  @Test
  void generatesAllKindsWithRegions() throws IOException {
    for (NewClass.Kind kind : NewClass.Kind.values()) {
      Path file = NewClass.create(kind, tmp, "My" + kind.name().toLowerCase());
      String source = Files.readString(file);
      assertThat(source).contains("// scratch4j:begin setup");
      assertThat(source).contains("// scratch4j:end setup");
      assertThat(source).contains("class My" + kind.name().toLowerCase());
    }
  }

  @Test
  void generatedClassesCompileAgainstTheScratchJar() throws IOException {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    var compiler = new CompilerService();
    for (NewClass.Kind kind : NewClass.Kind.values()) {
      String name = "Gen" + kind.name();
      NewClass.create(kind, tmp, name);
    }
    Path out = Files.createDirectories(tmp.resolve("out"));
    List<Path> sources;
    try (var stream = Files.list(tmp)) {
      sources = stream.filter(p -> p.toString().endsWith(".java")).toList();
    }
    var result = compiler.compile(sources, List.of(jar), out);
    assertThat(result.success())
        .as("diagnostics: %s", result.diagnostics())
        .isTrue();
  }

  @Test
  void refusesInvalidNamesAndOverwrites() throws IOException {
    assertThatThrownBy(() -> NewClass.create(NewClass.Kind.SPRITE, tmp, "my class"))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> NewClass.create(NewClass.Kind.SPRITE, tmp, "2Fast"))
        .isInstanceOf(IOException.class);
    NewClass.create(NewClass.Kind.SPRITE, tmp, "Cat");
    assertThatThrownBy(() -> NewClass.create(NewClass.Kind.SPRITE, tmp, "Cat"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("already exists");
  }
}
