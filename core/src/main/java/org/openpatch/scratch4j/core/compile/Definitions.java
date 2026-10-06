package org.openpatch.scratch4j.core.compile;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * "Go to definition": resolves the name at an offset with javac (parse +
 * attribute, errors tolerated) and returns where the project declares it — a
 * class, method, constructor, field, parameter or local variable. Names from
 * the library resolve to their qualified owner instead, so the IDE can show
 * the API help for them.
 */
public final class Definitions {

  /** A declaration in a project file: 1-based line and column. */
  public record Location(Path file, long line, long column) {}

  /**
   * The result: a project location, or for a library element its owning type
   * and member name ({@code org.openpatch.scratch.Sprite} / {@code move}).
   */
  public record Target(Optional<Location> location, String libraryType, String libraryMember) {
    static Target at(Location location) {
      return new Target(Optional.of(location), null, null);
    }

    static Target library(String type, String member) {
      return new Target(Optional.empty(), type, member);
    }
  }

  private Definitions() {}

  /**
   * Resolves the identifier at {@code offset} in {@code file}, whose current
   * (possibly unsaved) text is {@code text}. {@code sources} are the project's
   * other files (read from disk), {@code classpath} the library jars.
   */
  public static Optional<Target> find(Path file, String text, int offset, List<Path> sources,
      List<Path> classpath) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return Optional.empty();
    }
    try (StandardJavaFileManager files = compiler.getStandardFileManager(d -> { }, null,
        StandardCharsets.UTF_8)) {
      if (!classpath.isEmpty()) {
        files.setLocation(StandardLocation.CLASS_PATH,
            classpath.stream().map(Path::toFile).collect(Collectors.toList()));
      }
      Path current = file.toAbsolutePath().normalize();
      List<JavaFileObject> units = new ArrayList<>();
      Map<URI, Path> paths = new java.util.HashMap<>();
      for (Path source : sources) {
        Path normalized = source.toAbsolutePath().normalize();
        if (normalized.equals(current)) {
          continue;
        }
        JavaFileObject object = new Source(normalized,
            Files.readString(normalized, StandardCharsets.UTF_8));
        units.add(object);
        paths.put(object.toUri(), normalized);
      }
      Source edited = new Source(current, text);
      units.add(edited);
      paths.put(edited.toUri(), current);
      JavacTask task = (JavacTask) compiler.getTask(null, files, d -> { },
          List.of("-proc:none", "-encoding", "UTF-8"), null, units);
      List<CompilationUnitTree> trees = new ArrayList<>();
      task.parse().forEach(trees::add);
      try {
        task.analyze();
      } catch (RuntimeException ignored) {
        // attribution is best effort: broken code still resolves what it can
      }
      Trees treeUtil = Trees.instance(task);
      SourcePositions positions = treeUtil.getSourcePositions();
      CompilationUnitTree unit = trees.stream()
          .filter(t -> t.getSourceFile().toUri().equals(edited.toUri()))
          .findFirst().orElse(null);
      if (unit == null) {
        return Optional.empty();
      }
      TreePath path = pathAt(unit, positions, offset);
      if (path == null) {
        return Optional.empty();
      }
      Element element = treeUtil.getElement(path);
      if (element == null) {
        return Optional.empty();
      }
      TreePath declaration = treeUtil.getPath(element);
      if (declaration != null) {
        Path declaredIn = paths.get(declaration.getCompilationUnit().getSourceFile().toUri());
        if (declaredIn != null) {
          CompilationUnitTree declaringUnit = declaration.getCompilationUnit();
          long start = namePosition(declaration.getLeaf(), declaringUnit, positions, element);
          var lines = declaringUnit.getLineMap();
          return Optional.of(Target.at(new Location(declaredIn, lines.getLineNumber(start),
              lines.getColumnNumber(start))));
        }
      }
      return libraryTarget(element);
    }
  }

  private static Optional<Target> libraryTarget(Element element) {
    if (element instanceof TypeElement type) {
      return Optional.of(Target.library(type.getQualifiedName().toString(), null));
    }
    Element owner = element.getEnclosingElement();
    if (owner instanceof TypeElement type) {
      String member = element.getKind() == ElementKind.CONSTRUCTOR ? null
          : element.getSimpleName().toString();
      return Optional.of(Target.library(type.getQualifiedName().toString(), member));
    }
    return Optional.empty();
  }

  /** Points at the declared name rather than at modifiers or annotations. */
  private static long namePosition(Tree tree, CompilationUnitTree unit,
      SourcePositions positions, Element element) {
    long start = positions.getStartPosition(unit, tree);
    String name = element instanceof ExecutableElement executable
        && executable.getKind() == ElementKind.CONSTRUCTOR
        ? executable.getEnclosingElement().getSimpleName().toString()
        : element.getSimpleName().toString();
    if (tree instanceof ClassTree || tree instanceof MethodTree || tree instanceof VariableTree) {
      long end = positions.getEndPosition(unit, tree);
      try {
        String text = unit.getSourceFile().getCharContent(true).toString();
        int limit = (int) Math.min(text.length(), end < 0 ? text.length() : end);
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b")
            .matcher(text).region((int) start, limit);
        if (tree instanceof ClassTree) {
          m = java.util.regex.Pattern.compile("\\b(?:class|interface|enum|record)\\s+("
              + java.util.regex.Pattern.quote(name) + ")\\b").matcher(text)
              .region((int) start, limit);
          if (m.find()) {
            return m.start(1);
          }
        } else if (m.find()) {
          return m.start();
        }
      } catch (IOException ignored) {
        // fall back to the tree start
      }
    }
    return start;
  }

  /** The innermost identifier or member select that covers {@code offset}. */
  private static TreePath pathAt(CompilationUnitTree unit, SourcePositions positions,
      int offset) {
    TreePath[] best = {null};
    new TreePathScanner<Void, Void>() {
      @Override
      public Void scan(Tree tree, Void unused) {
        if (tree == null) {
          return null;
        }
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start <= offset && offset <= end || start < 0) {
          return super.scan(tree, unused);
        }
        return null;
      }

      @Override
      public Void visitIdentifier(IdentifierTree node, Void unused) {
        best[0] = getCurrentPath();
        return super.visitIdentifier(node, unused);
      }

      @Override
      public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        // the name part: "this.move" at "move" (the expression part is scanned below)
        long end = positions.getEndPosition(unit, node);
        if (offset >= end - node.getIdentifier().length() && offset <= end) {
          best[0] = getCurrentPath();
          return null;
        }
        return super.visitMemberSelect(node, unused);
      }

      @Override
      public Void visitNewClass(com.sun.source.tree.NewClassTree node, Void unused) {
        // "new Player()" on "Player" goes to the constructor
        long start = positions.getStartPosition(unit, node.getIdentifier());
        long end = positions.getEndPosition(unit, node.getIdentifier());
        if (start <= offset && offset <= end) {
          best[0] = getCurrentPath();
          return null;
        }
        return super.visitNewClass(node, unused);
      }
    }.scan(unit, null);
    return best[0];
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(Path file, String text) {
      super(file.toUri(), JavaFileObject.Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
