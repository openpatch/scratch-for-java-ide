package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactSourceTest {

  @TempDir
  Path tmp;

  private static final String SNIPPET = """
      int lives = 3;

      void main() {
        new MyStage();
      }

      // the stage of the example
      class MyStage extends Stage {
        MyStage() {
          this.add(new Cat());
        }
      }

      class Cat extends Sprite {
        Cat() {
          this.addCostume("cat", "bunny1_stand");
        }
      }
      """;

  @Test
  void snippetBecomesProjectClassesThatCompileAndRun() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    var imported = CompactSource.importSnippet(SNIPPET, tmp);
    assertThat(imported.startClass()).isEqualTo("Main");
    assertThat(imported.files()).extracting(p -> p.getFileName().toString())
        .containsExactlyInAnyOrder("MyStage.java", "Cat.java", "Main.java");
    assertThat(tmp.resolve("MyStage.java")).content().startsWith("import org.openpatch.scratch.*;")
        .contains("// the stage of the example");
    String main = Files.readString(tmp.resolve("Main.java"));
    assertThat(main).contains("int lives = 3;").contains("new Main().main();");
    var result = new CompilerService().compile(List.of(tmp.resolve("MyStage.java"),
        tmp.resolve("Cat.java"), tmp.resolve("Main.java")), List.of(jar), tmp.resolve("out"));
    assertThat(result.errors()).isEmpty();
    assertThatThrownBy(() -> CompactSource.importSnippet(SNIPPET, tmp))
        .hasMessageContaining("already exist");
  }

  @Test
  void projectBecomesOneCompactFileThatCompiles() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "p", jar);
    ScratchProject project = ScratchProject.open(tmp.resolve("p"));
    String compact = CompactSource.export(project, "MyStage");
    assertThat(compact).startsWith("void main() {\n  new MyStage();\n}")
        .doesNotContain("import ").doesNotContain("public class")
        .doesNotContain("static void main");
    Path file = tmp.resolve("Example.java");
    Files.writeString(file, CompactSource.IMPORTS + compact);
    var result = new CompilerService().compile(List.of(file), List.of(jar), tmp.resolve("o2"));
    assertThat(result.errors()).isEmpty();
  }
}
