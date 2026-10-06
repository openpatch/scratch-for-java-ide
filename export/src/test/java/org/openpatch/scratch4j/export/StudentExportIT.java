package org.openpatch.scratch4j.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.LibraryJarSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime check: the exported app folder — jlink'd runtime,
 * compiled classes, launcher — runs the student's program in its own bundled
 * JVM and exits cleanly via the smoke switch. Needs the current JDK's jmods
 * and a display ({@code xvfb-run -a}); enable with
 * {@code -Dscratch4j.exporttest=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.exporttest", matches = "true")
class StudentExportIT {

  @TempDir
  Path tmp;

  @Test
  void exportedLinuxAppRunsInItsOwnRuntime() throws Exception {
    // Temurin 25 JDKs ship no jmods (JEP 493, verified against the
    // downloaded 25.0.4.1 archive) - the export then bundles the runtime
    // instead of jlinking. currentJmods() returns null here, which selects
    // that fallback path in StudentExport.
    Path cache = Path.of(System.getProperty("user.dir"), "target", "bundled");
    Path jmods = RuntimeBuilder.currentJmods();

    Path allJar = LibraryJarSource.allJar(cache);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "game", allJar);
    ScratchProject project = ScratchProject.open(tmp.resolve("game"));

    StudentExport.Result result = new StudentExport()
        .export(project, StudentExport.Os.LINUX, jmods, tmp.resolve("exported"));

    Path appDir = result.appDir();
    assertThat(appDir.resolve("runtime/bin/java")).isRegularFile();
    assertThat(appDir.resolve("run.sh")).isRegularFile();
    assertThat(appDir.resolve("app/classes/MyStage.class")).isRegularFile();
    assertThat(result.archive()).isRegularFile();
    assertThat(Files.size(result.archive())).isGreaterThan(1_000_000);

    // run the exported launcher with the smoke switch, offscreen
    ProcessBuilder builder = new ProcessBuilder(appDir.resolve("run.sh").toString());
    builder.environment().put("JDK_JAVA_OPTIONS", "-Dscratch4j.ide.exitAfter=4");
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    assertThat(finished).as("exported app exits via exitAfter; output:\n" + output)
        .isTrue();
    assertThat(process.exitValue()).isZero();
  }
}
