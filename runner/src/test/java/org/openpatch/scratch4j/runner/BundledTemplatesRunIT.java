package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime check for the bundled templates: every finished tutorial project and
 * every demo is created from the wizard's template, compiled and run in its own
 * JVM, and closes cleanly via {@code -Dscratch4j.ide.exitAfter}. Needs a
 * display ({@code xvfb-run -a}) and {@code -Dscratch4j.gltest=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class BundledTemplatesRunIT {

  @TempDir
  Path tmp;

  static List<String> templates() {
    return BundledTemplates.list().stream().map(BundledTemplates.Template::id).toList();
  }

  @ParameterizedTest
  @MethodSource("templates")
  void templateRunsAndExitsViaSmokeSwitch(String id) throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    ScratchProject project = ScratchProject.open(
        BundledTemplates.create(id, tmp, id, allJar));
    List<String> err = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of(project.startStage()).withExitAfter(4),
        new RunListener() {
          @Override public void onStderr(String line) { err.add(line); }
        });
    int code = handle.exitFuture().get(60, TimeUnit.SECONDS);
    assertThat(code).as("%s exits cleanly; stderr: %s", id, err).isZero();
    assertThat(err).as("%s stderr", id)
        .noneMatch(line -> line.contains("Exception") || line.contains("Error:"));
  }
}
