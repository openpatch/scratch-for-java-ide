package org.openpatch.scratch4j.core.project;

import org.openpatch.scratch4j.core.compile.Symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the project uses a file, a folder or a Java class: what the IDE shows
 * before a move, rename or delete changes it. A usage the IDE can update by
 * itself (a Java string that is exactly the file's path, the splash image) is
 * {@code updatable}; everything else (a path built from pieces, a mention in a
 * shader or map, a class used by other classes) needs a look by hand.
 */
public final class FileUsages {

  /**
   * One usage: the file it is in, 1-based line (0 for a project setting), the
   * line's text (stripped) and where the path or name sits in that text.
   */
  public record Usage(Path file, int start, int end, long line, String lineText, int from,
      int to, boolean updatable) {}

  /** What a move or rename does with the usages it finds. */
  public enum Mode {
    /** Update the usages the IDE can update; refuse if any other usage exists. */
    STRICT,
    /** Update the usages the IDE can update; leave the others (the user knows). */
    UPDATE,
    /** Change nothing but the file system. */
    IGNORE
  }

  private FileUsages() {}

  /** The usages of a project file or folder; a Java file's are the uses of its class. */
  public static List<Usage> find(ScratchProject project, Path path) throws IOException {
    if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java")) {
      return ofClass(project, path);
    }
    return ProjectAssetManagement.usages(project, path);
  }

  /** The uses of the class that {@code Name.java} declares, outside that file. */
  static List<Usage> ofClass(ScratchProject project, Path file) throws IOException {
    String name = file.getFileName().toString().replaceFirst("\\.java$", "");
    Path real = file.toRealPath();
    Map<Path, String> texts = new HashMap<>();
    Path key = null;
    for (Path source : project.javaSources()) {
      texts.put(source, Files.readString(source));
      if (source.toRealPath().equals(real)) {
        key = source;
      }
    }
    if (key == null) {
      return List.of();
    }
    Matcher declaration = Pattern.compile("\\b(?:class|interface|enum|record)\\s+("
        + Pattern.quote(name) + ")\\b").matcher(texts.get(key));
    if (!declaration.find()) {
      return List.of();
    }
    List<Usage> usages = new ArrayList<>();
    var symbol = Symbols.at(key, declaration.start(1), texts, project.libs());
    if (symbol.isEmpty()) {
      return List.of();
    }
    for (Symbols.Occurrence o : symbol.get().occurrences()) {
      if (o.file().equals(key)) {
        continue;
      }
      String text = texts.get(o.file());
      int from = column(text, o.start());
      usages.add(new Usage(o.file(), o.start(), o.end(), o.line(), o.lineText(), from,
          from + name.length(), false));
    }
    return usages;
  }

  /** A usage at {@code start..end} of {@code text} (its line and position in it). */
  static Usage at(Path file, String text, int start, int end, boolean updatable) {
    long line = 1;
    for (int i = 0; i < start; i++) {
      if (text.charAt(i) == '\n') line++;
    }
    int lineStart = start == 0 ? 0 : text.lastIndexOf('\n', start - 1) + 1;
    int lineEnd = text.indexOf('\n', start);
    String raw = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
    int from = column(text, start);
    String stripped = raw.strip();
    return new Usage(file, start, end, line, stripped, Math.min(from, stripped.length()),
        Math.min(from + (end - start), stripped.length()), updatable);
  }

  /** Where {@code offset} sits in its line once the indentation is stripped. */
  private static int column(String text, int offset) {
    int lineStart = offset == 0 ? 0 : text.lastIndexOf('\n', offset - 1) + 1;
    int lineEnd = text.indexOf('\n', offset);
    String raw = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
    int indent = raw.length() - raw.stripLeading().length();
    return Math.max(0, offset - lineStart - indent);
  }
}
