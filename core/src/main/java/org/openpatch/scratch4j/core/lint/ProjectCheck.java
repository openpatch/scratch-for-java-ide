package org.openpatch.scratch4j.core.lint;

import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One full check of a project: compile it (errors with beginner explanations)
 * plus the asset lints and the Scratch-transition lints, as one sorted problem
 * list for the UI.
 */
public final class ProjectCheck {

  /**
   * A problem with everything the problems pane needs. {@code column} is
   * 1-based, 0 when only the line is known; {@code error} separates compile
   * errors (the program cannot run) from asset lints (it runs, but fails).
   * {@code original} is javac's own message when {@code message} is the
   * friendly headline (null otherwise); a {@code followUp} error comes after a
   * syntax error in the same file and may go away with it.
   */
  public record Problem(Path file, long line, long column, String message,
      String explanation, List<String> suggestions, boolean error, String fix,
      String original, boolean followUp) {

    public Problem(Path file, long line, long column, String message, String explanation,
        List<String> suggestions, boolean error, String fix) {
      this(file, line, column, message, explanation, suggestions, error, fix, null, false);
    }

    public Problem(Path file, long line, long column, String message, String explanation,
        List<String> suggestions, boolean error) {
      this(file, line, column, message, explanation, suggestions, error, null);
    }
  }

  /** A hint: a hand-written sprite the stage designer can take over. */
  public static final String FIX_PROMOTE = "region.promote";

  /** The fix for a statement the stage designer does not manage: move it below the region. */
  public static final String FIX_MOVE_OUT_OF_REGION = "region.moveOut";

  private ProjectCheck() {}

  public static List<Problem> check(ScratchProject project,
      DiagnosticsExplanations.Language language) {
    List<Problem> problems = new ArrayList<>();
    try {
      List<Path> sources = project.javaSources();
      List<Path> classpath = new ArrayList<>(project.libs());
      classpath.add(project.root());
      Path outDir = project.root().resolve(".scratch4j/build/check");
      java.util.Locale messages = language == DiagnosticsExplanations.Language.DE
          ? java.util.Locale.GERMAN : java.util.Locale.ROOT;
      CompileResult result = new CompilerService().compile(sources, classpath, outDir, messages);

      Set<String> knownNames = null;
      FriendlyErrors friendly = new FriendlyErrors(language);
      Imports imports = new Imports(project.libs());
      List<String> apiNames = result.errors().isEmpty() ? List.of()
          : ApiIndex.load().methodNames();
      java.util.Map<Path, List<String>> lines = new java.util.HashMap<>();
      Set<Path> brokenSyntax = new HashSet<>();
      for (var d : result.errors()) {
        Path file = sources.stream()
            .filter(s -> s.getFileName().toString().equals(fileNameOf(d.path())))
            .findFirst().orElse(null);
        List<String> suggestions = new ArrayList<>();
        if (file != null && d.code() != null && d.code().startsWith("compiler.err.cant.resolve")) {
          if (knownNames == null) {
            knownNames = knownNames(sources);
          }
          String name = identifierAt(file, d.line(), d.column());
          suggestions.addAll(DidYouMean.suggest(name, knownNames, 3));
        }
        List<String> source = file == null ? List.of()
            : lines.computeIfAbsent(file, ProjectCheck::readLines);
        String className = file == null ? null
            : file.getFileName().toString().replaceFirst("\\.java$", "");
        var described = friendly.describe(d, new FriendlyErrors.Context(source, className,
            imports::lineFor, apiNames));
        for (String s : described.suggestions()) {
          if (!suggestions.contains(s)) suggestions.add(s);
        }
        // after a syntax error, the rest of the file is often only its echo
        boolean followUp = brokenSyntax.contains(file);
        if (d.code() != null && FriendlyErrors.SYNTAX_ERRORS.contains(d.code())) {
          brokenSyntax.add(file);
        }
        String title = described.title();
        problems.add(new Problem(file, d.line(), d.column(), title, described.explanation(),
            List.copyOf(suggestions), true, null,
            title.equals(d.message()) ? null : d.message(), followUp));
      }

      AssetLinter linter = new AssetLinter();
      java.util.Map<Path, String> texts = new java.util.LinkedHashMap<>();
      for (Path source : sources) {
        texts.put(source, Files.readString(source, StandardCharsets.UTF_8));
      }
      for (Path source : sources) {
        for (AssetLinter.Finding f : linter.lint(project.root(), source, texts.get(source))) {
          problems.add(new Problem(f.file(), f.line(), 0, f.message(), null,
              f.suggestions(), false));
        }
      }
      // Tiled maps: missing files, formats, layer names
      var maps = new org.openpatch.scratch4j.core.tiled.MapLinter(language)
          .withLibraryVersion(org.openpatch.scratch4j.core.project.LibraryCheck
              .projectVersion(project));
      for (Path source : sources) {
        for (var f : maps.lint(project.root(), source, texts.get(source))) {
          problems.add(new Problem(f.file(), f.line(), 0, f.message(), f.explanation(),
              f.suggestions(), false));
        }
      }
      // stage designer regions it cannot read: the line, and the fix
      boolean de = language == DiagnosticsExplanations.Language.DE;
      for (Path source : sources) {
        String text = texts.get(source);
        if (!text.contains("scratch4j:begin") || !"Stage".equals(
            org.openpatch.scratch4j.core.region.DesignerRegions.kindOf(source.getParent(),
                source.getFileName().toString().replaceFirst("\\.java$", "")))) {
          continue;
        }
        var issue = org.openpatch.scratch4j.core.region.StageDocument.regionIssue(text);
        if (issue == null) {
          for (var promotion : org.openpatch.scratch4j.core.region.DesignerPromotion.find(text)) {
            problems.add(new Problem(source, promotion.firstLine(), 0, de
                ? "Die Figur " + promotion.name() + " kann der B\u00fchnen-Designer \u00fcbernehmen."
                : "The stage designer can take over the sprite " + promotion.name() + ".",
                de ? "Dann kannst du sie im Designer sehen, verschieben und einstellen."
                    : "You can then see, move and set it up in the designer.",
                List.of(), false, FIX_PROMOTE));
          }
        }
        if (issue != null && issue.line() > 0) {
          problems.add(new Problem(source, issue.line(), 0, de
              ? "Der B\u00fchnen-Designer kann diese Zeile nicht lesen und ist darum "
                  + "schreibgesch\u00fctzt."
              : "The stage designer cannot read this line, so it is read-only.",
              de ? "Im Bereich zwischen scratch4j:begin und scratch4j:end stehen nur "
                  + "Hintergr\u00fcnde, Kl\u00e4nge und Figuren mit Position, Gr\u00f6\u00dfe "
                  + "und Kost\u00fcm. Eigener Code geh\u00f6rt darunter (" + issue.message() + ")."
                  : "Between scratch4j:begin and scratch4j:end the designer keeps only "
                  + "backdrops, sounds and sprites with their position, size and costume. Your "
                  + "own code belongs below the region (" + issue.message() + ").",
              List.of(), false, FIX_MOVE_OUT_OF_REGION));
        }
      }
      // Scratch-to-Java pitfalls: forever loops, sleep, a second window, shared counters
      TransitionLinter transitions = new TransitionLinter(language)
          .withCallbacks(LibraryCallbacks.of(project.libs()));
      TransitionLinter.Facts facts = TransitionLinter.facts(texts);
      for (Path source : sources) {
        for (TransitionLinter.Finding f : transitions.lint(source, texts.get(source), facts)) {
          problems.add(new Problem(f.file(), f.line(), 0, f.message(), f.explanation(),
              List.of(), false));
        }
      }
    } catch (IOException e) {
      problems.add(new Problem(null, 0, 0, "Could not check the project: " + e.getMessage(),
          null, List.of(), true));
    }
    problems.sort(Comparator
        .comparing((Problem p) -> p.file() == null ? "" : p.file().toString())
        .thenComparingLong(Problem::line));
    return problems;
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return List.of();
    }
  }

  /**
   * Import lines for classes a student uses without importing them: the
   * top-level classes of the project's libraries and a few of the JDK.
   */
  static final class Imports {
    private static final java.util.Map<String, String> JDK = java.util.Map.of(
        "ArrayList", "java.util.ArrayList", "List", "java.util.List",
        "HashMap", "java.util.HashMap", "Map", "java.util.Map",
        "Scanner", "java.util.Scanner", "Arrays", "java.util.Arrays",
        "Collections", "java.util.Collections", "HashSet", "java.util.HashSet",
        "Set", "java.util.Set");
    private final List<Path> jars;
    private java.util.Map<String, String> library;

    Imports(List<Path> jars) {
      this.jars = jars;
    }

    /** {@code import a.b.Name;} or null when no library has a class of this name. */
    String lineFor(String simpleName) {
      if (library == null) {
        library = new java.util.HashMap<>();
        for (Path jar : jars) {
          if (!jar.toString().endsWith(".jar") || !Files.isRegularFile(jar)) continue;
          try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                .filter(n -> n.endsWith(".class") && !n.contains("$")
                    && !n.startsWith("META-INF/") && n.contains("/"))
                .forEach(n -> {
                  String name = n.substring(0, n.length() - ".class".length()).replace('/', '.');
                  // first one wins: two classes of one name are ambiguous anyway
                  library.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
                });
          } catch (IOException e) {
            // an unreadable jar gives no import hints
          }
        }
      }
      String qualified = library.getOrDefault(simpleName, JDK.get(simpleName));
      return qualified == null ? null : "import " + qualified + ";";
    }
  }

  private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");

  /** Library method names plus every identifier in the project: did-you-mean candidates. */
  private static Set<String> knownNames(List<Path> sources) throws IOException {
    Set<String> names = new HashSet<>(ApiIndex.load().methodNames());
    for (Path source : sources) {
      Matcher m = IDENTIFIER.matcher(Files.readString(source, StandardCharsets.UTF_8));
      while (m.find()) {
        names.add(m.group());
      }
    }
    return names;
  }

  /**
   * The identifier javac points at (1-based line/column). For a member
   * select ({@code this.mvoe}) javac points at the dot, so skip it.
   */
  static String identifierAt(Path file, long line, long column) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    if (line < 1 || line > lines.size()) {
      return "";
    }
    String text = lines.get((int) line - 1);
    int start = (int) column - 1;
    if (start >= 0 && start < text.length() && text.charAt(start) == '.') {
      start++;
    }
    if (start < 0 || start >= text.length()) {
      return "";
    }
    int end = start;
    while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
      end++;
    }
    return text.substring(start, end);
  }

  /** javac reports the path as given; we pass absolute paths, so use the file name. */
  private static String fileNameOf(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
