package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports in student code: code the IDE writes uses simple names
 * ({@code RotationStyle.LEFT_RIGHT}) and adds {@code import ...;} lines, like a
 * student would, never fully qualified names. An existing wildcard import of
 * the package ({@code import org.openpatch.scratch.*;}) is enough.
 */
public final class JavaImports {

  /** Where an import goes: the text to insert at this offset. */
  public record Insertion(int offset, String text) {}

  private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+[^;]+;[ \\t]*\\r?\\n");
  private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+[^;]+;[ \\t]*\\r?\\n");
  private static final Pattern TYPE_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
  private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

  private JavaImports() {}

  /** The source with {@code import <qualifiedName>;} added when it is needed. */
  public static String ensure(String source, String qualifiedName) {
    Insertion insertion = insertion(source, qualifiedName);
    return insertion == null ? source : source.substring(0, insertion.offset())
        + insertion.text() + source.substring(insertion.offset());
  }

  /**
   * Where and what to insert for {@code import <qualifiedName>;}, or null when
   * nothing is needed: already imported (or its package with {@code *}),
   * {@code java.lang}, or another class of that simple name is imported.
   */
  public static Insertion insertion(String source, String qualifiedName) {
    int dot = qualifiedName.lastIndexOf('.');
    if (dot < 0) return null;
    String packageName = qualifiedName.substring(0, dot);
    String simpleName = qualifiedName.substring(dot + 1);
    if (packageName.equals("java.lang")) return null;
    Matcher imports = IMPORT.matcher(source);
    int after = -1;
    while (imports.find()) {
      String imported = imports.group().replaceFirst("^import\\s+", "")
          .replaceFirst("\\s*;\\s*$", "").replaceAll("\\s+", "");
      if (imported.equals(qualifiedName) || imported.equals(packageName + ".*")
          || imported.endsWith("." + simpleName)) {
        return null;
      }
      after = imports.end();
    }
    String line = "import " + qualifiedName + ";\n";
    if (after >= 0) return new Insertion(after, line);
    Matcher pkg = PACKAGE.matcher(source);
    if (pkg.find()) return new Insertion(pkg.end(), "\n" + line);
    return new Insertion(0, line + "\n");
  }

  /**
   * The library classes a snippet names ({@code Window.getInstance()},
   * {@code KeyCode keyCode}), qualified; text in quotes and the project's own
   * classes do not count.
   */
  public static List<String> neededBy(String snippet, Library library,
      Collection<String> projectClasses) {
    Set<String> needed = new LinkedHashSet<>();
    Matcher names = TYPE_NAME.matcher(STRING.matcher(snippet).replaceAll("\"\""));
    while (names.find()) {
      String name = names.group(1);
      if (projectClasses.contains(name)) continue;
      String qualified = library.qualified(name);
      if (qualified != null) needed.add(qualified);
    }
    return List.copyOf(needed);
  }

  /**
   * The top-level classes of the project's libraries by simple name. The
   * all-in-one jar also holds Processing, JOGL and Kotlin, so a name found
   * twice prefers the Scratch for Java library's own, public package.
   */
  public static final class Library {
    private static final Map<String, String> JDK = Map.of(
        "ArrayList", "java.util.ArrayList", "List", "java.util.List",
        "HashMap", "java.util.HashMap", "Map", "java.util.Map",
        "Scanner", "java.util.Scanner", "Arrays", "java.util.Arrays",
        "Collections", "java.util.Collections", "HashSet", "java.util.HashSet",
        "Set", "java.util.Set");
    private final List<Path> jars;
    private Map<String, String> classes;

    public Library(List<Path> jars) {
      this.jars = List.copyOf(jars);
    }

    /** The qualified name for a simple class name, or null when no library has it. */
    public String qualified(String simpleName) {
      if (classes == null) classes = read();
      return classes.getOrDefault(simpleName, JDK.get(simpleName));
    }

    private Map<String, String> read() {
      Map<String, List<String>> found = new HashMap<>();
      for (Path jar : jars) {
        if (!jar.toString().endsWith(".jar") || !Files.isRegularFile(jar)) continue;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
          zip.stream().map(ZipEntry::getName)
              .filter(n -> n.endsWith(".class") && !n.contains("$")
                  && !n.startsWith("META-INF/") && n.contains("/"))
              .forEach(n -> {
                String name = n.substring(0, n.length() - ".class".length()).replace('/', '.');
                found.computeIfAbsent(name.substring(name.lastIndexOf('.') + 1),
                    k -> new ArrayList<>()).add(name);
              });
        } catch (IOException e) {
          // an unreadable jar gives no imports
        }
      }
      Map<String, String> best = new HashMap<>();
      found.forEach((simple, names) -> best.put(simple, names.stream()
          .min(Comparator.comparingInt(Library::rank).thenComparingInt(String::length)
              .thenComparing(Comparator.naturalOrder())).orElseThrow()));
      return best;
    }

    private static int rank(String qualified) {
      if (!qualified.startsWith("org.openpatch.scratch.")) return 2;
      return qualified.contains(".internal.") ? 1 : 0;
    }
  }
}
