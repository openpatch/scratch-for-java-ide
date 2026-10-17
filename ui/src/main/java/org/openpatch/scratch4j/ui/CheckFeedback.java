package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openpatch.scratch4j.core.compile.TeachingTests;
import org.openpatch.scratch4j.core.lint.RuntimeErrors;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.CheckResult;

/** Builds cards off the FX thread; teacher test frames never masquerade as student lines. */
final class CheckFeedback {
  private CheckFeedback() {}
  static Problem describe(ScratchProject project, CheckResult result, boolean de) {
    Map<String, Path> sources = new HashMap<>();
    try {
      for (Path source : project.javaSources()) {
        if (!TeachingTests.hasTests(Files.readString(source))) sources.put(source.getFileName().toString(), source);
      }
    } catch (IOException ignored) { /* feedback still has the teacher's name and failure */ }
    var frame = RuntimeErrors.studentFrame(result.frames(), sources::get).orElse(null);
    Path file = frame == null ? null : sources.get(frame.file());
    return new Problem(file, frame == null ? 0 : frame.line(), 0,
        (result.passed() ? "✓ " : result.failed() ? "✗ " : "– ") + result.name(),
        result.passed() ? null : result.feedback(de), List.of(), result.failed(),
        null, null, false, null, result.trace().isBlank() ? null : result.trace(),
        "check." + result.status().toLowerCase(java.util.Locale.ROOT), null);
  }
}
