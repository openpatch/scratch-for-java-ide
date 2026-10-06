package org.openpatch.scratch4j.core.api;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.project.NewProject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed {@code api-index.json} must be exactly what the generator
 * produces from the pinned library's sources jar. When this fails after a
 * library version bump, re-run:
 *
 * <pre>
 * java -cp core/target/classes org.openpatch.scratch4j.core.api.ApiIndexGenerator \
 *   &lt;path/to/scratch-sources.jar&gt; core/src/main/resources/api-index.json
 * </pre>
 */
class ApiIndexGeneratorTest {

  @Test
  void committedIndexMatchesGeneratedIndex() throws IOException {
    Path mainJar = NewProject.classpathJar(
        org.openpatch.scratch.internal.BuiltinAssets.class);
    Path sourcesJar = Path.of(mainJar.toString().replaceFirst("\\.jar$", "-sources.jar"));
    assertThat(sourcesJar)
        .as("the scratch sources jar must be on the test classpath (test dependency)")
        .isRegularFile();

    String generated = ApiIndexGenerator.toJson(ApiIndexGenerator.generate(sourcesJar));
    String committed;
    try (InputStream in = ApiIndexGenerator.class.getResourceAsStream("/api-index.json")) {
      assertThat(in).as("committed api-index.json").isNotNull();
      committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    // Git may check out this text resource with CRLF on Windows.
    assertThat(generated).isEqualTo(committed.replace("\r\n", "\n"));
    assertThat(Files.size(sourcesJar)).isPositive();
  }
}
