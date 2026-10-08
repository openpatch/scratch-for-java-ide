package org.openpatch.scratch4j.core.lint;

import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.IncrementalCompiler;
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
   * syntax error in the same file and may go away with it. {@code fix} is the
   * id of a one-click fix (the region fixes here, or one of {@link QuickFixes})
   * and {@code fixData} what it needs (an import line, a replacement name).
   */
  public record Problem(Path file, long line, long column, String message,
      String explanation, List<String> suggestions, boolean error, String fix,
      String original, boolean followUp, String fixData) {

    public Problem(Path file, long line, long column, String message, String explanation,
        List<String> suggestions, boolean error, String fix, String original,
        boolean followUp) {
      this(file, line, column, message, explanation, suggestions, error, fix, original,
          followUp, null);
    }

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

  /**
   * What one project's checks keep between runs: the incremental compiler
   * and each file's lint findings, keyed on the file's text (and the asset
   * files, which the asset and map lints read). One per open project; the
   * check runs one at a time, so it needs no locking.
   */
  public static final class Cache implements AutoCloseable {
    private final IncrementalCompiler compiler;
    private final java.util.Map<Path, Keyed<List<AssetLinter.Finding>>> assets =
        new java.util.HashMap<>();
    private final java.util.Map<Path, Keyed<List<org.openpatch.scratch4j.core.tiled.MapLinter.Finding>>>
        maps = new java.util.HashMap<>();
    private final java.util.Map<Path, Keyed<List<TransitionLinter.Finding>>> transitions =
        new java.util.HashMap<>();

    private record Keyed<T>(Object key, T value) {}

    public Cache(Path projectRoot) {
      this.compiler = new IncrementalCompiler(projectRoot.resolve(".scratch4j/build/check"));
    }

    /** What the last compile did (how many files it compiled). */
    public IncrementalCompiler.Stats lastCompile() {
      return compiler.lastStats();
    }

    private static <T> T cached(java.util.Map<Path, Keyed<T>> map, Path file, Object key,
        java.util.function.Supplier<T> compute) {
      Keyed<T> hit = map.get(file);
      if (hit != null && hit.key().equals(key)) {
        return hit.value();
      }
      T value = compute.get();
      map.put(file, new Keyed<>(key, value));
      return value;
    }

    private void retain(java.util.Collection<Path> sources) {
      assets.keySet().retainAll(sources);
      maps.keySet().retainAll(sources);
      transitions.keySet().retainAll(sources);
    }

    @Override
    public void close() throws IOException {
      compiler.close();
    }
  }

  /** A full check, compiling everything (tests, one-off callers). */
  public static List<Problem> check(ScratchProject project,
      DiagnosticsExplanations.Language language) {
    try (Cache cache = new Cache(project.root())) {
      return check(project, language, cache);
    } catch (IOException e) {
      return List.of(new Problem(null, 0, 0, "Could not check the project: " + e.getMessage(),
          null, List.of(), true));
    }
  }

  /** A check that reuses {@code cache}: only what changed is compiled and linted again. */
  public static List<Problem> check(ScratchProject project,
      DiagnosticsExplanations.Language language, Cache cache) {
    List<Problem> problems = new ArrayList<>();
    try {
      List<Path> sources = project.javaSources();
      List<Path> classpath = new ArrayList<>(project.libs());
      classpath.add(project.root());
      java.util.Locale messages = language == DiagnosticsExplanations.Language.DE
          ? java.util.Locale.GERMAN : java.util.Locale.ROOT;
      java.util.Map<Path, String> texts = new java.util.LinkedHashMap<>();
      for (Path source : sources) {
        texts.put(source, Files.readString(source, StandardCharsets.UTF_8));
      }
      cache.retain(sources);
      CompileResult result = cache.compiler.compile(texts, classpath, messages);

      Set<String> knownNames = null;
      FriendlyErrors friendly = new FriendlyErrors(language);
      var library = new org.openpatch.scratch4j.core.project.JavaImports.Library(project.libs());
      List<String> apiNames = result.errors().isEmpty() ? List.of()
          : ApiIndex.load().methodNames();
      java.util.Map<Path, List<String>> lines = new java.util.HashMap<>();
      Set<Path> brokenSyntax = new HashSet<>();
      for (var d : result.errors()) {
        Path file = sources.stream()
            .filter(s -> s.getFileName().toString().equals(fileNameOf(d.path())))
            .findFirst().orElse(null);
        List<String> suggestions = new ArrayList<>();
        String unknownName = null;
        if (file != null && d.code() != null && d.code().startsWith("compiler.err.cant.resolve")) {
          if (knownNames == null) {
            knownNames = knownNames(sources);
          }
          unknownName = identifierAt(file, d.line(), d.column());
          suggestions.addAll(DidYouMean.suggest(unknownName, knownNames, 3));
        }
        List<String> source = file == null ? List.of()
            : lines.computeIfAbsent(file, ProjectCheck::readLines);
        String className = file == null ? null
            : file.getFileName().toString().replaceFirst("\\.java$", "");
        var described = friendly.describe(d, new FriendlyErrors.Context(source, className,
            name -> {
              String qualified = library.qualified(name);
              return qualified == null ? null : "import " + qualified + ";";
            }, apiNames));
        for (String s : described.suggestions()) {
          if (!suggestions.contains(s)) suggestions.add(s);
        }
        // after a syntax error, the rest of the file is often only its echo
        boolean followUp = brokenSyntax.contains(file);
        if (d.code() != null && FriendlyErrors.SYNTAX_ERRORS.contains(d.code())) {
          brokenSyntax.add(file);
        }
        String title = described.title();
        String fix = described.fix();
        String fixData = described.fixData();
        if (fix == null && unknownName != null) {
          // one clear candidate (or the same name in another case): the bulb renames
          String pick = QuickFixes.pickRename(unknownName, suggestions);
          if (pick != null) {
            fix = QuickFixes.RENAME;
            fixData = pick;
          }
        }
        problems.add(new Problem(file, d.line(), d.column(), title, described.explanation(),
            List.copyOf(suggestions), true, fix,
            title.equals(d.message()) ? null : d.message(), followUp, fixData));
      }

      AssetLinter linter = new AssetLinter();
      String assetFiles = assetFingerprint(project.root());
      for (Path source : sources) {
        String text = texts.get(source);
        for (AssetLinter.Finding f : Cache.cached(cache.assets, source,
            List.of(text, assetFiles), () -> linter.lint(project.root(), source, text))) {
          problems.add(new Problem(f.file(), f.line(), 0, f.message(), null,
              f.suggestions(), false));
        }
      }
      // Tiled maps: missing files, formats, layer names
      var maps = new org.openpatch.scratch4j.core.tiled.MapLinter(language)
          .withLibraryVersion(org.openpatch.scratch4j.core.project.LibraryCheck
              .projectVersion(project));
      for (Path source : sources) {
        String text = texts.get(source);
        for (var f : Cache.cached(cache.maps, source,
            List.of(text, assetFiles, language, String.valueOf(
                org.openpatch.scratch4j.core.project.LibraryCheck.projectVersion(project))),
            () -> maps.lint(project.root(), source, text))) {
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
      List<Path> libs = project.libs();
      for (Path source : sources) {
        String text = texts.get(source);
        for (TransitionLinter.Finding f : Cache.cached(cache.transitions, source,
            List.of(text, facts, language, libs), () -> transitions.lint(source, text, facts))) {
          // a plain while (true) { ... } wrapper: the bulb unwraps it
          boolean unwrap = "forever".equals(f.kind())
              && QuickFixes.canRemoveForever(texts.get(source), f.line());
          problems.add(new Problem(f.file(), f.line(), 0, f.message(), f.explanation(),
              List.of(), false, unwrap ? QuickFixes.FOREVER : null));
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

  /**
   * The project's files other than Java sources (images, sounds, maps), by
   * path, size and change time: the asset lints depend on them.
   */
  static String assetFingerprint(Path root) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (var walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
        Path rel = root.relativize(p);
        String first = rel.getName(0).toString();
        String name = p.getFileName().toString();
        if (first.startsWith(".") || first.equals("build") || first.equals("target")
            || first.equals("export") || name.endsWith(".java") || name.endsWith(".class")) {
          continue;
        }
        sb.append(rel).append('|').append(Files.size(p)).append('|')
            .append(Files.getLastModifiedTime(p).toMillis()).append('\n');
      }
    }
    return sb.toString();
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return List.of();
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
