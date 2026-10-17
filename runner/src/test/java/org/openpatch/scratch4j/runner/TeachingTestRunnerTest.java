package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.openpatch.scratch4j.core.project.ScratchProject;
import static org.assertj.core.api.Assertions.assertThat;

class TeachingTestRunnerTest {
  @TempDir Path root;

  @Test void commandLineKeepsTheSummaryUsedByBrowserTransferChecks() throws Exception {
    Files.writeString(root.resolve("ScoreTest.java"), """
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class ScoreTest {
          @Test void startsAtZero() { assertEquals(0, 0); }
        }
        """);
    Process process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), TeachingTestRunner.class.getName(), root.toString())
        .redirectErrorStream(true).start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(process.exitValue()).as(output).isZero();
      assertThat(output).contains("PASSED: startsAtZero()", "1 tests successful");
    } finally {
      process.destroyForcibly();
    }
  }

  @Test void browserTestClassesRunUnchangedAndFailOnWrongBehavior() throws Exception {
    Path source = root.resolve("ScoreTest.java");
    String original = "@Test\nclass ScoreTest {\n @Test void startsAtZero() { assertEquals(0, new Score().points); }\n}\n";
    Files.writeString(source, original);
    Files.writeString(root.resolve("Score.java"), "class Score { int points = 0; }");
    var project = ScratchProject.open(root);
    var runner = new TeachingTestRunner();
    assertThat(runner.run(project, new RunListener() {}).exitFuture().get(20, TimeUnit.SECONDS)).isZero();
    assertThat(Files.readString(source)).isEqualTo(original);
    Files.writeString(root.resolve("Score.java"), "class Score { int points = 1; }");
    assertThat(runner.run(project, new RunListener() {}).exitFuture().get(20, TimeUnit.SECONDS)).isNotZero();
  }
}
