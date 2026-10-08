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
 *       file's code (comments removed) matches; with {@code "text"} instead of
 *       {@code "pattern"} the code is plain Java and spaces do not matter
 *       ({@code this.say("Hi")} also finds {@code this.say( "Hi" )});</li>
 *   <li>{@code {"changed": {"file": ..., "pattern": "move\\((\\d+)\\)", "from": "4"}}}:
 *       some match's first group is no longer {@code from};</li>
 *   <li>{@code {"classExists": {"name": "Coin", "extends": "Sprite", "min": 1}}}:
 *       the project declares such a class (every field optional; {@code extends}
 *       also counts classes that extend it through other project classes,
 *       {@code min} how many there must be);</li>
 *   <li>{@code {"methodExists": {"name": "whenClicked", "file": "Bunny.java"}}}:
 *       a method of that name is declared (in that file, or anywhere);</li>
 *   <li>{@code {"lineChanged": {"file": "Bunny.java", "line": "this.move(4);"}}}:
 *       the line is gone from the file (changed or removed; spacing does not
 *       matter, every copy of it must be gone);</li>
 *   <li>{@code {"all": [check, ...]}}: every check is met.</li>
 * </ul>
 *
 * <p>{@code languages} lists the languages the lesson is written in (for
 * example only {@code "de"}); a student whose IDE speaks another language
 * sees the English text if there is one, else the lesson's own language.
 */
public record Lesson(String id, String template, List<String> languages,
    Map<String, String> title, List<Step> steps) {

  /** The languages the IDE can write lessons in, in the order it offers them. */
  public static final List<String> LANGUAGES = List.of("de", "en");

  /** A lesson in the languages its title is written in. */
  public Lesson(String id, String template, Map<String, String> title, List<Step> steps) {
    this(id, template, languagesOf(title), title, steps);
  }

  private static List<String> languagesOf(Map<String, String> title) {
    List<String> out = LANGUAGES.stream().filter(title::containsKey).toList();
    return out.isEmpty() ? List.of("en") : out;
  }

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

  /** The program drew a frame since the step began. */
  public record Ran() implements Check {
    @Override public boolean met(Facts facts) {
      return facts.ranSinceStart();
    }

    @Override public boolean readsCode() {
      return false;
    }
  }

  /**
   * {@code file}'s code (without comments) has {@code pattern}: a regular
   * expression, or with {@code literal} plain Java where spacing does not matter.
   */
  public record Contains(String file, String pattern, boolean literal) implements Check {
    public Pattern compiled() {
      return Pattern.compile(literal ? literalPattern(pattern) : pattern);
    }

    @Override public boolean met(Facts facts) {
      String text = facts.files().get(file);
      return text != null && compiled().matcher(code(text)).find();
    }

    @Override public boolean readsCode() {
      return true;
    }
  }

  /** Some match of {@code pattern} in {@code file} has a first group other than {@code from}. */
  public record Changed(String file, String pattern, String from) implements Check {
    @Override public boolean met(Facts facts) {
      String text = facts.files().get(file);
      if (text == null) {
        return false;
      }
      Matcher m = Pattern.compile(pattern).matcher(code(text));
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

  private static final Pattern CLASS_DECLARATION = Pattern.compile(
      "\\bclass\\s+(\\w+)(?:\\s*<[^>{]*>)?(?:\\s+extends\\s+(\\w+))?");

  /**
   * At least {@code min} classes with this name (blank: any) that extend
   * {@code base} (blank: anything), directly or through project classes.
   */
  public record ClassExists(String name, String base, int min) implements Check {
    @Override public boolean met(Facts facts) {
      Map<String, String> parents = new LinkedHashMap<>();
      for (String text : facts.files().values()) {
        Matcher m = CLASS_DECLARATION.matcher(code(text));
        while (m.find()) {
          parents.put(m.group(1), m.group(2) == null ? "" : m.group(2));
        }
      }
      long count = parents.keySet().stream()
          .filter(c -> name.isBlank() || c.equals(name.strip()))
          .filter(c -> base.isBlank() || extendsBase(c, base.strip(), parents))
          .count();
      return count >= Math.max(1, min);
    }

    private static boolean extendsBase(String type, String base, Map<String, String> parents) {
      String parent = parents.get(type);
      for (int depth = 0; parent != null && !parent.isEmpty() && depth < 20; depth++) {
        if (parent.equals(base)) {
          return true;
        }
        parent = parents.get(parent);
      }
      return false;
    }

    @Override public boolean readsCode() {
      return true;
    }
  }

  /** A method called {@code name} is declared, in {@code file} (blank: in any file). */
  public record MethodExists(String name, String file) implements Check {
    @Override public boolean met(Facts facts) {
      // a declaration: the name and its parameters, then the body (a call ends with ;)
      Pattern declaration = Pattern.compile("\\b" + Pattern.quote(name.strip())
          + "\\s*\\([^;{}()]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");
      return facts.files().entrySet().stream()
          .filter(e -> file.isBlank() || e.getKey().equals(file.strip()))
          .anyMatch(e -> declaration.matcher(code(e.getValue())).find());
    }

    @Override public boolean readsCode() {
      return true;
    }
  }

  /** {@code line} is no longer in {@code file} (changed or removed). */
  public record LineChanged(String file, String line) implements Check {
    @Override public boolean met(Facts facts) {
      String text = facts.files().get(file);
      return text != null
          && !Pattern.compile(literalPattern(line)).matcher(code(text)).find();
    }

    @Override public boolean readsCode() {
      return true;
    }
  }

  /** Every check is met. */
  public record All(List<Check> checks) implements Check {
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
    Map<String, String> title = texts(root.path("title"));
    List<String> languages = new ArrayList<>();
    for (JsonNode language : root.path("languages")) {
      languages.add(language.asString());
    }
    return new Lesson(root.path("id").asString(), root.path("template").asString(""),
        languages.isEmpty() ? languagesOf(title) : List.copyOf(languages), title,
        List.copyOf(steps));
  }

  private static Check check(JsonNode node) throws IOException {
    if (node.has("ran")) {
      return new Ran();
    }
    if (node.has("contains")) {
      JsonNode c = node.get("contains");
      boolean literal = c.has("text");
      String pattern = literal ? c.path("text").asString() : c.path("pattern").asString();
      validate(literal ? literalPattern(pattern) : pattern);
      return new Contains(c.path("file").asString(), pattern, literal);
    }
    if (node.has("changed")) {
      JsonNode c = node.get("changed");
      String pattern = c.path("pattern").asString();
      validate(pattern);
      return new Changed(c.path("file").asString(), pattern, c.path("from").asString());
    }
    if (node.has("classExists")) {
      JsonNode c = node.get("classExists");
      return new ClassExists(c.path("name").asString(""), c.path("extends").asString(""),
          c.path("min").asInt(1));
    }
    if (node.has("methodExists")) {
      JsonNode c = node.get("methodExists");
      return new MethodExists(c.path("name").asString(), c.path("file").asString(""));
    }
    if (node.has("lineChanged")) {
      JsonNode c = node.get("lineChanged");
      return new LineChanged(c.path("file").asString(), c.path("line").asString());
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

  private static void validate(String regex) throws IOException {
    try {
      Pattern.compile(regex);
    } catch (java.util.regex.PatternSyntaxException e) {
      throw new IOException("Not a regular expression: " + regex, e);
    }
  }

  /**
   * Plain Java as a pattern that ignores spacing: spaces may appear (or not)
   * around punctuation, and at least one must stay between two words.
   */
  public static String literalPattern(String code) {
    StringBuilder out = new StringBuilder();
    char previous = 0;
    boolean space = false;
    for (int i = 0; i < code.length(); i++) {
      char c = code.charAt(i);
      if (Character.isWhitespace(c)) {
        space = previous != 0;
        continue;
      }
      if (previous != 0) {
        boolean words = Character.isJavaIdentifierPart(previous)
            && Character.isJavaIdentifierPart(c);
        out.append(words ? (space ? "\\s+" : "") : "\\s*");
      }
      out.append(Pattern.quote(String.valueOf(c)));
      previous = c;
      space = false;
    }
    return out.toString();
  }

  /** The lesson as JSON, in the format {@link #parse} reads. */
  public String toJson() {
    var root = JSON.createObjectNode();
    root.put("id", id);
    if (template != null && !template.isEmpty()) {
      root.put("template", template);
    }
    var languageArray = root.putArray("languages");
    languages.forEach(languageArray::add);
    putTexts(root.putObject("title"), title);
    var array = root.putArray("steps");
    for (Step step : steps) {
      var node = array.addObject();
      node.put("id", step.id());
      putTexts(node.putObject("title"), step.title());
      putTexts(node.putObject("text"), step.text());
      if (step.code() != null && !step.code().isBlank()) {
        node.put("code", step.code());
      }
      putCheck(node.putObject("check"), step.check());
    }
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
  }

  /** Writes the lesson into the project (replacing the one there). */
  public void write(Path projectRoot) throws IOException {
    Path file = projectRoot.resolve(FILE);
    Files.createDirectories(file.getParent());
    Files.writeString(file, toJson(), StandardCharsets.UTF_8);
  }

  private static void putTexts(tools.jackson.databind.node.ObjectNode node,
      Map<String, String> texts) {
    texts.forEach((language, text) -> {
      if (text != null && !text.isBlank()) {
        node.put(language, text);
      }
    });
  }

  private static void putCheck(tools.jackson.databind.node.ObjectNode node, Check check) {
    switch (check) {
      case Ran r -> node.put("ran", true);
      case Contains c -> {
        var contains = node.putObject("contains");
        contains.put("file", c.file());
        contains.put(c.literal() ? "text" : "pattern", c.pattern());
      }
      case Changed c -> {
        var changed = node.putObject("changed");
        changed.put("file", c.file());
        changed.put("pattern", c.pattern());
        changed.put("from", c.from());
      }
      case ClassExists c -> {
        var exists = node.putObject("classExists");
        if (!c.name().isBlank()) exists.put("name", c.name());
        if (!c.base().isBlank()) exists.put("extends", c.base());
        if (c.min() > 1) exists.put("min", c.min());
      }
      case MethodExists m -> {
        var exists = node.putObject("methodExists");
        exists.put("name", m.name());
        if (!m.file().isBlank()) exists.put("file", m.file());
      }
      case LineChanged l -> {
        var changed = node.putObject("lineChanged");
        changed.put("file", l.file());
        changed.put("line", l.line());
      }
      case All a -> {
        var all = node.putArray("all");
        for (Check inner : a.checks()) {
          putCheck(all.addObject(), inner);
        }
      }
    }
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

  /** The text in the student's language, else English, else the language it has. */
  private static String text(Map<String, String> texts, Locale locale) {
    String text = texts.get(locale.getLanguage());
    if (text == null) text = texts.get("en");
    if (text == null) text = texts.values().stream().findFirst().orElse("");
    return text;
  }

  /** The code without comments, so a commented-out line does not count. */
  static String code(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", "");
  }
}
