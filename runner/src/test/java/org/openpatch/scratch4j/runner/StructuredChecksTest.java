package org.openpatch.scratch4j.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import static org.assertj.core.api.Assertions.assertThat;

class StructuredChecksTest {
  @TempDir Path tmp;
  @Test void listenerPreservesTeacherNamesAssertionValuesAndStudentFrames() throws Exception {
    Files.writeString(tmp.resolve("Score.java"), "class Score { int value() { return 4; } }");
    Files.writeString(tmp.resolve("ScoreTest.java"), """
        import org.junit.jupiter.api.*;
        import static org.junit.jupiter.api.Assertions.*;
        class ScoreTest {
          @Test @DisplayName("The score starts at five") void starts() { assertEquals(5, new Score().value()); }
          @Test @DisplayName("A passing check") void passes() { assertTrue(true); }
          @Test @Disabled("Later") void later() {}
        }
        """);
    List<CheckResult> results = new CopyOnWriteArrayList<>();
    List<String> output = new CopyOnWriteArrayList<>();
    var run = new TeachingTestRunner().run(ScratchProject.open(tmp), new RunListener() {
      public void onCheck(CheckResult result) { results.add(result); }
      public void onStdout(String line) { output.add(line); }
    });
    assertThat(run.exitFuture().get(20, TimeUnit.SECONDS)).isNotZero();
    assertThat(results).hasSize(3);
    CheckResult failure = results.stream().filter(CheckResult::failed).findFirst().orElseThrow();
    assertThat(failure.name()).isEqualTo("The score starts at five");
    assertThat(failure.feedback(false)).isEqualTo("Expected 5, got 4");
    assertThat(failure.feedback(true)).isEqualTo("Erwartet: 5, erhalten: 4");
    assertThat(failure.frames()).anyMatch(f -> f.file().equals("ScoreTest.java") && f.line() == 4);
    assertThat(output).anyMatch(s -> s.contains("expected: <5> but was: <4>"));
    assertThat(CheckResult.parse(CheckResult.PREFIX + "bad")).isEmpty();
  }
  @Test void setupFailureCannotLookLikeZeroSuccessfulTests() throws Exception {
    Files.writeString(tmp.resolve("BrokenTest.java"), """
        import org.junit.jupiter.api.*;
        class BrokenTest {
          @BeforeAll static void setup() { throw new IllegalStateException("Broken setup"); }
          @Test void neverRuns() {}
        }
        """);
    List<CheckResult> results = new CopyOnWriteArrayList<>();
    var run = new TeachingTestRunner().run(ScratchProject.open(tmp), new RunListener() {
      public void onCheck(CheckResult result) { results.add(result); }
    });
    assertThat(run.exitFuture().get(20, TimeUnit.SECONDS)).isNotZero();
    assertThat(results).anyMatch(CheckResult::failed);
  }
}
