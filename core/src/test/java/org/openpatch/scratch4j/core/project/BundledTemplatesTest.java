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
    try (var in = BundledTemplates.class.getResourceAsStream("/catalogs/examples.json")) {
      var catalog = tools.jackson.databind.json.JsonMapper.builder().build().readTree(in);
      long demos = java.util.stream.StreamSupport.stream(catalog.path("examples").spliterator(), false)
          .filter(entry -> entry.path("kind").asText().equals("demo")).count();
      assertThat(all.stream().filter(t -> !t.isTutorial())).hasSize((int) demos);
    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    assertThat(all).allSatisfy(t -> assertThat(t.startClass()).isNotBlank());
  }

  @Test
  void everyTemplateCreatesACompilingProjectWithItsStartClass() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    for (BundledTemplates.Template template : BundledTemplates.list()) {
      boolean available;
      try (var archive = new java.util.zip.ZipFile(jar.toFile())) {
        available = template.requiredSheets() == null || template.requiredSheets().stream()
            .allMatch(sheet -> archive.getEntry("images/" + sheet + ".png") != null);
      }
      if (!available) {
        org.assertj.core.api.Assertions.assertThatIOException()
            .isThrownBy(() -> BundledTemplates.create(template.id(), tmp, template.id(), jar))
            .withMessageContaining("Update");
        assertThat(tmp.resolve(template.id())).doesNotExist();
      }
      Path root = BundledTemplates.create(template.id(), tmp, template.id(), available ? jar : null);
      if (!available) Files.copy(jar, root.resolve("+libs").resolve(jar.getFileName()));
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
