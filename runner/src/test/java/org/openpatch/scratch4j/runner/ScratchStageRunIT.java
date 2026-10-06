package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end OpenGL smoke test: a real Scratch for Java stage opens a window
 * in its own JVM and the launcher's {@code -Dscratch4j.ide.exitAfter=N} hook
 * closes it. Needs a display — run under {@code xvfb-run -a} or with a real
 * desktop, and enable with {@code -Dscratch4j.gltest=true}. CI runs it on
 * Linux under Xvfb.
 */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class ScratchStageRunIT {

  @TempDir
  Path tmp;

  @Test
  void stageOpensWindowAndExitsViaSmokeSwitch() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "glproj", allJar);
    ScratchProject project = ScratchProject.open(tmp.resolve("glproj"));
    List<String> err = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of(project.startStage()).withExitAfter(4),
        new RunListener() {
          @Override public void onStderr(String line) { err.add(line); }
        });
    int code = handle.exitFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(code)
        .as("window program exits cleanly via exitAfter; stderr: %s", err)
        .isZero();
  }
}
