package org.openpatch.scratch4j.core.api;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.compile.CompilerService;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectCatalogTest {
  @TempDir Path root;

  @Test void olderProjectWithoutMetadataShowsOnlyItsActualOverloads() throws Exception {
    Path source = root.resolve("scratch-source/org/openpatch/scratch/Sprite.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package org.openpatch.scratch; public class Sprite { public void move(double steps) {} }");
    Path classes = root.resolve("classes");
    assertThat(new CompilerService().compile(List.of(source), List.of(), classes).success()).isTrue();
    Path project = root.resolve("game");
    Files.createDirectories(project.resolve("+libs"));
    try (var jar = new JarOutputStream(Files.newOutputStream(project.resolve("+libs/scratch-4.0.0-all.jar")))) {
      jar.putNextEntry(new JarEntry("org/openpatch/scratch/Sprite.class"));
      jar.write(Files.readAllBytes(classes.resolve("org/openpatch/scratch/Sprite.class")));
    }
    ApiIndex index = ApiIndex.load();
    index.useProject(ScratchProject.open(project));
    assertThat(index.libraryVersion()).isEqualTo("4.0.0");
    assertThat(index.byName("clone")).isEmpty();
    assertThat(index.byName("move")).hasSize(1).first().satisfies(
        method -> assertThat(method.params()).containsExactly("double steps"));
  }

  @Test void projectLocalCatalogWinsOverBundledHelpWithoutANetworkRequest() throws Exception {
    Path project = root.resolve("game");
    Files.createDirectories(project.resolve("+libs"));
    String metadata = """
        {"schemaVersion":1,"libraryVersion":"4.1.0","methods":[
          {"className":"Sprite","methodName":"legacyMethod","returnType":"void","params":[],
           "summary":"Local release help","description":"Local release help","scratchblock":null,
           "docsUrl":"https://scratch4j.openpatch.org/reference/Sprite/legacyMethod","paramDocs":[]}
        ]}
        """;
    try (var jar = new JarOutputStream(Files.newOutputStream(project.resolve("+libs/scratch-4.1.0-all.jar")))) {
      jar.putNextEntry(new JarEntry("catalogs/api.json"));
      jar.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    ApiIndex index = ApiIndex.load();
    index.useProject(ScratchProject.open(project));
    assertThat(index.libraryVersion()).isEqualTo("4.1.0");
    assertThat(index.first("legacyMethod")).isPresent();
    assertThat(index.first("clone")).isEmpty();
  }
}
