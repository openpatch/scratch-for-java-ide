package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** "Find in project": every line of the project's text files that contains a text. */
public final class ProjectSearch {

  /**
   * One match: offsets in the file, 1-based line, the line's text (stripped)
   * and where the match sits in that stripped text.
   */
  public record Hit(Path file, int start, int end, long line, String lineText, int from,
      int to) {}

  /** At most this many hits; a search for "e" should not fill memory. */
  public static final int LIMIT = 2000;

  private static final Set<String> SKIPPED = Set.of(".scratch4j", ".git", "target", "build",
      "+libs", "export");
  private static final Set<String> TEXT = Set.of("java", "txt", "md", "json", "xml", "csv",
      "frag", "vert", "glsl", "tmx", "tsx", "css", "html", "properties");

  private ProjectSearch() {}

  /** Searches Java and other text files; Java files come first. */
  public static List<Hit> search(ScratchProject project, String query, boolean matchCase)
      throws IOException {
    if (query == null || query.isEmpty()) {
      return List.of();
    }
    List<Path> java = new ArrayList<>();
    List<Path> other = new ArrayList<>();
    Path root = project.root();
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
        return !dir.equals(root) && SKIPPED.contains(dir.getFileName().toString())
            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
      }

      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        if (a.isRegularFile() && a.size() <= 2_000_000 && TEXT.contains(extension)) {
          (extension.equals("java") ? java : other).add(file);
        }
        return FileVisitResult.CONTINUE;
      }
    });
    java.sort(null);
    other.sort(null);
    List<Hit> hits = new ArrayList<>();
    String needle = matchCase ? query : query.toLowerCase(Locale.ROOT);
    for (Path file : concat(java, other)) {
      String text;
      try {
        text = Files.readString(file, StandardCharsets.UTF_8);
      } catch (CharacterCodingException notText) {
        continue;
      }
      String haystack = matchCase ? text : text.toLowerCase(Locale.ROOT);
      if (haystack.length() != text.length()) {
        haystack = text; // a case change that alters lengths: fall back to exact case
        needle = query;
      }
      long line = 1;
      int lineStart = 0;
      int scanned = 0;
      for (int at = haystack.indexOf(needle); at >= 0;
           at = haystack.indexOf(needle, at + needle.length())) {
        for (int i = scanned; i < at; i++) {
          if (text.charAt(i) == '\n') {
            line++;
            lineStart = i + 1;
          }
        }
        scanned = at;
        int lineEnd = text.indexOf('\n', at);
        String raw = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
        int indent = raw.length() - raw.stripLeading().length();
        String stripped = raw.strip();
        int from = Math.max(0, at - lineStart - indent);
        int to = Math.min(stripped.length(), from + query.length());
        hits.add(new Hit(file, at, at + query.length(), line, stripped, from, to));
        if (hits.size() >= LIMIT) {
          return hits;
        }
      }
      needle = matchCase ? query : query.toLowerCase(Locale.ROOT);
    }
    return hits;
  }

  private static List<Path> concat(List<Path> first, List<Path> second) {
    List<Path> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }
}
