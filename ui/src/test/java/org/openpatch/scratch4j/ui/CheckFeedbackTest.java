package org.openpatch.scratch4j.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.CheckResult;
import static org.assertj.core.api.Assertions.assertThat;

class CheckFeedbackTest {
  @TempDir Path root;
  @Test void failureJumpsToAStudentFrameWhenOneExistsAndDoesNotInventOneForAnAssertion() throws Exception {
    Files.writeString(root.resolve("Score.java"), "class Score { int points; }");
    Files.writeString(root.resolve("ScoreTest.java"), "class ScoreTest { @Test void check() {} }");
    var project = ScratchProject.open(root);
    var crash = new CheckResult("id", "Moving adds points", "FAILED", "boom", """
        java.lang.IllegalStateException: boom
        \tat Score.move(Score.java:12)
        \tat ScoreTest.check(ScoreTest.java:3)
        """);
    var card = CheckFeedback.describe(project, crash, false);
    assertThat(card.file()).isEqualTo(root.resolve("Score.java"));
    assertThat(card.line()).isEqualTo(12);
    assertThat(card.message()).contains("Moving adds points");
    var assertion = new CheckResult("id", "The score starts at five", "FAILED", "expected: <5> but was: <4>", "\tat ScoreTest.check(ScoreTest.java:3)");
    card = CheckFeedback.describe(project, assertion, false);
    assertThat(card.explanation()).isEqualTo("Expected 5, got 4");
    assertThat(card.file()).isNull();
    assertThat(card.trace()).contains("ScoreTest.check");
  }
}
