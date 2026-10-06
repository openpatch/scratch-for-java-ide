package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A crash inside a running stage is explained at the student's line. */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class RuntimeErrorRunIT {

  @TempDir
  Path tmp;

  @Test
  void crashInRunPrintsATrace() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "crash", allJar);
    Path root = tmp.resolve("crash");
    Path stage = root.resolve("MyStage.java");
    Files.writeString(stage, Files.readString(stage).replace("public void run() {\n  }", """
        private Player enemy;
          public void run() {
            enemy.move(10);
          }"""));
    List<String> err = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(ScratchProject.open(root),
        RunConfig.of("MyStage").withExitAfter(3), new RunListener() {
          @Override public void onStderr(String line) { err.add(line); }
        });
    handle.exitFuture().get(30, TimeUnit.SECONDS);
    var collector = new org.openpatch.scratch4j.core.lint.RuntimeErrors.Collector();
    List<org.openpatch.scratch4j.core.lint.RuntimeErrors.Crash> crashes = new ArrayList<>();
    for (String line : err) {
      collector.feed(line).ifPresent(crashes::add);
    }
    collector.flush().ifPresent(crashes::add);
    assertThat(crashes).as("stderr: %s", err).isNotEmpty();
    var explained = org.openpatch.scratch4j.core.lint.RuntimeErrors.explain(crashes.get(0),
        file -> file.equals("MyStage.java") ? stage : null,
        org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.EN);
    assertThat(explained.title()).isEqualTo("enemy is null");
    assertThat(explained.file()).isEqualTo(stage);
    assertThat(Files.readAllLines(stage).get(explained.line() - 1)).contains("enemy.move(10)");
  }
}
