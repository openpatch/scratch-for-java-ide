package org.openpatch.scratch4j.core.project;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.core.io.LocalHistory;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Rename and recoverable delete for files under a project's assets folder,
 * and the reference finding and rewriting behind every file and folder move.
 */
public final class ProjectAssetManagement {

  private record Span(int start, int end) {}

  /**
   * What a scan found: the Java strings that are exactly the path (updatable)
   * and every other usage (by hand).
   */
  private record Scan(Map<Path, String> original, Map<Path, List<Span>> literals,
                      List<FileUsages.Usage> manual) {}

  private ProjectAssetManagement() {}

  public static Path rename(ScratchProject project, Path asset, String newBaseName)
      throws IOException {
    return rename(project, asset, newBaseName, FileUsages.Mode.STRICT);
  }

  public static Path rename(ScratchProject project, Path asset, String newBaseName,
      FileUsages.Mode mode) throws IOException {
    Path source = validate(project, asset);
    if (!newBaseName.matches("[A-Za-z0-9_][A-Za-z0-9_ -]*")) {
      throw new IOException("Use letters, digits, spaces, hyphens or underscores");
    }
    String oldFile = source.getFileName().toString();
    int dot = oldFile.lastIndexOf('.');
    String extension = dot < 0 ? "" : oldFile.substring(dot);
    Path target = source.resolveSibling(newBaseName + extension);
    if (source.equals(target) || Files.exists(target)) {
      throw new IOException("Choose an unused asset name");
    }
    return relocate(project, source, target, mode);
  }

  /**
   * Every usage of a (non-Java) file or of the files in a folder: Java strings
   * that are exactly a path (updatable), the splash image (updatable), and
   * mentions the IDE cannot rewrite (a path inside a longer string, code that
   * builds paths from the folder, shaders, data files, maps).
   */
  static List<FileUsages.Usage> usages(ScratchProject project, Path path) throws IOException {
    Path source = path.toRealPath();
    List<FileUsages.Usage> usages = new ArrayList<>();
    Map<Path, List<Span>> literals = new HashMap<>();
    for (Path file : files(source)) {
      Scan scan = scan(project, reference(project, file), file, source);
      for (Map.Entry<Path, List<Span>> entry : scan.literals().entrySet()) {
        literals.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
            .addAll(entry.getValue());
        String text = scan.original().get(entry.getKey());
        for (Span span : entry.getValue()) {
          // the path without its quotes
          usages.add(FileUsages.at(entry.getKey(), text, span.start() + 1, span.end() - 1,
              true));
        }
      }
      usages.addAll(scan.manual());
      String splash = project.settings().splashLogo;
      if (reference(project, file).equals(splash)) {
        usages.add(new FileUsages.Usage(project.root().resolve(".scratch4j/project.json"),
            0, 0, 0, "splash image: " + splash, 14, 14 + splash.length(), true));
      }
    }
    if (Files.isDirectory(source)) {
      usages.addAll(folderPrefix(project, reference(project, source), literals));
    }
    return distinct(usages);
  }

  private static List<FileUsages.Usage> distinct(List<FileUsages.Usage> usages) {
    Map<String, FileUsages.Usage> seen = new java.util.LinkedHashMap<>();
    for (FileUsages.Usage usage : usages) {
      seen.putIfAbsent(usage.file() + ":" + usage.start() + ":" + usage.updatable(), usage);
    }
    return List.copyOf(seen.values());
  }

  private static List<Path> files(Path source) throws IOException {
    if (!Files.isDirectory(source)) {
      return List.of(source);
    }
    try (Stream<Path> walk = Files.walk(source)) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    }
  }

  /** Moves or renames with the usual safety: refuses usages it cannot update. */
  static Path relocate(ScratchProject project, Path source, Path target) throws IOException {
    return relocate(project, source, target, FileUsages.Mode.STRICT);
  }

  /**
   * Moves or renames a (non-Java) project file or a folder to {@code target}.
   * With {@link FileUsages.Mode#UPDATE} or {@code STRICT}, every Java string
   * that is exactly a moved file's project path ({@code "assets/images/cat.png"})
   * and the splash image follow the move; {@code STRICT} refuses when other
   * usages exist. {@code IGNORE} only moves.
   */
  static Path relocate(ScratchProject project, Path source, Path target, FileUsages.Mode mode)
      throws IOException {
    boolean folder = Files.isDirectory(source);
    if (folder) {
      for (Path file : files(source)) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if ((name.endsWith(".tmx") || name.endsWith(".tsx"))
            && Files.readString(file).contains("../")) {
          throw new IOException(reference(project, file)
              + " uses files outside the folder; move them together in Tiled");
        }
      }
    }
    if (mode == FileUsages.Mode.IGNORE) {
      return Files.move(source, target);
    }
    Map<Path, String> original = new HashMap<>();
    Map<Path, List<Span>> spans = new HashMap<>();
    Map<Span, String> replacements = new HashMap<>();
    Map<String, String> refs = new HashMap<>();
    List<FileUsages.Usage> manual = new ArrayList<>();
    for (Path file : files(source)) {
      String oldRef = reference(project, file);
      String newRef = reference(project, folder ? target.resolve(source.relativize(file))
          : target);
      refs.put(oldRef, newRef);
      Scan scan = scan(project, oldRef, file, source);
      manual.addAll(scan.manual());
      original.putAll(scan.original());
      for (Map.Entry<Path, List<Span>> entry : scan.literals().entrySet()) {
        spans.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
            .addAll(entry.getValue());
        entry.getValue().forEach(span -> replacements.put(span, "\"" + newRef + "\""));
      }
    }
    if (folder) {
      manual.addAll(folderPrefix(project, reference(project, source), spans));
    }
    if (mode == FileUsages.Mode.STRICT && !manual.isEmpty()) {
      FileUsages.Usage first = manual.get(0);
      throw new IOException("Check references before moving: " + reference(project,
          first.file()) + (first.line() > 0 ? ":" + first.line() : ""));
    }
    Map<Path, String> changes = new HashMap<>();
    for (Map.Entry<Path, List<Span>> entry : spans.entrySet()) {
      StringBuilder code = new StringBuilder(original.get(entry.getKey()));
      entry.getValue().stream().distinct()
          .sorted(Comparator.comparingInt(Span::start).reversed())
          .forEach(span -> code.replace(span.start(), span.end(), replacements.get(span)));
      changes.put(entry.getKey(), code.toString());
    }
    String oldSplash = project.settings().splashLogo;
    String newSplash = oldSplash == null ? null : refs.get(oldSplash);
    List<Path> written = new ArrayList<>();
    boolean done = false;
    try {
      for (Map.Entry<Path, String> entry : changes.entrySet()) {
        LocalHistory.writeString(project.root(), entry.getKey(), entry.getValue());
        written.add(entry.getKey());
      }
      Files.move(source, target);
      done = true;
      if (newSplash != null) {
        project.settings().splashLogo = newSplash;
        project.settings().save(project.root());
      }
      return target;
    } catch (IOException | RuntimeException failure) {
      if (done) {
        try {
          Files.move(target, source);
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      for (Path file : written) {
        try {
          AtomicFiles.writeString(file, original.get(file));
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      project.settings().splashLogo = oldSplash;
      if (newSplash != null) {
        try {
          project.settings().save(project.root());
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      if (failure instanceof IOException io) throw io;
      throw new IOException("Could not move " + source.getFileName(), failure);
    }
  }

  /**
   * Code that builds paths from the folder ({@code "assets/images/" + name})
   * breaks when the folder moves; only whole file paths are rewritten.
   */
  private static List<FileUsages.Usage> folderPrefix(ScratchProject project, String prefix,
      Map<Path, List<Span>> rewritten) throws IOException {
    List<FileUsages.Usage> found = new ArrayList<>();
    for (Path file : project.javaSources()) {
      String text = Files.readString(file);
      for (int at = text.indexOf(prefix + "/"); at >= 0;
           at = text.indexOf(prefix + "/", at + 1)) {
        int index = at;
        boolean covered = rewritten.getOrDefault(file, List.of()).stream()
            .anyMatch(span -> index > span.start() && index < span.end());
        if (!covered) {
          found.add(FileUsages.at(file, text, at, at + prefix.length(), false));
        }
      }
      for (int at = text.indexOf("\"" + prefix + "\""); at >= 0;
           at = text.indexOf("\"" + prefix + "\"", at + 1)) {
        found.add(FileUsages.at(file, text, at + 1, at + 1 + prefix.length(), false));
      }
    }
    return found;
  }

  /** Moves an unreferenced asset to IDE trash; returns the recovery path. */
  public static Path delete(ScratchProject project, Path asset) throws IOException {
    return delete(project, asset, false);
  }

  /**
   * Moves an asset to IDE trash; returns the recovery path. Without
   * {@code force} a used asset stays; with it, the code that uses it is left
   * as it is (the problems list shows what broke).
   */
  public static Path delete(ScratchProject project, Path asset, boolean force)
      throws IOException {
    Path source = validate(project, asset);
    String ref = reference(project, source);
    if (!force) {
      if (ref.equals(project.settings().splashLogo)) {
        throw new IOException("The asset is used as the project splash image");
      }
      Scan scan = scan(project, ref, source, source);
      if (!scan.literals().isEmpty() || !scan.manual().isEmpty()) {
        String first = !scan.literals().isEmpty()
            ? reference(project, scan.literals().keySet().iterator().next())
            : reference(project, scan.manual().get(0).file());
        throw new IOException("The asset is still referenced in " + first);
      }
    } else if (ref.equals(project.settings().splashLogo)) {
      project.settings().splashLogo = null;
      project.settings().save(project.root());
    }
    Path trash = project.root().resolve(".scratch4j/trash");
    Files.createDirectories(trash);
    Path target = trash.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
        + "-" + source.getFileName());
    return Files.move(source, target);
  }

  private static Path validate(ScratchProject project, Path asset) throws IOException {
    Path root = project.root().toRealPath();
    Path source = asset.toRealPath();
    if (!source.startsWith(root.resolve("assets")) || !Files.isRegularFile(source)) {
      throw new IOException("Choose a file inside this project's assets folder");
    }
    return source;
  }

  private static String reference(ScratchProject project, Path path) throws IOException {
    return project.root().toRealPath().relativize(path.toAbsolutePath().normalize()).toString()
        .replace('\\', '/');
  }

  /** {@code moving}: the file or folder that moves (its own text files are not checked). */
  private static Scan scan(ScratchProject project, String ref, Path asset, Path moving)
      throws IOException {
    Map<Path, String> original = new HashMap<>();
    Map<Path, List<Span>> literals = new HashMap<>();
    List<FileUsages.Usage> manual = new ArrayList<>();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IOException("The Java compiler is unavailable");
    for (Path file : project.javaSources()) {
      String source = Files.readString(file);
      if (!source.contains(ref)) continue;
      original.put(file, source);
      JavaFile sourceFile = new JavaFile(file, source);
      JavacTask task = (JavacTask) compiler.getTask(null, null, null,
          List.of("-proc:none"), null, List.of(sourceFile));
      try {
        for (CompilationUnitTree unit : task.parse()) {
          SourcePositions positions = Trees.instance(task).getSourcePositions();
          new TreeScanner<Void, Void>() {
            @Override public Void visitLiteral(LiteralTree node, Void unused) {
              if (ref.equals(node.getValue())) {
                int start = (int) positions.getStartPosition(unit, node);
                int end = (int) positions.getEndPosition(unit, node);
                if (start >= 0 && end > start) {
                  literals.computeIfAbsent(file, key -> new ArrayList<>())
                      .add(new Span(start, end));
                }
              }
              return super.visitLiteral(node, unused);
            }
          }.scan(unit, null);
        }
      } catch (IOException e) {
        throw new IOException("Could not inspect Java references in " + file, e);
      }
      for (int pos = source.indexOf(ref); pos >= 0; pos = source.indexOf(ref, pos + ref.length())) {
        int index = pos;
        boolean covered = literals.getOrDefault(file, List.of()).stream()
            .anyMatch(span -> index >= span.start() && index + ref.length() < span.end());
        if (!covered) {
          manual.add(FileUsages.at(file, source, pos, pos + ref.length(), false));
        }
      }
    }
    // Other text files can contain references too; flag these instead of rewriting syntax
    // the IDE does not understand. Generated/build and IDE metadata are excluded.
    // Maps name their tilesets relative to themselves, so a bare file name counts.
    String fileName = asset.getFileName().toString();
    try (Stream<Path> files = Files.walk(project.root().toRealPath())) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (file.startsWith(moving) || ignored(project, file) || Files.size(file) > 2_000_000) {
          continue;
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String needle = name.matches(".*\\.(json|xml|frag|vert|glsl|txt|css|html)$") ? ref
            : name.matches(".*\\.(tmx|tsx)$") ? fileName : null;
        if (needle == null) {
          continue;
        }
        String text = Files.readString(file);
        int at = text.indexOf(needle);
        if (at >= 0) {
          manual.add(FileUsages.at(file, text, at, at + needle.length(), false));
        }
      }
    }
    return new Scan(original, literals, manual);
  }

  /** IDE metadata, build output and the library: never scanned for references. */
  private static boolean ignored(ScratchProject project, Path file) throws IOException {
    Path relative = project.root().toRealPath().relativize(file);
    String first = relative.getName(0).toString();
    return relative.getNameCount() > 1 && java.util.Set.of(".scratch4j", ".git", "target",
        "build", "+libs", "export", ".vscode").contains(first);
  }

  private static final class JavaFile extends SimpleJavaFileObject {
    private final String source;

    JavaFile(Path file, String source) {
      super(URI.create("string:///" + file.getFileName()), Kind.SOURCE);
      this.source = source;
    }

    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
