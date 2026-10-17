package org.openpatch.scratch4j.runner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.openpatch.scratch4j.core.lint.RuntimeErrors;

/** Versioned line protocol from the JUnit listener, independent of JUnit's text renderer. */
public record CheckResult(String id, String name, String status, String message, String trace) {
  public static final String PREFIX = "SCRATCH4J_CHECK_V1\t";
  public boolean passed() { return status.equals("PASSED"); }
  public boolean failed() { return status.equals("FAILED"); }
  public List<RuntimeErrors.Frame> frames() {
    return trace.lines().map(RuntimeErrors::parseFrame).flatMap(Optional::stream).toList();
  }
  public String feedback(boolean de) {
    var m = java.util.regex.Pattern.compile("(?:.*? ==> )?expected: <(.*?)> but was: <(.*?)>",
        java.util.regex.Pattern.DOTALL).matcher(message);
    if (m.matches()) return (de ? "Erwartet: " : "Expected ") + m.group(1)
        + (de ? ", erhalten: " : ", got ") + m.group(2);
    return message.isBlank() ? (de ? "Prüfe die markierte Zeile." : "Check the highlighted line.") : message;
  }
  public static Optional<CheckResult> parse(String line) {
    if (!line.startsWith(PREFIX) || line.length() > 1_000_000) return Optional.empty();
    String[] parts = line.substring(PREFIX.length()).split("\t", -1);
    if (parts.length != 5 || !List.of("PASSED", "FAILED", "SKIPPED").contains(parts[2])) return Optional.empty();
    try {
      return Optional.of(new CheckResult(decode(parts[0]), decode(parts[1]), parts[2],
          decode(parts[3]), decode(parts[4])));
    } catch (IllegalArgumentException e) { return Optional.empty(); }
  }
  static String encode(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
  private static String decode(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
}
