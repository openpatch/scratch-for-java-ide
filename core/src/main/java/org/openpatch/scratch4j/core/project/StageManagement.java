package org.openpatch.scratch4j.core.project;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.core.io.LocalHistory;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stage file operations with compiler-resolved Java reference updates. */
public final class StageManagement {

  private StageManagement() {}

  /** Duplicate a stage and its self references; other stages continue to refer to the original. */
  public static Path duplicate(ScratchProject project, String oldName, String newName)
      throws IOException {
    if (!project.stageClasses().contains(oldName)) {
      throw new IOException("Not a stage: " + oldName);
    }
    Plan plan = plan(project, oldName, newName);
    Path target = project.root().resolve(newName + ".java");
    AtomicFiles.writeString(target, plan.updated().get(plan.source()));
    return target;
  }

  /**
   * Duplicate any top-level project class: {@code NewName.java} with the
   * class, its constructors and its references to itself renamed. Other files
   * keep using the original.
   */
  public static Path duplicateClass(ScratchProject project, String oldName, String newName)
      throws IOException {
    Plan plan = plan(project, oldName, newName);
    Path target = plan.source().resolveSibling(newName + ".java");
    AtomicFiles.writeString(target, plan.updated().get(plan.source()));
    return target;
  }

  /** Rename a stage and every resolved Java type reference to it. */
  public static Path rename(ScratchProject project, String oldName, String newName)
      throws IOException {
    if (!project.stageClasses().contains(oldName)) {
      throw new IOException("Not a stage: " + oldName);
    }
    return renameClass(project, oldName, newName);
  }

  /**
   * Rename any top-level project class declared in {@code OldName.java} (a
   * sprite, a window, a helper): the file moves to {@code NewName.java} and
   * every compiler-resolved reference — declarations, constructors,
   * {@code new}, types, static calls — changes with it. The project must
   * compile, so references are exact; the previous versions go to local history.
   */
  public static Path renameClass(ScratchProject project, String oldName, String newName)
      throws IOException {
    Plan plan = plan(project, oldName, newName);
    Path source = plan.source();
    Path target = source.resolveSibling(newName + ".java");
    Path trashed = null;
    List<Path> changed = new ArrayList<>();
    String oldStart = project.settings().startStage;
    try {
      for (Map.Entry<Path, String> entry : plan.updated().entrySet()) {
        if (!entry.getKey().equals(source)) {
          LocalHistory.writeString(project.root(), entry.getKey(), entry.getValue());
          changed.add(entry.getKey());
        }
      }
      AtomicFiles.writeString(target, plan.updated().get(source));
      trashed = trash(project, source);
      if (oldName.equals(oldStart)) {
        project.settings().startStage = newName;
        project.settings().save(project.root());
      }
      moveHistory(project, source, target);
      return target;
    } catch (IOException | RuntimeException failure) {
      if (trashed != null) {
        try {
          Files.move(trashed, source);
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      if (Files.exists(target)) {
        try {
          trash(project, target);
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      for (Path file : changed) {
        try {
          AtomicFiles.writeString(file, plan.original().get(file));
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      project.settings().startStage = oldStart;
      if (oldName.equals(oldStart)) {
        try {
          project.settings().save(project.root());
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
      }
      if (failure instanceof IOException io) {
        throw io;
      }
      throw new IOException("Could not rename class", failure);
    }
  }

  /** Move an unreferenced stage into IDE trash so it remains recoverable. */
  public static Path delete(ScratchProject project, String name) throws IOException {
    return delete(project, name, false);
  }

  /**
   * Move a stage into IDE trash. Without {@code force} a stage other classes
   * still use stays; with it they are left as they are (the problems list
   * shows what broke).
   */
  public static Path delete(ScratchProject project, String name, boolean force)
      throws IOException {
    if (!project.stageClasses().contains(name)) {
      throw new IOException("Not a stage: " + name);
    }
    Path source;
    if (force) {
      source = project.javaSources().stream()
          .filter(path -> path.getFileName().toString().equals(name + ".java"))
          .findFirst().orElseThrow(() -> new IOException("No source file " + name + ".java"));
    } else {
      Plan plan = plan(project, name, unusedName(project, name));
      if (plan.updated().keySet().stream().anyMatch(path -> !path.equals(plan.source()))) {
        throw new IOException("Other Java files still refer to stage " + name);
      }
      source = plan.source();
    }
    Path trashed = trash(project, source);
    if (name.equals(project.settings().startStage)) {
      project.settings().startStage = "";
      try {
        project.settings().save(project.root());
      } catch (IOException failure) {
        project.settings().startStage = name;
        try {
          project.settings().save(project.root());
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
        try {
          Files.move(trashed, source);
        } catch (IOException rollback) {
          failure.addSuppressed(rollback);
        }
        throw failure;
      }
    }
    return trashed;
  }

  private static String unusedName(ScratchProject project, String name) throws IOException {
    String candidate = name + "Deleted";
    Set<String> names = new HashSet<>();
    for (Path file : project.javaSources()) {
      names.add(file.getFileName().toString());
    }
    while (names.contains(candidate + ".java")) {
      candidate += "X";
    }
    return candidate;
  }

  private static Path trash(ScratchProject project, Path file) throws IOException {
    Path folder = project.root().resolve(".scratch4j/trash");
    Files.createDirectories(folder);
    Path target = folder.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
        + "-" + file.getFileName());
    return Files.move(file, target);
  }

  private static void moveHistory(ScratchProject project, Path source, Path target)
      throws IOException {
    Path base = project.root().resolve(".scratch4j/history");
    Path from = base.resolve(project.root().relativize(source));
    Path to = base.resolve(project.root().relativize(target));
    if (Files.isDirectory(from) && !Files.exists(to)) {
      Files.createDirectories(to.getParent());
      Files.move(from, to);
    }
  }

  private record Span(int start, int end) {}
  private record Plan(Path source, Map<Path, String> original, Map<Path, String> updated) {}

  private static Plan plan(ScratchProject project, String oldName, String newName)
      throws IOException {
    checkName(newName);
    if (oldName.equals(newName)) {
      throw new IOException("Choose a different class name");
    }
    List<Path> sources = project.javaSources();
    Path source = sources.stream()
        .filter(path -> path.getFileName().toString().equals(oldName + ".java"))
        .findFirst().orElse(null);
    if (source == null) {
      throw new IOException("No source file " + oldName + ".java in the project");
    }
    Path target = source.resolveSibling(newName + ".java");
    if (Files.exists(target) || sources.stream()
        .anyMatch(path -> path.getFileName().toString().equals(newName + ".java"))) {
      throw new IOException("Class already exists: " + newName);
    }
    Map<Path, String> original = new HashMap<>();
    for (Path path : sources) {
      original.put(path, Files.readString(path, StandardCharsets.UTF_8));
    }
    Map<Path, List<Span>> positions = referencePositions(project, source, oldName, original);
    if (!positions.containsKey(source) || positions.get(source).isEmpty()) {
      throw new IOException("Could not resolve the declaration of " + oldName);
    }
    Map<Path, String> updated = new HashMap<>();
    for (Map.Entry<Path, List<Span>> entry : positions.entrySet()) {
      StringBuilder text = new StringBuilder(original.get(entry.getKey()));
      entry.getValue().stream().distinct()
          .sorted(Comparator.comparingInt(Span::start).reversed())
          .forEach(span -> text.replace(span.start(), span.end(), newName));
      updated.put(entry.getKey(), text.toString());
    }
    return new Plan(source, original, updated);
  }

  private static void checkName(String name) throws IOException {
    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || SourceVersion.isKeyword(name)) {
      throw new IOException("Not a valid Java class name: " + name);
    }
  }

  private static Map<Path, List<Span>> referencePositions(ScratchProject project,
      Path source, String oldName, Map<Path, String> originals) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IOException("The bundled Java compiler is unavailable");
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null,
        StandardCharsets.UTF_8)) {
      if (!project.libs().isEmpty()) {
        files.setLocation(StandardLocation.CLASS_PATH,
            project.libs().stream().map(Path::toFile).toList());
      }
      JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
          List.of("-proc:none", "-encoding", "UTF-8"), null,
          files.getJavaFileObjectsFromPaths(originals.keySet()));
      List<CompilationUnitTree> units = new ArrayList<>();
      task.parse().forEach(units::add);
      task.analyze();
      if (diagnostics.getDiagnostics().stream()
          .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
        throw new IOException("Fix Java errors before renaming a class");
      }
      Trees trees = Trees.instance(task);
      SourcePositions positions = trees.getSourcePositions();
      TypeElement target = null;
      for (CompilationUnitTree unit : units) {
        if (!Path.of(unit.getSourceFile().toUri()).equals(source)) {
          continue;
        }
        final TypeElement[] found = {null};
        new TreePathScanner<Void, Void>() {
          @Override public Void visitClass(ClassTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (node.getSimpleName().contentEquals(oldName)
                && element instanceof TypeElement type) {
              found[0] = type;
            }
            return super.visitClass(node, unused);
          }
        }.scan(unit, null);
        target = found[0];
      }
      if (target == null) {
        throw new IOException("Could not resolve class " + oldName);
      }
      TypeElement stage = target;
      Map<Path, List<Span>> found = new HashMap<>();
      for (CompilationUnitTree unit : units) {
        Path file = Path.of(unit.getSourceFile().toUri());
        String text = originals.get(file);
        List<Span> spans = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
          @Override public Void visitClass(ClassTree node, Void unused) {
            if (stage.equals(trees.getElement(getCurrentPath()))) {
              int start = (int) positions.getStartPosition(unit, node);
              // up to the body's brace: a synthetic default constructor is the
              // first member and sits at the class start, so it cannot bound the header
              int brace = text.indexOf('{', Math.max(start, 0));
              int end = brace < 0 ? text.length() : brace;
              addDeclaration(text, start, end, "class", oldName, spans);
            }
            return super.visitClass(node, unused);
          }

          @Override public Void visitMethod(MethodTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null && element.getKind() == ElementKind.CONSTRUCTOR
                && stage.equals(element.getEnclosingElement())) {
              int start = (int) positions.getStartPosition(unit, node);
              int end = node.getBody() == null ? (int) positions.getEndPosition(unit, node)
                  : (int) positions.getStartPosition(unit, node.getBody());
              addDeclaration(text, start, end, "", oldName, spans);
            }
            return super.visitMethod(node, unused);
          }

          @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
            if (stage.equals(trees.getElement(getCurrentPath()))) {
              int start = (int) positions.getStartPosition(unit, node);
              spans.add(new Span(start, start + oldName.length()));
            }
            return super.visitIdentifier(node, unused);
          }

          @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            if (stage.equals(trees.getElement(getCurrentPath()))) {
              int end = (int) positions.getEndPosition(unit, node);
              spans.add(new Span(end - oldName.length(), end));
            }
            return super.visitMemberSelect(node, unused);
          }
        }.scan(unit, null);
        for (Span span : spans) {
          if (span.start() < 0 || span.end() > text.length()
              || !text.substring(span.start(), span.end()).equals(oldName)) {
            throw new IOException("Could not locate all references in " + file);
          }
        }
        if (!spans.isEmpty()) {
          found.put(file, spans);
        }
      }
      return found;
    }
  }

  private static void addDeclaration(String text, int start, int end, String keyword,
      String name, List<Span> spans) {
    if (start < 0 || end < start || end > text.length()) {
      return;
    }
    String pattern = keyword.isEmpty() ? "\\b" + Pattern.quote(name) + "\\s*\\("
        : "\\b" + keyword + "\\s+" + Pattern.quote(name) + "\\b";
    Matcher match = Pattern.compile(pattern).matcher(text.substring(start, end));
    if (match.find()) {
      int at = start + match.start() + (keyword.isEmpty() ? 0 :
          match.group().lastIndexOf(name));
      spans.add(new Span(at, at + name.length()));
    }
  }
}
