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

/** Rename and recoverable delete for files under a project's assets folder. */
public final class ProjectAssetManagement {

  private record Span(int start, int end) {}
  private record Scan(Map<Path, String> original, Map<Path, List<Span>> literals,
                      List<String> unsafe) {}

  private ProjectAssetManagement() {}

  public static Path rename(ScratchProject project, Path asset, String newBaseName)
      throws IOException {
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
    String oldRef = reference(project, source);
    String newRef = reference(project, target);
    Scan scan = scan(project, oldRef, source);
    if (!scan.unsafe().isEmpty()) {
      throw new IOException("Check references before renaming: " + scan.unsafe().get(0));
    }
    Map<Path, String> changes = new HashMap<>();
    for (Map.Entry<Path, List<Span>> entry : scan.literals().entrySet()) {
      StringBuilder code = new StringBuilder(scan.original().get(entry.getKey()));
      entry.getValue().stream().sorted(Comparator.comparingInt(Span::start).reversed())
          .forEach(span -> code.replace(span.start(), span.end(), "\"" + newRef + "\""));
      changes.put(entry.getKey(), code.toString());
    }
    String oldSplash = project.settings().splashLogo;
    List<Path> written = new ArrayList<>();
    boolean moved = false;
    try {
      for (Map.Entry<Path, String> entry : changes.entrySet()) {
        LocalHistory.writeString(project.root(), entry.getKey(), entry.getValue());
        written.add(entry.getKey());
      }
      Files.move(source, target);
      moved = true;
      if (oldRef.equals(oldSplash)) {
        project.settings().splashLogo = newRef;
        project.settings().save(project.root());
      }
      return target;
    } catch (IOException | RuntimeException failure) {
      if (moved) {
        try {
          Files.move(target, source);
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      for (Path file : written) {
        try {
          AtomicFiles.writeString(file, scan.original().get(file));
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      project.settings().splashLogo = oldSplash;
      if (oldRef.equals(oldSplash)) {
        try {
          project.settings().save(project.root());
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      if (failure instanceof IOException io) throw io;
      throw new IOException("Could not rename asset", failure);
    }
  }

  /** Moves an unreferenced asset to IDE trash; returns the recovery path. */
  public static Path delete(ScratchProject project, Path asset) throws IOException {
    Path source = validate(project, asset);
    String ref = reference(project, source);
    if (ref.equals(project.settings().splashLogo)) {
      throw new IOException("The asset is used as the project splash image");
    }
    Scan scan = scan(project, ref, source);
    if (!scan.literals().isEmpty() || !scan.unsafe().isEmpty()) {
      String first = !scan.literals().isEmpty()
          ? reference(project, scan.literals().keySet().iterator().next())
          : scan.unsafe().get(0);
      throw new IOException("The asset is still referenced in " + first);
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

  private static Scan scan(ScratchProject project, String ref, Path asset) throws IOException {
    Map<Path, String> original = new HashMap<>();
    Map<Path, List<Span>> literals = new HashMap<>();
    List<String> unsafe = new ArrayList<>();
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
          unsafe.add(reference(project, file));
          break;
        }
      }
    }
    // Other text assets can contain references too; flag these instead of rewriting syntax
    // the IDE does not understand. Generated/build and IDE metadata are excluded.
    try (Stream<Path> files = Files.walk(project.root().resolve("assets"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        if (file.equals(asset) || Files.size(file) > 2_000_000) continue;
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.matches(".*\\.(json|xml|tmx|frag|vert|glsl|txt|css|html)$")
            && Files.readString(file).contains(ref)) {
          unsafe.add(reference(project, file));
        }
      }
    }
    return new Scan(original, literals, unsafe);
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
