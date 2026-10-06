package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BundledTemplatesTest {

  @TempDir
  Path tmp;

  @Test
  void shipsEveryFinishedTutorialAndDemo() {
    List<BundledTemplates.Template> all = BundledTemplates.list();
    assertThat(all.stream().filter(BundledTemplates.Template::isTutorial).map(t -> t.id()))
        .containsExactly("getting-started-100", "make-it-walk-100", "catch-the-coins-100",
            "red-light-green-light-100", "guess-the-number-100", "bouncy-hedgehog-100",
            "dodge-the-rocks-100");
    assertThat(all.stream().filter(t -> !t.isTutorial())).hasSize(21);
    assertThat(all).allSatisfy(t -> assertThat(t.startClass()).isNotBlank());
  }

  @Test
  void everyTemplateCreatesACompilingProjectWithItsStartClass() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    for (BundledTemplates.Template template : BundledTemplates.list()) {
      Path root = BundledTemplates.create(template.id(), tmp, template.id(), jar);
      ScratchProject project = ScratchProject.open(root);
      assertThat(project.startStage()).as(template.id()).isEqualTo(template.startClass());
      assertThat(root.resolve("+libs").resolve(jar.getFileName())).isRegularFile();
      CompileResult result = new CompilerService().compile(project.javaSources(),
          List.of(jar), tmp.resolve("out-" + template.id()));
      assertThat(result.errors()).as(template.id()).isEmpty();
      for (Path source : project.javaSources()) {
        assertThat(Files.readString(source)).as(source.toString())
            .doesNotContain("demos/").doesNotStartWith("package ");
      }
    }
  }

  @Test
  void tutorialsKeepTheirBlueJAndVsCodeLayout() throws Exception {
    Path root = BundledTemplates.create("dodge-the-rocks-100", tmp, "dodge", null);
    assertThat(root.resolve("package.bluej")).isRegularFile();
    assertThat(root.resolve(".vscode/settings.json")).isRegularFile();
    // a Window subclass starts the program (it picks the title stage itself)
    assertThat(ScratchProject.open(root).startStage()).isEqualTo("DodgeWindow");
  }
}
