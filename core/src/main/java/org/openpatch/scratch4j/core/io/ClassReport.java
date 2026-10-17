package org.openpatch.scratch4j.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Derived metrics over explicit submissions, paired only within participant/session. */
public final class ClassReport {
  private ClassReport() {}
  private record Event(Instant time, String participant, String session, String type, JsonNode data) {}
  public static String build(Path folder, boolean de) throws IOException {
    var json = JsonMapper.builder().build();
    List<Event> events = new ArrayList<>();
    var seen = new HashSet<String>();
    int ignored = 0;
    try (var files = Files.walk(folder, 3)) {
      for (Path file : files.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          && p.getFileName().toString().endsWith(".jsonl")).limit(1001).toList()) {
        if (Files.size(file) > 4 * EventLog.MAX_BYTES) { ignored++; continue; }
        try (var lines = Files.newBufferedReader(file)) {
          String line;
          while ((line = lines.readLine()) != null) {
            if (line.length() > 32_768) { ignored++; continue; }
            try {
              var node = json.readTree(line);
              if (node.path("v").asInt() != 1) { ignored++; continue; }
              String participant = node.path("participant").asString();
              String session = node.path("session").asString();
              if (!participant.matches("[a-f0-9-]{36}|[a-f0-9]{64}") || !session.matches("[a-f0-9-]{36}")) { ignored++; continue; }
              if (!seen.add(EventLog.id(json.writeValueAsString(node)))) continue;
              events.add(new Event(Instant.parse(node.path("t").asString()), participant, session,
                  node.path("type").asString(), node));
            } catch (RuntimeException e) { ignored++; }
          }
        }
      }
    }
    events.sort(Comparator.comparing(Event::time));
    Map<String, List<Event>> sessions = new HashMap<>();
    for (Event event : events) sessions.computeIfAbsent(event.participant() + "/" + event.session(), k -> new ArrayList<>()).add(event);
    Map<String, Integer> errors = new TreeMap<>(), checks = new TreeMap<>(), help = new TreeMap<>();
    Map<String, List<Long>> stepTimes = new TreeMap<>();
    List<Long> fixes = new ArrayList<>();
    long typedEdits = 0, blocks = 0;
    int runs = 0, failed = 0, nudges = 0, nudgeRuns = 0, nudgeSuccess = 0, helpRuns = 0, helpSuccess = 0;
    for (var session : sessions.values()) {
      Instant failedSince = null;
      boolean afterNudge = false, afterHelp = false;
      Map<String, Instant> steps = new HashMap<>();
      Set<String> previousErrors = new HashSet<>();
      for (Event event : session) {
        JsonNode data = event.data();
        switch (event.type()) {
          case "errors.changed" -> {
            Set<String> currentErrors = new HashSet<>();
            for (JsonNode error : data.path("errors")) {
              String key = safe(error.path("case").asString());
              if (key.isBlank()) key = safe(error.path("code").asString());
              String identity = key + "/" + error.path("code").asString() + "/"
                  + error.path("line").asLong() + "/" + error.path("kind").asString();
              if (currentErrors.add(identity) && !previousErrors.contains(identity))
                errors.merge(key.isBlank() ? "raw" : key, 1, Integer::sum);
            }
            if (!currentErrors.isEmpty() && failedSince == null) failedSince = event.time();
            previousErrors = currentErrors;
          }
          case "crash" -> {
            errors.merge("runtime." + safe(data.path("case").asString()), 1, Integer::sum);
            if (failedSince == null) failedSince = event.time();
          }
          case "run" -> {
            runs++;
            boolean passed = data.path("success").asBoolean();
            if (!passed) { failed++; if (failedSince == null) failedSince = event.time(); }
            else if (failedSince != null) { fixes.add(Math.max(0, Duration.between(failedSince, event.time()).toMillis())); failedSince = null; }
            if (afterNudge) { nudgeRuns++; if (passed) nudgeSuccess++; afterNudge = false; }
            if (afterHelp) { helpRuns++; if (passed) helpSuccess++; afterHelp = false; }
          }
          case "check" -> { if (!data.path("passed").asBoolean()) checks.merge(label(data.path("name").asString()), 1, Integer::sum); }
          case "edit" -> typedEdits += Math.max(0, data.path("count").asLong());
          case "block.insert" -> blocks++;
          case "hint" -> { help.merge("hint." + data.path("level").asInt(), 1, Integer::sum); afterHelp = true; }
          case "help" -> { help.merge(safe(data.path("action").asString()), 1, Integer::sum); afterHelp = true; }
          case "nudge" -> { nudges++; afterNudge = true; }
          case "lesson.step" -> {
            String step = shortId(data.path("step").asString());
            if (data.path("state").asString().equals("started")) steps.put(step, event.time());
            else if (data.path("state").asString().equals("completed")) {
              Instant start = steps.remove(step);
              if (start != null) stepTimes.computeIfAbsent(step, k -> new ArrayList<>())
                  .add(Math.max(0, Duration.between(start, event.time()).toMillis()));
            }
          }
          default -> { }
        }
      }
    }
    StringBuilder report = new StringBuilder(de ? "# Klassenbericht\n\n" : "# Class report\n\n");
    long participants = events.stream().map(Event::participant).distinct().count();
    report.append(de ? "Pseudonyme: " : "Participants: ").append(participants)
        .append(de ? "; Sitzungen: " : "; sessions: ").append(sessions.size()).append("\n\n")
        .append(de ? "Beobachtete Runs: " : "Observed runs: ").append(runs)
        .append(de ? "; Fehlerquotient (fehlgeschlagene Runs / Runs): " : "; error quotient (failed runs / runs): ")
        .append(runs == 0 ? "—" : String.format(java.util.Locale.ROOT, "%.3f", (double) failed / runs)).append("\n\n")
        .append(de ? "Mittlere Zeit bis zum nächsten erfolgreichen Run: " : "Mean time to the next successful run: ")
        .append(average(fixes)).append(" (n=").append(fixes.size()).append(")\n\n");
    table(report, de ? "Fehlerhäufigkeit (neue Vorkommen, Abstürze)" : "Error frequency (new occurrences, crashes)", errors);
    table(report, de ? "Fehlgeschlagene Verhaltensprüfungen" : "Failing behavior checks", checks);
    report.append(de ? "## Zeit pro Lernschritt\n\n" : "## Time per lesson step\n\n")
        .append("| ID | ").append(de ? "Mittelwert | Abschlüsse |\n" : "Mean | Completions |\n").append("|---|---:|---:|\n");
    stepTimes.forEach((step, times) -> report.append("| ").append(step).append(" | ").append(average(times)).append(" | ").append(times.size()).append(" |\n"));
    report.append('\n');
    table(report, de ? "Hilfenutzung" : "Help patterns", help);
    report.append(de ? "Tastaturbearbeitungen (Aktionen): " : "Keyboard edits (actions): ").append(typedEdits)
        .append(de ? "; eingefügte Blöcke: " : "; palette blocks inserted: ").append(blocks).append("\n\n");
    report.append(de ? "Hinweise bei Stillstand: " : "Nudges: ").append(nudges).append("; ")
        .append(de ? "nächster beobachteter Run erfolgreich: " : "next observed run successful: ").append(nudgeSuccess).append('/').append(nudgeRuns)
        .append("\n\n").append(de ? "Nach Hilfe erfolgreich: " : "After help, next run successful: ").append(helpSuccess).append('/').append(helpRuns)
        .append("\n\n").append(de ? "Nur erhaltene Ereignisse. Zeiten gelten innerhalb einer Sitzung; Zusammenhänge belegen keine Wirkung. Ungültige Einträge: "
            : "Retained events only. Times are within one session; associations do not establish causation. Invalid entries: ")
        .append(ignored).append('\n');
    return report.toString();
  }
  private static String label(String value) {
    String text = value.replaceAll("[\\r\\n\\t]", " ").replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
    return text.substring(0, Math.min(300, text.length()));
  }
  private static String safe(String key) { return key.matches("[A-Za-z0-9_.-]{0,100}") ? key : "unknown"; }
  private static String shortId(String id) {
    if (id.matches("[a-f0-9]{64}")) return id.substring(0, 12);
    return id.matches("[A-Za-z0-9_./:-]{1,160}") ? id : "unknown";
  }
  private static String average(List<Long> values) {
    return values.isEmpty() ? "—" : String.format(java.util.Locale.ROOT, "%.1f s", values.stream().mapToLong(Long::longValue).average().orElse(0) / 1000);
  }
  private static void table(StringBuilder out, String title, Map<String, Integer> counts) {
    out.append("## ").append(title).append("\n\n| ID | n |\n|---|---:|\n");
    counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .forEach(e -> out.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));
    out.append('\n');
  }
}
