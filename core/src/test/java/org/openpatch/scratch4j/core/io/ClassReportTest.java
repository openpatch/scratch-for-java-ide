package org.openpatch.scratch4j.core.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ClassReportTest {
  @TempDir Path tmp;
  @Test void derivesMetricsWithinSessionsAndDeduplicatesRepeatedSubmissions() throws Exception {
    String session = UUID.randomUUID().toString(), participant = UUID.randomUUID().toString();
    var json = JsonMapper.builder().build();
    var lines = new StringBuilder();
    class Events {
      void add(int seconds, String type, Map<String, Object> data) {
        var event = new LinkedHashMap<String, Object>(Map.of("v", 1, "t", Instant.parse("2026-10-17T08:00:00Z").plusSeconds(seconds).toString(),
            "session", session, "participant", participant, "type", type));
        event.putAll(data); lines.append(json.writeValueAsString(event)).append('\n');
      }
    }
    var e = new Events();
    e.add(0, "lesson.step", Map.of("step", "first-steps/walk", "state", "started"));
    e.add(1, "run", Map.of("success", false));
    e.add(2, "errors.changed", Map.of("errors", java.util.List.of(Map.of("case", "expected"))));
    e.add(3, "hint", Map.of("level", 2));
    e.add(4, "nudge", Map.of());
    e.add(5, "run", Map.of("success", true));
    e.add(6, "edit", Map.of("count", 12, "kind", "other"));
    e.add(7, "block.insert", Map.of("block", EventLog.id("move")));
    e.add(10, "lesson.step", Map.of("step", "first-steps/walk", "state", "completed"));
    e.add(11, "check", Map.of("name", "The score starts at five", "passed", false));
    Files.writeString(tmp.resolve("one.jsonl"), lines + "{partial\n");
    Files.writeString(tmp.resolve("same.jsonl"), lines);
    String report = ClassReport.build(tmp, false);
    assertThat(report).contains("Observed runs: 2", "0.500", "4.0 s (n=1)", "| first-steps/walk | 10.0 s | 1 |", "| expected | 1 |", "Nudges: 1", "1/1", "Keyboard edits (actions): 12; palette blocks inserted: 1", "Invalid entries: 1", "| The score starts at five | 1 |");
    // A completion without a start in its session cannot borrow a start elsewhere.
    Files.writeString(tmp.resolve("other.jsonl"), lines.toString().replace(session, UUID.randomUUID().toString())
        .lines().filter(l -> l.contains("completed")).findFirst().orElseThrow());
    assertThat(ClassReport.build(tmp, true)).contains("Klassenbericht", "10.0 s | 1");
  }
}
