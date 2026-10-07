package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JavaImportsTest {

  @TempDir
  Path tmp;

  @Test
  void addsTheImportAfterTheLastOne() {
    String source = """
        import org.openpatch.scratch.Sprite;
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {}
        """;
    assertThat(JavaImports.ensure(source, "org.openpatch.scratch.Window")).isEqualTo("""
        import org.openpatch.scratch.Sprite;
        import org.openpatch.scratch.Stage;
        import org.openpatch.scratch.Window;

        public class MyStage extends Stage {}
        """);
  }

  @Test
  void keepsWhatIsAlreadyThere() {
    String wildcard = "import org.openpatch.scratch.*;\n\nclass A {}\n";
    assertThat(JavaImports.ensure(wildcard, "org.openpatch.scratch.Window")).isEqualTo(wildcard);
    String exact = "import org.openpatch.scratch.Window;\nclass A {}\n";
    assertThat(JavaImports.ensure(exact, "org.openpatch.scratch.Window")).isEqualTo(exact);
    // another Window is imported: adding ours would not compile
    String other = "import java.awt.Window;\nclass A {}\n";
    assertThat(JavaImports.ensure(other, "org.openpatch.scratch.Window")).isEqualTo(other);
    assertThat(JavaImports.ensure("class A {}\n", "java.lang.Math")).isEqualTo("class A {}\n");
  }

  @Test
  void withoutImportsItGoesOnTopOrBelowThePackage() {
    assertThat(JavaImports.ensure("class A {}\n", "org.openpatch.scratch.KeyCode"))
        .isEqualTo("import org.openpatch.scratch.KeyCode;\n\nclass A {}\n");
    assertThat(JavaImports.ensure("package game;\n\nclass A {}\n", "org.openpatch.scratch.KeyCode"))
        .isEqualTo("package game;\n\nimport org.openpatch.scratch.KeyCode;\n\nclass A {}\n");
  }

  @Test
  void snippetsNeedTheLibraryClassesTheyName() throws IOException {
    // the all-in-one jar also has JOGL's and Kotlin's classes of the same names
    Path jar = jar("com/jogamp/newt/Window.class", "org/openpatch/scratch/Window.class",
        "kotlin/random/Random.class", "org/openpatch/scratch/Random.class",
        "org/openpatch/scratch/KeyCode.class", "org/openpatch/scratch/internal/Applet.class",
        "org/openpatch/scratch/Player.class");
    var library = new JavaImports.Library(List.of(jar));
    assertThat(library.qualified("Window")).isEqualTo("org.openpatch.scratch.Window");
    assertThat(library.qualified("Random")).isEqualTo("org.openpatch.scratch.Random");
    assertThat(JavaImports.neededBy("""
        public void whenKeyPressed(KeyCode keyCode) {
          Window.getInstance().setStage(new Player());
          this.say("Window");
        }
        """, library, List.of("Player"))).containsExactly(
        "org.openpatch.scratch.KeyCode", "org.openpatch.scratch.Window");
  }

  private Path jar(String... entries) throws IOException {
    Path jar = tmp.resolve("lib.jar");
    try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
      for (String entry : entries) {
        out.putNextEntry(new ZipEntry(entry));
        out.closeEntry();
      }
    }
    return jar;
  }
}
