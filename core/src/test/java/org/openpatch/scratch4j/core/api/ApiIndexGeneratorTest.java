package org.openpatch.scratch4j.core.api;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.project.NewProject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed {@code api-index.json} must be exactly what the generator
 * produces from the pinned library's sources jars (the core and every extension).
 * When this fails after a library version bump, re-run:
 *
 * <pre>
 * java -cp core/target/classes org.openpatch.scratch4j.core.api.ApiIndexGenerator \
 *   &lt;scratch-sources.jar&gt; &lt;scratch-tiled-sources.jar&gt; ... core/src/main/resources/api-index.json
 * </pre>
 */
class ApiIndexGeneratorTest {

  @Test
  void committedIndexMatchesGeneratedIndex() throws IOException {
    List<Path> sourcesJars = new ArrayList<>();
    for (Class<?> anchor : List.of(
        org.openpatch.scratch.internal.BuiltinAssets.class,
        org.openpatch.scratch.tiled.TiledMap.class,
        org.openpatch.scratch.save.SaveData.class,
        org.openpatch.scratch.recorder.GifRecorder.class,
        org.openpatch.scratch.drawing.Drawing.class,
        org.openpatch.scratch.gamepad.Gamepad.class)) {
      Path mainJar = NewProject.classpathJar(anchor);
      Path sourcesJar = Path.of(mainJar.toString().replaceFirst("\\.jar$", "-sources.jar"));
      assertThat(sourcesJar)
          .as("the sources jar next to " + mainJar + " must be on the test classpath (test dependency)")
          .isRegularFile()
          .isNotEmptyFile();
      sourcesJars.add(sourcesJar);
    }

    String generated = ApiIndexGenerator.toJson(ApiIndexGenerator.generate(sourcesJars));
    String committed;
    try (InputStream in = ApiIndexGenerator.class.getResourceAsStream("/api-index.json")) {
      assertThat(in).as("committed api-index.json").isNotNull();
      committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    // Git may check out this text resource with CRLF on Windows.
    assertThat(generated).isEqualTo(committed.replace("\r\n", "\n"));
  }
}
