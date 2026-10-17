package org.openpatch.scratch4j.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in local learning events. Payloads have a closed schema; source and messages have no field. */
public final class EventLog implements AutoCloseable {
  public static final String DIRECTORY = ".scratch4j/log";
  public static final long MAX_BYTES = 2 * 1024 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
      Map.entry("session.start", Set.of("lesson", "language", "ide")),
      Map.entry("session.end", Set.of("lesson", "language", "ide")),
      Map.entry("run", Set.of("success", "errorCount", "attempt")),
      Map.entry("errors.changed", Set.of("errors")),
      Map.entry("crash", Set.of("exception", "case", "line", "kind")),
      Map.entry("check", Set.of("name", "passed")),
      Map.entry("hint", Set.of("level", "case", "step")),
      Map.entry("nudge", Set.of("case", "step", "rule")),
      Map.entry("help", Set.of("action", "case")),
      Map.entry("lesson.step", Set.of("step", "state")),
      Map.entry("view", Set.of("view", "kind")),
      Map.entry("block.insert", Set.of("block")),
      Map.entry("edit", Set.of("count", "kind")),
      Map.entry("transition.lint", Set.of("keys")));
  private final Path directory;
  private final String participant;
  private final String session = UUID.randomUUID().toString();
  private final Map<String, Object> sessionData;
  private final long cap;
  private boolean closed;
  private IOException failure;
  private String lastErrors;
  private String lastTransitions;

  public static EventLog open(Path root, Path settingsDirectory, String lesson, String language,
      String version) throws IOException {
    return new EventLog(root, settingsDirectory, lesson, language, version, MAX_BYTES);
  }

  EventLog(Path root, Path settingsDirectory, String lesson, String language, String version,
      long cap) throws IOException {
    directory = root.resolve(DIRECTORY);
    this.cap = cap;
    Files.createDirectories(settingsDirectory);
    Path idFile = settingsDirectory.resolve("research-id");
    if (!Files.exists(idFile, LinkOption.NOFOLLOW_LINKS)) {
      try { Files.writeString(idFile, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW); }
      catch (java.nio.file.FileAlreadyExistsException ignored) { /* concurrent IDE startup */ }
    }
    if (Files.isSymbolicLink(idFile)) throw new IOException("Research ID is a symbolic link");
    participant = Files.readString(idFile).strip();
    if (!participant.matches("[a-f0-9-]{36}")) throw new IOException("Invalid research ID");
    if (!List.of("en", "de").contains(language)) throw new IOException("Invalid log language");
    sessionData = Map.of("lesson", reference(lesson), "language", language,
        "ide", version.matches("[0-9A-Za-z.+-]{1,80}") ? version : "development");
    if (!record("session.start", sessionData)) throw failure;
  }

  public static Path settingsDirectory() {
    String explicit = System.getProperty("scratch4j.settingsDir", "");
    if (!explicit.isBlank()) return Path.of(explicit);
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (os.contains("win") && System.getenv("APPDATA") != null)
      return Path.of(System.getenv("APPDATA"), "Scratch4J");
    if (os.contains("mac")) return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Scratch4J");
    return Path.of(System.getProperty("user.home"), ".config", "scratch4j");
  }

  /** Teacher-authored IDs remain readable; free-form text falls back to an opaque ID. */
  public static String reference(String value) {
    String text = value == null ? "" : value;
    return text.matches("[A-Za-z0-9_./:-]{0,160}") ? text : id(text);
  }

  /** Opaque IDs for account codes and palette identifiers. */
  public static String id(String value) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
  }

  public synchronized boolean record(String type, Map<String, ?> data) {
    if (closed || failure != null) return false;
    validate(type, data);
    var event = new LinkedHashMap<String, Object>();
    event.put("v", 1); event.put("t", Instant.now().toString());
    event.put("session", session); event.put("participant", participant); event.put("type", type);
    event.putAll(data);
    byte[] bytes = (JSON.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 32_768 || bytes.length > cap) throw new IllegalArgumentException("Event too large");
    try {
      Files.createDirectories(directory);
      // Never follow project-controlled links to an unrelated local file.
      if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent()))
        throw new IOException("Log directory is a symbolic link");
      Path file = directory.resolve("events.jsonl");
      if (Files.isSymbolicLink(file)) throw new IOException("Log is a symbolic link");
      if (Files.exists(file) && Files.size(file) + bytes.length + 1 > cap) rotate();
      if (Files.exists(file) && Files.size(file) > 0) {
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
          var last = java.nio.ByteBuffer.allocate(1);
          channel.read(last, channel.size() - 1);
          if (last.array()[0] != '\n') Files.writeString(file, "\n", StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        }
      }
      Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
      return true;
    } catch (IOException e) { failure = e; return false; }
  }

  private static void validate(String type, Map<String, ?> data) {
    Set<String> fields = FIELDS.get(type);
    if (fields == null || !fields.equals(data.keySet())) throw new IllegalArgumentException("Unknown event fields: " + type);
    data.forEach((key, value) -> {
      boolean valid = switch (key) {
        case "success", "passed" -> value instanceof Boolean;
        case "line", "errorCount", "attempt", "level", "count" -> value instanceof Number n && n.longValue() >= 0;
        case "lesson", "step" -> value instanceof String s && s.matches("[A-Za-z0-9_./:-]{0,160}");
        case "block" -> value instanceof String s && s.matches("[a-f0-9]{64}");
        case "name" -> value instanceof String s && s.length() <= 300 && !s.contains("\n") && !s.contains("\r");
        case "case" -> value instanceof String s && s.matches("[A-Za-z0-9_.-]{0,100}");
        case "exception" -> value instanceof String s && s.matches("java\\.(?:lang|util|io)\\.[A-Za-z]+(?:Exception|Error)|other");
        case "kind" -> Set.of("stage", "sprite", "window", "other").contains(value);
        case "view" -> Set.of("CODE", "SPLIT", "VISUAL").contains(value);
        case "action" -> Set.of("explanation", "rename", "fix.applied", "fix.dismissed", "api").contains(value);
        case "state" -> Set.of("started", "completed").contains(value);
        case "rule" -> Set.of("error.3runs", "step.3runs").contains(value);
        case "language" -> Set.of("en", "de").contains(value);
        case "ide" -> value instanceof String s && s.matches("[0-9A-Za-z.+-]{1,80}");
        case "keys" -> value instanceof List<?> list && list.stream().allMatch(k -> k instanceof String s && s.matches("[a-z.]{1,100}"));
        case "errors" -> value instanceof List<?> list && list.stream().allMatch(EventLog::validError);
        default -> false;
      };
      if (!valid) throw new IllegalArgumentException("Invalid event field: " + key);
    });
  }
  private static boolean validError(Object value) {
    if (!(value instanceof Map<?, ?> m) || !m.keySet().equals(Set.of("case", "code", "line", "kind"))) return false;
    return m.get("case") instanceof String s && s.matches("[A-Za-z0-9_.-]{0,100}")
        && m.get("code") instanceof String c && c.matches("compiler\\.[a-z0-9_.]+|^$")
        && m.get("line") instanceof Number n && n.longValue() >= 0
        && Set.of("stage", "sprite", "window", "other").contains(m.get("kind"));
  }

  /** Change-only streams: callers pass only keys, diagnostic codes, line numbers and kinds. */
  public synchronized void errorsChanged(List<Map<String, Object>> errors) {
    var sorted = errors.stream().distinct().sorted(java.util.Comparator.comparing(JSON::writeValueAsString)).toList();
    String fingerprint = JSON.writeValueAsString(sorted);
    if (!fingerprint.equals(lastErrors)) {
      if (record("errors.changed", Map.of("errors", sorted))) lastErrors = fingerprint;
    }
  }
  public synchronized void transitions(List<String> keys) {
    var sorted = keys.stream().distinct().sorted().toList();
    String fingerprint = JSON.writeValueAsString(sorted);
    if (!fingerprint.equals(lastTransitions)) {
      if (record("transition.lint", Map.of("keys", sorted))) lastTransitions = fingerprint;
    }
  }
  private void rotate() throws IOException {
    for (int i = 3; i >= 1; i--) {
      Path target = directory.resolve("events.jsonl." + i);
      Path source = directory.resolve(i == 1 ? "events.jsonl" : "events.jsonl." + (i - 1));
      if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(source) || Files.isSymbolicLink(target)) throw new IOException("Log segment is a symbolic link");
        Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
  public synchronized IOException failure() { return failure; }
  public synchronized boolean active() { return !closed && failure == null; }
  public synchronized List<Path> segments() { return segmentsIn(directory); }
  public static List<Path> segments(Path projectRoot) { return segmentsIn(projectRoot.resolve(DIRECTORY)); }
  private static List<Path> segmentsIn(Path directory) {
    if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent())) return List.of();
    List<Path> files = new ArrayList<>();
    for (int i = 3; i >= 0; i--) {
      Path file = directory.resolve(i == 0 ? "events.jsonl" : "events.jsonl." + i);
      if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file);
    }
    return List.copyOf(files);
  }
  public synchronized String contents() throws IOException {
    return contentsIn(directory);
  }
  public static String contents(Path projectRoot) throws IOException { return contentsIn(projectRoot.resolve(DIRECTORY)); }
  private static String contentsIn(Path directory) throws IOException {
    StringBuilder text = new StringBuilder();
    for (Path path : segmentsIn(directory)) text.append(Files.readString(path));
    return text.toString();
  }
  /** Separate, deliberate submission. Shared-account codes replace the ID only in this copy. */
  public synchronized void handIn(Path destination, String sharedAccountCode) throws IOException {
    handInFrom(directory, destination, sharedAccountCode);
  }
  public static void handIn(Path projectRoot, Path destination, String sharedAccountCode) throws IOException {
    handInFrom(projectRoot.resolve(DIRECTORY), destination, sharedAccountCode);
  }
  private static void handInFrom(Path directory, Path destination, String sharedAccountCode) throws IOException {
    Path projectRoot = directory.toAbsolutePath().normalize().getParent().getParent();
    Path target = destination.toAbsolutePath().normalize();
    if (target.startsWith(projectRoot) || (Files.isDirectory(target.getParent())
        && target.getParent().toRealPath().startsWith(projectRoot.toRealPath())))
      throw new IOException("Choose a hand-in file outside the project folder so ZIP exports cannot include it");
    String code = sharedAccountCode == null ? "" : sharedAccountCode.strip();
    if (!code.isEmpty() && !code.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Use the teacher's pseudonym code (letters, digits, - or _)");
    StringBuilder copy = new StringBuilder();
    for (String line : contentsIn(directory).lines().toList()) {
      try {
        var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(line);
        if (!code.isEmpty()) node.put("participant", id(code));
        copy.append(JSON.writeValueAsString(node)).append('\n');
      } catch (RuntimeException ignored) { /* incomplete final line after a power loss */ }
    }
    AtomicFiles.writeString(destination, copy.toString());
  }
  @Override public synchronized void close() {
    if (!closed) record("session.end", sessionData);
    closed = true;
  }
  public synchronized void delete() throws IOException {
    closed = true;
    for (Path file : segments()) Files.delete(file);
  }
  public static void delete(Path projectRoot) throws IOException {
    for (Path file : segments(projectRoot)) Files.delete(file);
  }
}
