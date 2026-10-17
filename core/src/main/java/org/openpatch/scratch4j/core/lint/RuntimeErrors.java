package org.openpatch.scratch4j.core.lint;

import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the stack trace of a crashed program into what a student needs: the
 * line of their own code where it happened and what went wrong in plain words
 * ("{@code enemy} is null: it has no object yet").
 *
 * <p>Feed the program's stderr line by line to a {@link Collector}; a trace
 * is complete at the first line that does not belong to it (or at
 * {@link Collector#flush()} when the program ends).
 */
public final class RuntimeErrors {

  /** One {@code at Class.method(File.java:42)} line. */
  public record Frame(String className, String method, String file, int line) {}

  /** A parsed trace: the exception that caused it all, and where. */
  public record Crash(String exception, String message, List<Frame> frames) {

    public String simpleName() {
      return exception.substring(exception.lastIndexOf('.') + 1);
    }
  }

  /**
   * What the student sees: a title, the explanation, a hint what to try, and
   * where in their code (null when no frame is theirs).
   */
  public record Explanation(String title, String explanation, String hint, Path file,
      int line, String method, String exception, String caseKey, String trace) {
    public Explanation(String title, String explanation, String hint, Path file, int line, String method) {
      this(title, explanation, hint, file, line, method, "", "unknown", null);
    }
  }

  private static final Pattern HEAD = Pattern.compile(
      "^(?:Exception in thread \"[^\"]*\" )?(?:Caused by: )?"
          + "((?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error|Throwable))(?::\\s?(.*))?$");
  private static final Pattern FRAME = Pattern.compile(
      "^\\s+at\\s+(?:[\\w.$/@ ]+/)?([\\w.$]+)\\.([\\w$<>]+)\\(([^:)]*)(?::(\\d+))?\\)");
  private static final Pattern MORE = Pattern.compile("^\\s+\\.\\.\\. \\d+ (?:more|common frames omitted)");

  private RuntimeErrors() {}

  /** Collects stderr lines into crashes. Not thread-safe: feed from one thread. */
  public static final class Collector {
    private String exception;
    private String message;
    private List<Frame> frames = new ArrayList<>();
    private boolean causedBy;

    /** The crash a finished trace describes, when this line ends one. */
    public Optional<Crash> feed(String line) {
      Matcher head = HEAD.matcher(line);
      if (head.matches()) {
        boolean cause = line.startsWith("Caused by: ");
        if (exception != null && cause) {
          // the root cause names what really happened; keep the frames seen so far
          exception = head.group(1);
          message = head.group(2) == null ? "" : head.group(2);
          causedBy = true;
          return Optional.empty();
        }
        Optional<Crash> previous = flush();
        exception = head.group(1);
        message = head.group(2) == null ? "" : head.group(2);
        frames = new ArrayList<>();
        causedBy = false;
        return previous;
      }
      if (exception != null) {
        Matcher frame = FRAME.matcher(line);
        if (frame.find()) {
          Frame f = new Frame(frame.group(1), frame.group(2), frame.group(3),
              frame.group(4) == null ? -1 : Integer.parseInt(frame.group(4)));
          // a cause's frames are deeper: they go first
          if (causedBy) {
            frames.add(0, f);
          } else {
            frames.add(f);
          }
          return Optional.empty();
        }
        if (MORE.matcher(line).find() || line.trim().startsWith("Suppressed:")) {
          return Optional.empty();
        }
        return flush();
      }
      return Optional.empty();
    }

    /** The trace collected so far as a crash (the program ended, or output paused). */
    public Optional<Crash> flush() {
      if (exception == null) {
        return Optional.empty();
      }
      Crash crash = new Crash(exception, message, List.copyOf(frames));
      exception = null;
      message = null;
      frames = new ArrayList<>();
      causedBy = false;
      return Optional.of(crash);
    }
  }

  /**
   * Explains a crash. {@code projectFile} maps a source file name
   * ({@code "Player.java"}) to the project file, or null when it is not the
   * student's (library and JDK frames).
   */
  public static Explanation explain(Crash crash,
      java.util.function.Function<String, Path> projectFile, Language language) {
    var bundle = java.util.ResourceBundle.getBundle(
        "org.openpatch.scratch4j.core.lint.explanations",
        language == Language.DE ? java.util.Locale.GERMAN : java.util.Locale.ENGLISH);
    java.util.function.BiFunction<String, String[], String> text = (key, args) -> {
      String value = bundle.getString("runtime." + key);
      for (int i = 0; i < args.length; i++) value = value.replace("{" + i + "}", args[i]);
      return value;
    };
    Frame own = studentFrame(crash.frames(), projectFile).orElse(null);
    Path file = own == null ? null : projectFile.apply(own.file());
    String name = crash.simpleName();
    String msg = crash.message() == null ? "" : crash.message();
    String key = name;
    String[] args = {};
    switch (name) {
      case "NullPointerException" -> {
        String what = nullCulprit(msg, language == Language.DE);
        key += what == null ? ".unknown" : ".named";
        args = new String[]{what == null ? "" : what, example(what)};
      }
      case "ArrayIndexOutOfBoundsException", "IndexOutOfBoundsException",
          "StringIndexOutOfBoundsException" -> {
        Matcher m = Pattern.compile("[Ii]ndex:? (-?\\d+).*?(?:length|[Ss]ize):? (\\d+)").matcher(msg);
        key = "index";
        if (m.find()) {
          long index = Long.parseLong(m.group(1)), length = Long.parseLong(m.group(2));
          key += length == 0 ? ".empty" : ".bounds";
          args = new String[]{"" + index, "" + length, "" + (length - 1)};
        } else key += ".unknown";
      }
      case "ArithmeticException" -> { if (!msg.contains("/ by zero")) key = "unknown"; }
      case "StackOverflowError" -> {
        String method = repeated(crash.frames());
        key += method == null ? ".unknown" : ".named";
        args = new String[]{method == null ? "" : method};
      }
      case "ClassCastException" -> {
        Matcher m = Pattern.compile("class ([\\w.$]+) cannot be cast to class ([\\w.$]+)").matcher(msg);
        key += m.find() ? ".named" : ".unknown";
        if (key.endsWith(".named")) args = new String[]{simple(m.group(1)), simple(m.group(2))};
      }
      case "NumberFormatException" -> {
        Matcher m = Pattern.compile("For input string: \"(.*)\"").matcher(msg);
        args = new String[]{m.find() ? m.group(1) : msg};
      }
      case "NegativeArraySizeException" -> args = new String[]{msg};
      case "ConcurrentModificationException", "OutOfMemoryError",
          "IllegalMonitorStateException", "NoSuchElementException" -> { }
      default -> key = "unknown";
    }
    String title = key.equals("unknown") ? name : text.apply(key + ".title", args);
    String explanation = key.equals("unknown") && !msg.isBlank() ? msg : text.apply(key + ".text", args);
    String hint = text.apply(key + ".hint", args);
    String trace = crash.exception() + (msg.isBlank() ? "" : ": " + msg) + "\n"
        + String.join("\n", crash.frames().stream().map(f -> "\tat " + f.className() + "."
            + f.method() + "(" + f.file() + ":" + f.line() + ")").toList());
    return new Explanation(title, explanation, hint, file, own == null ? -1 : own.line(),
        own == null ? null : simple(own.className()) + "." + own.method(), crash.exception(), key, trace);
  }

  /** Shared with structured behavior-check feedback. */
  public static Optional<Frame> studentFrame(List<Frame> frames,
      java.util.function.Function<String, Path> projectFile) {
    return frames.stream().filter(f -> f.line() > 0 && f.file() != null
        && projectFile.apply(f.file()) != null).findFirst();
  }

  public static Optional<Frame> parseFrame(String line) {
    Matcher m = FRAME.matcher(line);
    return m.find() ? Optional.of(new Frame(m.group(1), m.group(2), m.group(3),
        m.group(4) == null ? -1 : Integer.parseInt(m.group(4)))) : Optional.empty();
  }

  /** From Java's helpful message: "this.enemy" -> "enemy", "<local2>" -> null. */
  static String nullCulprit(String message, boolean de) {
    Matcher m = Pattern.compile("because \"([^\"]+)\" is null").matcher(message);
    if (m.find()) {
      String expr = m.group(1);
      if (expr.startsWith("<")) {
        return null; // compiled without variable names
      }
      expr = expr.replaceFirst("^this\\.", "");
      return expr;
    }
    Matcher call = Pattern.compile("because the return value of \"([\\w.$]+)\\(\\)\" is null")
        .matcher(message);
    if (call.find()) {
      String method = simple(call.group(1)) + "()";
      var bundle = java.util.ResourceBundle.getBundle("org.openpatch.scratch4j.core.lint.explanations",
          de ? java.util.Locale.GERMAN : java.util.Locale.ENGLISH);
      return bundle.getString("runtime.null.result").replace("{0}", method);
    }
    return null;
  }

  private static String example(String what) {
    if (what == null || !what.matches("[A-Za-z_]\\w*")) {
      return "x = new Player();";
    }
    String type = Character.toUpperCase(what.charAt(0)) + what.substring(1);
    return what + " = new " + type + "();";
  }

  private static String repeated(List<Frame> frames) {
    if (frames.size() < 4) return null;
    Frame f = frames.get(0);
    int same = 0;
    for (Frame g : frames.subList(0, Math.min(20, frames.size()))) {
      if (g.className().equals(f.className()) && g.method().equals(f.method())) same++;
    }
    return same >= 3 ? simple(f.className()) + "." + f.method() + "()" : null;
  }

  private static String simple(String name) {
    String s = name.substring(name.lastIndexOf('.') + 1);
    return s.contains("$") ? s.substring(0, s.indexOf('$')) : s;
  }
}
