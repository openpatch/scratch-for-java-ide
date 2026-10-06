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
      int line, String method) {}

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
    boolean de = language == Language.DE;
    Frame own = null;
    Path file = null;
    for (Frame f : crash.frames()) {
      Path p = f.file() == null || f.file().isEmpty() ? null : projectFile.apply(f.file());
      if (p != null) {
        own = f;
        file = p;
        break;
      }
    }
    String name = crash.simpleName();
    String msg = crash.message() == null ? "" : crash.message();
    String title;
    String text;
    String hint;
    switch (name) {
      case "NullPointerException" -> {
        String what = nullCulprit(msg, de);
        title = what == null
            ? (de ? "Etwas ist null" : "Something is null")
            : (de ? what + " ist null" : what + " is null");
        text = de
            ? (what == null ? "Ein Wert hat noch kein Objekt" : what + " hat noch kein Objekt")
                + ", aber der Code benutzt es (ruft eine Methode auf oder liest ein Attribut)."
            : (what == null ? "A value has no object yet" : what + " has no object yet")
                + ", but the code uses it (calls a method or reads a field).";
        hint = de
            ? "Wurde es mit new erzeugt, z. B. " + example(what) + "? Oder wird es benutzt, "
                + "bevor die Zeile läuft, die es erzeugt?"
            : "Was it created with new, e.g. " + example(what) + "? Or is it used before "
                + "the line that creates it runs?";
      }
      case "ArrayIndexOutOfBoundsException", "IndexOutOfBoundsException",
          "StringIndexOutOfBoundsException" -> {
        Matcher m = Pattern.compile("[Ii]ndex:? (-?\\d+).*?(?:length|[Ss]ize):? (\\d+)")
            .matcher(msg);
        if (m.find()) {
          int index = Integer.parseInt(m.group(1));
          int length = Integer.parseInt(m.group(2));
          title = de ? "Index " + index + " gibt es nicht" : "There is no index " + index;
          text = length == 0
              ? (de ? "Die Liste (oder das Array) ist leer, es gibt also kein Element " + index
                  + "." : "The list (or array) is empty, so there is no element " + index + ".")
              : (de ? "Es gibt " + length + " Elemente mit den Nummern 0 bis " + (length - 1)
                  + "." : "There are " + length + " elements, numbered 0 to " + (length - 1)
                  + ".");
        } else {
          title = de ? "Index außerhalb des Bereichs" : "Index out of range";
          text = msg;
        }
        hint = de
            ? "Zählt die Schleife bis i < länge (nicht <=)? Wird ein leeres Element gelesen?"
            : "Does the loop run while i < length (not <=)? Is an empty list read?";
      }
      case "ArithmeticException" -> {
        title = de ? "Division durch 0" : "Division by zero";
        text = de ? "Eine ganze Zahl wurde durch 0 geteilt." : "A whole number was divided by 0.";
        hint = de ? "Prüfe vorher, ob der Teiler 0 ist." : "Check whether the divisor is 0 first.";
      }
      case "ConcurrentModificationException" -> {
        title = de ? "Liste beim Durchlaufen verändert" : "List changed while looping over it";
        text = de
            ? "Während eine for-each-Schleife eine Liste durchläuft, wurde etwas hinzugefügt "
                + "oder entfernt."
            : "Something was added to or removed from a list while a for-each loop went "
                + "through it.";
        hint = de
            ? "Entferne mit list.removeIf(...) oder merke dir die Elemente und entferne sie "
                + "nach der Schleife."
            : "Remove with list.removeIf(...), or remember the items and remove them after "
                + "the loop.";
      }
      case "StackOverflowError" -> {
        String loop = repeated(crash.frames());
        title = de ? "Endlose Selbstaufrufe" : "Endless self-calls";
        text = de
            ? (loop == null ? "Methoden rufen sich" : loop + " ruft sich")
                + " immer wieder selbst auf, bis der Speicher dafür voll ist."
            : (loop == null ? "Methods call themselves" : loop + " calls itself")
                + " again and again until there is no room left.";
        hint = de
            ? "Braucht die Methode eine Abbruchbedingung? Oder ruft ein Getter/Setter sich "
                + "selbst statt das Attribut auf?"
            : "Does the method need a condition to stop? Or does a getter/setter call itself "
                + "instead of using the field?";
      }
      case "ClassCastException" -> {
        Matcher m = Pattern.compile("class ([\\w.$]+) cannot be cast to class ([\\w.$]+)")
            .matcher(msg);
        String from = m.find() ? simple(m.group(1)) : null;
        String to = from == null ? null : simple(m.group(2));
        title = de ? "Falscher Typ" : "Wrong type";
        text = from == null ? msg : de
            ? "Ein " + from + "-Objekt wurde als " + to + " behandelt."
            : "A " + from + " object was treated as a " + to + ".";
        hint = de ? "Prüfe vorher mit instanceof." : "Check with instanceof first.";
      }
      case "NumberFormatException" -> {
        Matcher m = Pattern.compile("For input string: \"(.*)\"").matcher(msg);
        String input = m.find() ? m.group(1) : msg;
        title = de ? "\"" + input + "\" ist keine Zahl" : "\"" + input + "\" is not a number";
        text = de
            ? "Text sollte in eine Zahl umgewandelt werden, enthält aber keine Zahl."
            : "Text was turned into a number, but it does not contain one.";
        hint = de
            ? "Leerzeichen und Kommas stören (3,5 statt 3.5). Prüfe die Eingabe vorher."
            : "Spaces and commas get in the way (3,5 instead of 3.5). Check the input first.";
      }
      case "OutOfMemoryError" -> {
        title = de ? "Speicher voll" : "Out of memory";
        text = de
            ? "Das Programm hat zu viele Objekte erzeugt."
            : "The program created too many objects.";
        hint = de
            ? "Werden in run() in jedem Bild neue Figuren hinzugefügt und nie entfernt?"
            : "Are new sprites added in run() every frame and never removed?";
      }
      default -> {
        title = name;
        text = msg.isEmpty() ? (de ? "Das Programm ist abgestürzt." : "The program crashed.")
            : msg;
        hint = de ? "Die Zeile unten ist die letzte deines Codes vor dem Fehler."
            : "The line below is the last of your code before the error.";
      }
    }
    return new Explanation(title, text, hint, file, own == null ? -1 : own.line(),
        own == null ? null : simple(own.className()) + "." + own.method());
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
      return de ? "Das Ergebnis von " + method : "The result of " + method;
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
