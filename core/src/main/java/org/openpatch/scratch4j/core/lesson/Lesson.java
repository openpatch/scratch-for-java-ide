package org.openpatch.scratch4j.core.lesson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A guided lesson that lives in a project ({@code .scratch4j/lesson.json}):
 * steps with a title, an explanation, optional code to copy, and a check the
 * IDE evaluates after every project check and every run. A step is done when
 * its check is met; code checks also need the project to compile, so a step
 * never ticks off while the student is halfway through typing.
 *
 * <p>Texts are given per language ({@code "en"}, {@code "de"}); English is
 * the fallback. Checks:
 * <ul>
 *   <li>{@code {"ran": true}}: the program drew a frame since the step began;</li>
 *   <li>{@code {"contains": {"file": "Bunny.java", "pattern": "regex"}}}: the
 *       file's code (comments removed) matches;</li>
 *   <li>{@code {"changed": {"file": ..., "pattern": "move\\((\\d+)\\)", "from": "4"}}}:
 *       some match's first group is no longer {@code from};</li>
 *   <li>{@code {"all": [check, ...]}}: every check is met.</li>
 * </ul>
 */
public record Lesson(String id, String template, Map<String, String> title,
    List<Step> steps) {

  /** The project file a lesson is kept in. */
  public static final String FILE = ".scratch4j/lesson.json";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BUNDLED = "/org/openpatch/scratch4j/core/lessons/";

  /** One step: what to do, why, optional code to copy, and when it is done. */
  public record Step(String id, Map<String, String> title, Map<String, String> text,
      String code, Check check) {

    public String title(Locale locale) {
      return Lesson.text(title, locale);
    }

    public String text(Locale locale) {
      return Lesson.text(text, locale);
    }
  }

  /** What the check sees: each source file's text by file name, and whether it compiles. */
  public record Facts(Map<String, String> files, boolean compiles, boolean ranSinceStart) {}

  /** A step's completion condition. */
  public sealed interface Check {
    boolean met(Facts facts);

    /** Whether the condition reads code (then the project must also compile). */
    boolean readsCode();
  }

  record Ran() implements Check {
    @Override public boolean met(Facts facts) {
      return facts.ranSinceStart();
    }

    @Override public boolean readsCode() {
      return false;
    }
  }

  record Contains(String file, Pattern pattern) implements Check {
    @Override public boolean met(Facts facts) {
      String text = facts.files().get(file);
      return text != null && pattern.matcher(code(text)).find();
    }

    @Override public boolean readsCode() {
      return true;
    }
  }

  record Changed(String file, Pattern pattern, String from) implements Check {
    @Override public boolean met(Facts facts) {
      String text = facts.files().get(file);
      if (text == null) {
        return false;
      }
      Matcher m = pattern.matcher(code(text));
      while (m.find()) {
        if (m.groupCount() >= 1 && m.group(1) != null && !m.group(1).strip().equals(from)) {
          return true;
        }
      }
      return false;
    }

    @Override public boolean readsCode() {
      return true;
    }
  }

  record All(List<Check> checks) implements Check {
    @Override public boolean met(Facts facts) {
      return checks.stream().allMatch(c -> c.met(facts));
    }

    @Override public boolean readsCode() {
      return checks.stream().anyMatch(Check::readsCode);
    }
  }

  public String title(Locale locale) {
    return text(title, locale);
  }

  /** Whether {@code step}'s check is met (a code check also needs a compiling project). */
  public static boolean done(Step step, Facts facts) {
    return step.check().met(facts) && (!step.check().readsCode() || facts.compiles());
  }

  /** A lesson shipped with the IDE, by id (null if there is none). */
  public static Lesson bundled(String id) throws IOException {
    try (InputStream in = Lesson.class.getResourceAsStream(BUNDLED + id + ".json")) {
      return in == null ? null : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /** The ids of the lessons shipped with the IDE, in the order they are offered. */
  public static List<String> bundledIds() {
    return List.of("first-steps");
  }

  /** The project's lesson, or null when it has none. */
  public static Lesson load(Path projectRoot) throws IOException {
    Path file = projectRoot.resolve(FILE);
    return Files.isRegularFile(file) ? parse(Files.readString(file, StandardCharsets.UTF_8))
        : null;
  }

  /** Puts a bundled lesson into a project (it travels with the project from then on). */
  public static void install(Path projectRoot, String id) throws IOException {
    try (InputStream in = Lesson.class.getResourceAsStream(BUNDLED + id + ".json")) {
      if (in == null) {
        throw new IOException("No lesson " + id);
      }
      Path file = projectRoot.resolve(FILE);
      Files.createDirectories(file.getParent());
      Files.write(file, in.readAllBytes());
    }
  }

  public static Lesson parse(String json) throws IOException {
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (RuntimeException e) {
      throw new IOException("Not a lesson: " + e.getMessage(), e);
    }
    List<Step> steps = new ArrayList<>();
    for (JsonNode step : root.path("steps")) {
      JsonNode code = step.get("code");
      steps.add(new Step(step.path("id").asString(), texts(step.path("title")),
          texts(step.path("text")), code == null || code.isNull() ? null : code.asString(),
          check(step.path("check"))));
    }
    if (steps.isEmpty()) {
      throw new IOException("A lesson needs steps");
    }
    return new Lesson(root.path("id").asString(), root.path("template").asString(""),
        texts(root.path("title")), List.copyOf(steps));
  }

  private static Check check(JsonNode node) throws IOException {
    if (node.has("ran")) {
      return new Ran();
    }
    if (node.has("contains")) {
      JsonNode c = node.get("contains");
      return new Contains(c.path("file").asString(), Pattern.compile(c.path("pattern").asString()));
    }
    if (node.has("changed")) {
      JsonNode c = node.get("changed");
      return new Changed(c.path("file").asString(), Pattern.compile(c.path("pattern").asString()),
          c.path("from").asString());
    }
    if (node.has("all")) {
      List<Check> checks = new ArrayList<>();
      for (JsonNode c : node.get("all")) {
        checks.add(check(c));
      }
      return new All(List.copyOf(checks));
    }
    throw new IOException("Unknown lesson check: " + node);
  }

  private static Map<String, String> texts(JsonNode node) {
    Map<String, String> out = new LinkedHashMap<>();
    if (node.isString()) {
      out.put("en", node.asString());
    } else {
      for (var entry : node.properties()) {
        out.put(entry.getKey(), entry.getValue().asString());
      }
    }
    return out;
  }

  private static String text(Map<String, String> texts, Locale locale) {
    String text = texts.get(locale.getLanguage());
    return text != null ? text : texts.getOrDefault("en", "");
  }

  /** The code without comments, so a commented-out line does not count. */
  static String code(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", "");
  }
}
