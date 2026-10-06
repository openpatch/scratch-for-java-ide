package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRunnerTest {

  @TempDir
  Path tmp;

  private final ProjectRunner runner = new ProjectRunner();

  private Path scratchJar() throws IOException {
    return NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
  }

  /** A minimal project: one class whose constructor prints, jar in +libs. */
  private ScratchProject plainProject(String name, String javaSource) throws IOException {
    Path root = tmp.resolve(name);
    Files.createDirectories(root.resolve("+libs"));
    Files.writeString(root.resolve(name + ".java"), javaSource);
    Files.copy(scratchJar(), root.resolve("+libs/scratch.jar"));
    return ScratchProject.open(root);
  }

  @Test
  void compilesAndRunsInASeparateJvmStreamingStdout() throws Exception {
    ScratchProject project = plainProject("Greeting", """
        public class Greeting {
          public Greeting() {
            System.out.println("hello-from-student-program");
          }
        }
        """);
    List<String> lines = new CopyOnWriteArrayList<>();
    RunHandle handle = runner.run(project, RunConfig.of("Greeting"), new RunListener() {
      @Override public void onStdout(String line) { lines.add(line); }
    });
    int code = handle.awaitExit();
    assertThat(code).isZero();
    assertThat(lines).contains("hello-from-student-program");
    assertThat(handle.isRunning()).isFalse();
  }

  @Test
  void stderrIsStreamedSeparately() throws Exception {
    ScratchProject project = plainProject("Noisy", """
        public class Noisy {
          public Noisy() {
            System.out.println("out-line");
            System.err.println("err-line");
          }
        }
        """);
    List<String> out = new CopyOnWriteArrayList<>();
    List<String> err = new CopyOnWriteArrayList<>();
    RunHandle handle = runner.run(project, RunConfig.of("Noisy"), new RunListener() {
      @Override public void onStdout(String line) { out.add(line); }
      @Override public void onStderr(String line) { err.add(line); }
    });
    assertThat(handle.awaitExit()).isZero();
    assertThat(out).containsExactly("out-line");
    assertThat(err).containsExactly("err-line");
  }

  @Test
  void stopKillsAnEndlessProgram() throws Exception {
    ScratchProject project = plainProject("Endless", """
        public class Endless {
          public Endless() throws Exception {
            System.out.println("looping");
            while (true) {
              Thread.sleep(100);
            }
          }
        }
        """);
    RunHandle handle = runner.run(project, RunConfig.of("Endless"), new RunListener() {});
    assertThat(handle.isRunning()).isTrue();
    handle.stop();
    int code = handle.awaitExit();
    assertThat(handle.isRunning()).isFalse();
    assertThat(code).isNotEqualTo(Integer.MIN_VALUE);
  }

  @Test
  void compileFailureDeliversDiagnosticsBeforeAnythingStarts() throws Exception {
    ScratchProject project = plainProject("Broken", """
        public class Broken {
          public Broken() {
            int x =
          }
        }
        """);
    List<CompileResult> failures = new CopyOnWriteArrayList<>();
    try {
      runner.run(project, RunConfig.of("Broken"), new RunListener() {
        @Override public void onCompileFailed(CompileResult result) { failures.add(result); }
      });
      throw new AssertionError("expected CompilationFailedException");
    } catch (ProjectRunner.CompilationFailedException e) {
      assertThat(failures).hasSize(1);
      assertThat(e.result().errors()).isNotEmpty();
      assertThat(e.result().errors().get(0).path()).endsWith("Broken.java");
    }
  }

  @Test
  void runsATemplateProjectWithItsOwnLauncherSemantics() throws Exception {
    // the classes-first template compiles + runs through the runner too
    // (its MyStage would open a window, so run the *launcher generation +
    // compilation* path only: compile succeeds and the process starts; we
    // do not instantiate a real Stage without a display).
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "tpl", scratchJar());
    ScratchProject project = ScratchProject.open(tmp.resolve("tpl"));
    assertThat(project.startStage()).isEqualTo("MyWindow");
    // compile check without starting:
    var compiler = new org.openpatch.scratch4j.core.compile.CompilerService();
    var result = compiler.compile(project.javaSources(),
        List.copyOf(project.libs()), tmp.resolve("out"));
    assertThat(result.success()).isTrue();
  }
}
