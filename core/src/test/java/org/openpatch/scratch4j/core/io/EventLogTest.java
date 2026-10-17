package org.openpatch.scratch4j.core.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class EventLogTest {
  @TempDir Path tmp;
  @Test void schemaExcludesMessagesNamesAndSourceAndOnlyLogsChangedErrorSets() throws Exception {
    Path root = tmp.resolve("project"), settings = tmp.resolve("settings");
    try (var log = EventLog.open(root, settings, "lesson", "en", "1.0")) {
      var errors = List.<Map<String, Object>>of(Map.of("case", "expected", "code", "compiler.err.expected", "line", 3, "kind", "sprite"));
      log.errorsChanged(errors); log.errorsChanged(errors);
      log.record("run", Map.of("success", false, "errorCount", 1, "attempt", 1));
      assertThatIllegalArgumentException().isThrownBy(() -> log.record("run", Map.of("source", "class StudentName {}")));
      assertThatIllegalArgumentException().isThrownBy(() -> log.errorsChanged(List.of(Map.of("case", "expected", "code", "raw student name", "line", 3, "kind", "sprite"))));
      assertThatIllegalArgumentException().isThrownBy(() -> log.record("view", Map.of("view", "CODE", "kind", "Student.java")));
      assertThat(log.contents().lines().filter(l -> l.contains("errors.changed")).count()).isOne();
      log.errorsChanged(List.of());
    }
    String text = Files.readString(root.resolve(EventLog.DIRECTORY).resolve("events.jsonl"));
    assertThat(text).contains("session.start", "session.end", "\"v\":1", "\"session\"")
        .doesNotContain("StudentName", "Student.java", "raw student name");
    assertThat(settings.resolve("research-id")).isRegularFile();
    assertThat(root.resolve(".scratch4j/project.json")).doesNotExist();
  }
  @Test void rotationAndHandInKeepTheIdOutOfProjectMetadataAndSharedCodesOutOfTheOriginal() throws Exception {
    Path settings = tmp.resolve("settings");
    try (var log = new EventLog(tmp.resolve("a"), settings, "lesson", "de", "1", 1024)) {
      for (int i = 1; i <= 100; i++) log.record("run", Map.of("success", true, "errorCount", 0, "attempt", i));
      assertThat(log.segments()).hasSize(4);
      for (Path file : log.segments()) assertThat(Files.size(file)).isLessThanOrEqualTo(1024);
      assertThatIOException().isThrownBy(() -> log.handIn(tmp.resolve("a/learning-log.jsonl"), ""));
      Path submission = tmp.resolve("submission.jsonl");
      log.handIn(submission, "group-42");
      String original = log.contents(), handed = Files.readString(submission);
      assertThat(handed).contains(EventLog.id("group-42")).doesNotContain(Files.readString(settings.resolve("research-id")), "group-42");
      assertThat(original).contains(Files.readString(settings.resolve("research-id"))).doesNotContain(EventLog.id("group-42"));
      var json = JsonMapper.builder().build();
      for (String line : handed.lines().toList()) assertThat(json.readTree(line).path("v").asInt()).isOne();
      log.delete();
      assertThat(log.segments()).isEmpty();
      assertThat(log.record("run", Map.of("success", true, "errorCount", 0, "attempt", 101))).isFalse();
    }
    try (var other = EventLog.open(tmp.resolve("b"), settings, "other lesson", "en", "1")) {
      assertThat(other.contents()).contains(Files.readString(settings.resolve("research-id")));
    }
  }
  @Test void aPartialTailDoesNotSwallowTheNextSessionAndLogsRemainAccessibleAfterOptOut() throws Exception {
    Path root = Files.createDirectories(tmp.resolve("project"));
    Path dir = Files.createDirectories(root.resolve(EventLog.DIRECTORY));
    Files.writeString(dir.resolve("events.jsonl"), "{partial");
    try (var log = EventLog.open(root, tmp.resolve("settings"), "lesson", "en", "1")) {
      assertThat(log.contents().lines().toList()).hasSize(2);
    }
    Path submission = tmp.resolve("after-optout.jsonl");
    EventLog.handIn(root, submission, "");
    assertThat(Files.readString(submission)).contains("session.start", "session.end").doesNotContain("partial");
    EventLog.delete(root);
    assertThat(EventLog.contents(root)).isEmpty();
  }
  @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
  @Test void projectControlledSymlinksCannotRedirectLogs() throws Exception {
    Path root = Files.createDirectories(tmp.resolve("p/.scratch4j")), outside = Files.createDirectories(tmp.resolve("outside"));
    Files.createSymbolicLink(root.resolve("log"), outside);
    assertThatIOException().isThrownBy(() -> EventLog.open(tmp.resolve("p"), tmp.resolve("settings"), "", "en", "1"));
    assertThat(outside.resolve("events.jsonl")).doesNotExist();
  }
}
