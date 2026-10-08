package org.openpatch.scratch4j.core.compile;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compile-as-you-type for one project that only recompiles what a change can
 * affect. The first compile (and any compile after the library, the message
 * language or the output directory changed) compiles every source. After
 * that, a compile takes the changed and removed files, every file without
 * up-to-date class files (it had errors, or javac skipped it after another
 * file's error), and everything that names one of their classes, directly
 * or through other files. The rest comes from the class files of earlier
 * compiles, which the compiler tracks file by file.
 *
 * <p>The result is the same diagnostics a full compile gives, as long as no
 * file reaches a class without naming it or a class that names it: a type
 * reached through a chain like {@code stage.getPlayer().move()} is named in
 * the file that declares {@code getPlayer}, so the chain is followed.
 *
 * <p>One instance per project; not thread-safe (the check runs one at a time).
 */
public final class IncrementalCompiler implements AutoCloseable {

  /** What the last compile did, for tests and the status line. */
  public record Stats(boolean full, int compiled, int total) {}

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
  private static final Pattern DECLARATION = Pattern.compile(
      "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

  private final Path outDir;
  private JavaCompiler compiler;
  private StandardJavaFileManager files;
  private List<Path> fileManagerClasspath = List.of();

  /** The text each source had when it was last compiled. */
  private final Map<Path, String> compiledText = new HashMap<>();
  /** The class files the last compile of each source wrote. */
  private final Map<Path, Set<Path>> classesBySource = new HashMap<>();
  /** Sources whose class files match their current text. */
  private final Set<Path> fresh = new HashSet<>();
  /** The diagnostics of each source's last compile; null key for those without a file. */
  private final Map<Path, List<CompilerDiagnostic>> diagnosticsBySource = new HashMap<>();
  /** Identifiers and declared class names per text, so unchanged files are not rescanned. */
  private final Map<Path, Scan> scans = new HashMap<>();
  private List<Path> lastClasspath;
  private Locale lastLocale;
  private Stats stats = new Stats(true, 0, 0);

  private record Scan(String text, Set<String> identifiers, Set<String> declared) {}

  public IncrementalCompiler(Path outDir) {
    this.outDir = outDir;
  }

  public Stats lastStats() {
    return stats;
  }

  @Override
  public void close() throws IOException {
    if (files != null) {
      files.close();
      files = null;
    }
  }

  /** Forgets everything: the next compile is a full one. */
  public void reset() {
    compiledText.clear();
    classesBySource.clear();
    fresh.clear();
    diagnosticsBySource.clear();
    lastClasspath = null;
    lastLocale = null;
  }

  /**
   * Compiles {@code sources} (with their current {@code texts}) against
   * {@code classpath}; diagnostics in {@code locale} (ROOT = English).
   */
  public CompileResult compile(Map<Path, String> texts, List<Path> classpath, Locale locale)
      throws IOException {
    Set<Path> sources = new LinkedHashSet<>(texts.keySet());
    boolean teachingTests = texts.values().stream().anyMatch(TeachingTests::hasTests);
    List<Path> fullClasspath = new ArrayList<>();
    fullClasspath.add(outDir);
    fullClasspath.addAll(classpath);
    if (teachingTests) {
      fullClasspath.add(TeachingTests.junitJar());
    }
    boolean full = lastClasspath == null || !lastClasspath.equals(fullClasspath)
        || !Objects.equals(lastLocale, locale) || !Files.isDirectory(outDir);
    if (full) {
      reset();
      deleteTree(outDir);
    }
    Files.createDirectories(outDir);
    lastClasspath = fullClasspath;
    lastLocale = locale;

    // removed sources: their classes go, and whoever named them is compiled again
    Set<String> seeds = new HashSet<>();
    for (Path gone : new ArrayList<>(compiledText.keySet())) {
      if (!sources.contains(gone)) {
        Scan scan = scans.remove(gone);
        if (scan != null) {
          seeds.addAll(scan.declared());
        }
        deleteClasses(gone);
        compiledText.remove(gone);
        fresh.remove(gone);
        diagnosticsBySource.remove(gone);
      }
    }
    Map<Path, Scan> current = new LinkedHashMap<>();
    for (Path source : sources) {
      current.put(source, scan(source, texts.get(source)));
    }
    Set<Path> toCompile = new LinkedHashSet<>();
    for (Path source : sources) {
      if (!fresh.contains(source) || !texts.get(source).equals(compiledText.get(source))) {
        toCompile.add(source);
      }
    }
    if (toCompile.isEmpty() && seeds.isEmpty()) {
      stats = new Stats(false, 0, sources.size());
      return result();
    }
    // everything that names a class of a file being compiled, transitively
    Deque<String> names = new ArrayDeque<>(seeds);
    for (Path source : toCompile) {
      names.addAll(current.get(source).declared());
    }
    Set<String> seen = new HashSet<>();
    while (!names.isEmpty()) {
      String name = names.pop();
      if (!seen.add(name)) {
        continue;
      }
      for (Map.Entry<Path, Scan> e : current.entrySet()) {
        if (!toCompile.contains(e.getKey()) && e.getValue().identifiers().contains(name)) {
          toCompile.add(e.getKey());
          names.addAll(e.getValue().declared());
        }
      }
    }
    if (toCompile.isEmpty()) {
      stats = new Stats(false, 0, sources.size());
      return result();
    }

    for (Path source : toCompile) {
      deleteClasses(source);
      fresh.remove(source);
      diagnosticsBySource.remove(source);
    }
    diagnosticsBySource.remove(null);
    Map<Path, Set<Path>> written = new HashMap<>();
    List<CompilerDiagnostic> diagnostics = run(toCompile, texts, fullClasspath, locale, written);
    for (Path source : toCompile) {
      compiledText.put(source, texts.get(source));
      Set<Path> classes = written.getOrDefault(source, Set.of());
      classesBySource.put(source, classes);
      if (!classes.isEmpty()) {
        fresh.add(source);
      }
      diagnosticsBySource.put(source, new ArrayList<>());
    }
    Map<String, Path> byFileName = new HashMap<>();
    for (Path source : sources) {
      byFileName.putIfAbsent(source.getFileName().toString(), source);
    }
    for (CompilerDiagnostic d : diagnostics) {
      Path file = d.path().isEmpty() ? null : resolve(d.path(), sources, byFileName);
      diagnosticsBySource.computeIfAbsent(file, f -> new ArrayList<>()).add(d);
      if (file != null && d.isError()) {
        // a class file next to an error would hide the error from the next compile
        fresh.remove(file);
      }
    }
    stats = new Stats(full, toCompile.size(), sources.size());
    return result();
  }

  /** All diagnostics in source order (null-file ones first), as one result. */
  private CompileResult result() {
    List<CompilerDiagnostic> all = new ArrayList<>(diagnosticsBySource.getOrDefault(null,
        List.of()));
    diagnosticsBySource.keySet().stream().filter(Objects::nonNull)
        .sorted(java.util.Comparator.comparing(Path::toString))
        .forEach(p -> all.addAll(diagnosticsBySource.get(p)));
    boolean success = all.stream().noneMatch(CompilerDiagnostic::isError);
    return new CompileResult(success, List.copyOf(all));
  }

  private List<CompilerDiagnostic> run(Collection<Path> toCompile, Map<Path, String> texts,
      List<Path> classpath, Locale locale, Map<Path, Set<Path>> written) throws IOException {
    if (compiler == null) {
      compiler = ToolProvider.getSystemJavaCompiler();
      if (compiler == null) {
        throw new IllegalStateException("No system java compiler; is jdk.compiler present?");
      }
    }
    DiagnosticCollector<FileObject> collector = new DiagnosticCollector<>();
    if (files == null || !fileManagerClasspath.equals(classpath)) {
      if (files != null) {
        files.close();
      }
      files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
      files.setLocation(StandardLocation.CLASS_PATH,
          classpath.stream().map(Path::toFile).toList());
      // only the sources handed in: a class outside them comes from a class file
      files.setLocation(StandardLocation.SOURCE_PATH, List.of());
      fileManagerClasspath = classpath;
    }
    files.flush(); // the output directory changed since the last compile
    List<JavaFileObject> units = new ArrayList<>();
    for (Path source : toCompile) {
      String code = TeachingTests.adapt(texts.get(source));
      units.add(new javax.tools.SimpleJavaFileObject(source.toAbsolutePath().toUri(),
          JavaFileObject.Kind.SOURCE) {
        @Override public CharSequence getCharContent(boolean ignoreErrors) {
          return code;
        }
      });
    }
    Map<String, Path> byUri = new HashMap<>();
    for (Path source : toCompile) {
      byUri.put(source.toAbsolutePath().toUri().toString(), source);
    }
    // which class files each source produced (inner and anonymous classes included)
    var tracking = new ForwardingJavaFileManager<StandardJavaFileManager>(files) {
      @Override
      public JavaFileObject getJavaFileForOutput(Location location, String className,
          JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        JavaFileObject out = super.getJavaFileForOutput(location, className, kind, sibling);
        Path source = sibling == null ? null : byUri.get(sibling.toUri().toString());
        if (source != null && kind == JavaFileObject.Kind.CLASS) {
          written.computeIfAbsent(source, s -> new HashSet<>()).add(Path.of(out.toUri()));
        }
        return out;
      }
    };
    compiler.getTask(null, tracking, collector,
        // -g: line numbers and local variable names for the debugger
        List.of("-g", "-encoding", "UTF-8", "-implicit:none", "-d", outDir.toString()),
        null, units).call();
    List<CompilerDiagnostic> mapped = new ArrayList<>();
    for (var d : collector.getDiagnostics()) {
      mapped.add(CompilerDiagnostic.of(d, locale));
    }
    return mapped;
  }

  private Scan scan(Path source, String text) {
    Scan scan = scans.get(source);
    if (scan != null && scan.text().equals(text)) {
      return scan;
    }
    Set<String> identifiers = new HashSet<>();
    Matcher m = IDENTIFIER.matcher(text);
    while (m.find()) {
      identifiers.add(m.group());
    }
    Set<String> declared = new HashSet<>();
    String name = source.getFileName().toString();
    if (name.endsWith(".java")) {
      declared.add(name.substring(0, name.length() - ".java".length()));
    }
    m = DECLARATION.matcher(text);
    while (m.find()) {
      declared.add(m.group(1));
    }
    scan = new Scan(text, identifiers, declared);
    scans.put(source, scan);
    return scan;
  }

  private static Path resolve(String path, Set<Path> sources, Map<String, Path> byFileName) {
    try {
      Path p = Path.of(path).toAbsolutePath();
      for (Path source : sources) {
        if (source.toAbsolutePath().equals(p)) {
          return source;
        }
      }
      return byFileName.get(p.getFileName().toString());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void deleteClasses(Path source) throws IOException {
    for (Path cls : classesBySource.getOrDefault(source, Set.of())) {
      Files.deleteIfExists(cls);
    }
    classesBySource.remove(source);
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
