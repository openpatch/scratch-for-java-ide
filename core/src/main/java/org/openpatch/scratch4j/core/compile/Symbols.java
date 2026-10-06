package org.openpatch.scratch4j.core.compile;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Find usages" and "Rename": resolves the name at an offset with javac and
 * lists every place in the project that declares or uses the same element — a
 * field, method, parameter, local variable or class. Methods include the
 * project methods that override them (or that they override), so renaming a
 * method keeps an override chain intact.
 */
public final class Symbols {

  /** One occurrence of the name: offsets in the file's text, 1-based line and column. */
  public record Occurrence(Path file, int start, int end, long line, long column,
      String lineText, boolean declaration) {}

  /**
   * The resolved element. {@code inProject} is false for names from the library
   * or the JDK. {@code libraryMethod} names the library method that a project
   * method overrides ({@code Sprite.whenClicked}), which a rename must keep.
   * {@code topLevelClass} marks a class declared at the top of its own file.
   */
  public record Symbol(String name, ElementKind kind, boolean inProject, String owner,
      String libraryMethod, boolean topLevelClass, List<Occurrence> occurrences) {

    /** The occurrences grouped by file, in project order. */
    public Map<Path, List<Occurrence>> byFile() {
      return occurrences.stream().collect(Collectors.groupingBy(Occurrence::file,
          LinkedHashMap::new, Collectors.toList()));
    }
  }

  private Symbols() {}

  /**
   * The symbol at {@code offset} in {@code file}, with every occurrence in
   * {@code sources} (path to current text; the file itself must be included).
   * Broken code is tolerated: what javac can attribute is found.
   */
  public static Optional<Symbol> at(Path file, int offset, Map<Path, String> sources,
      List<Path> classpath) throws IOException {
    try (Analysis analysis = Analysis.of(sources, classpath)) {
      return analysis.symbolAt(file, offset);
    }
  }

  /**
   * The new text of every file that changes when the symbol at {@code offset}
   * is renamed to {@code newName}. The project must compile before and after:
   * a clash (two fields with the same name, a local hiding a field that is
   * used) is reported instead of silently changing what the code means.
   */
  public static Map<Path, String> rename(Path file, int offset, String newName,
      Map<Path, String> sources, List<Path> classpath) throws IOException {
    if (newName == null || !SourceVersion.isIdentifier(newName)
        || SourceVersion.isKeyword(newName)) {
      throw new IOException("Not a valid Java name: " + newName);
    }
    Symbol symbol;
    try (Analysis analysis = Analysis.of(sources, classpath)) {
      List<String> errors = analysis.errors();
      if (!errors.isEmpty()) {
        throw new IOException("Fix the Java errors before renaming: " + errors.get(0));
      }
      symbol = analysis.symbolAt(file, offset)
          .orElseThrow(() -> new IOException("Place the cursor on a name to rename it"));
    }
    if (!symbol.inProject()) {
      throw new IOException(symbol.name() + " belongs to "
          + (symbol.owner() == null ? "the library" : symbol.owner())
          + " and cannot be renamed here");
    }
    if (symbol.libraryMethod() != null) {
      throw new IOException(symbol.name() + " overrides " + symbol.libraryMethod()
          + ". Scratch for Java calls it by this name, so it cannot be renamed");
    }
    if (symbol.name().equals(newName)) {
      throw new IOException("Choose a different name");
    }
    Map<Path, String> changed = new LinkedHashMap<>();
    for (Map.Entry<Path, List<Occurrence>> entry : symbol.byFile().entrySet()) {
      StringBuilder text = new StringBuilder(sources.get(entry.getKey()));
      entry.getValue().stream()
          .sorted(Comparator.comparingInt(Occurrence::start).reversed())
          .forEach(o -> text.replace(o.start(), o.end(), newName));
      changed.put(entry.getKey(), text.toString());
    }
    Map<Path, String> after = new HashMap<>(sources);
    after.putAll(changed);
    try (Analysis check = Analysis.of(after, classpath)) {
      List<String> errors = check.errors();
      if (!errors.isEmpty()) {
        throw new IOException("Renaming " + symbol.name() + " to " + newName
            + " would break the code: " + errors.get(0));
      }
      // the same number of uses must still point at one element: catches shadowing
      // (occurrences are sorted, so nothing before the first one moved)
      Occurrence first = symbol.occurrences().get(0);
      Optional<Symbol> renamed = check.symbolAt(first.file(), first.start());
      if (renamed.isEmpty() || renamed.get().occurrences().size()
          != symbol.occurrences().size()) {
        throw new IOException("Renaming " + symbol.name() + " to " + newName
            + " would change which name some code refers to. Choose another name");
      }
    }
    return changed;
  }

  // --- the javac run ------------------------------------------------------------

  private static final class Analysis implements AutoCloseable {
    private final StandardJavaFileManager files;
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private final Map<URI, Path> paths;
    private final Map<Path, String> texts;
    private final List<CompilationUnitTree> units;
    private final Trees trees;
    private final Elements elements;

    private Analysis(StandardJavaFileManager files,
        DiagnosticCollector<JavaFileObject> diagnostics, Map<URI, Path> paths,
        Map<Path, String> texts, List<CompilationUnitTree> units, Trees trees,
        Elements elements) {
      this.files = files;
      this.diagnostics = diagnostics;
      this.paths = paths;
      this.texts = texts;
      this.units = units;
      this.trees = trees;
      this.elements = elements;
    }

    static Analysis of(Map<Path, String> sources, List<Path> classpath) throws IOException {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      if (compiler == null) {
        throw new IOException("The bundled Java compiler is unavailable");
      }
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null,
          StandardCharsets.UTF_8);
      if (!classpath.isEmpty()) {
        files.setLocation(StandardLocation.CLASS_PATH,
            classpath.stream().map(Path::toFile).collect(Collectors.toList()));
      }
      Map<URI, Path> paths = new HashMap<>();
      Map<Path, String> texts = new HashMap<>();
      List<JavaFileObject> objects = new ArrayList<>();
      for (Map.Entry<Path, String> entry : sources.entrySet()) {
        Path path = entry.getKey().toAbsolutePath().normalize();
        Source object = new Source(path, entry.getValue());
        objects.add(object);
        paths.put(object.toUri(), entry.getKey());
        texts.put(entry.getKey(), entry.getValue());
      }
      JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
          List.of("-proc:none", "-encoding", "UTF-8"), null, objects);
      List<CompilationUnitTree> units = new ArrayList<>();
      task.parse().forEach(units::add);
      try {
        task.analyze();
      } catch (RuntimeException ignored) {
        // attribution is best effort: broken code still resolves what it can
      }
      return new Analysis(files, diagnostics, paths, texts, units, Trees.instance(task),
          task.getElements());
    }

    List<String> errors() {
      return diagnostics.getDiagnostics().stream()
          .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
          .map(d -> {
            String where = d.getSource() == null ? ""
                : pathOf(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ": ";
            return where + d.getMessage(java.util.Locale.ENGLISH);
          })
          .toList();
    }

    private Path pathOf(URI uri) {
      Path path = paths.get(uri);
      return path != null ? path : Path.of(uri);
    }

    Optional<Symbol> symbolAt(Path file, int offset) {
      CompilationUnitTree unit = unitFor(file);
      if (unit == null) {
        return Optional.empty();
      }
      Element element = elementAt(unit, offset);
      if (element == null || element.getSimpleName().isEmpty()) {
        return Optional.empty();
      }
      if (element.getKind() == ElementKind.CONSTRUCTOR) {
        element = element.getEnclosingElement();
      }
      Set<Element> targets = related(element);
      String libraryMethod = null;
      if (element instanceof ExecutableElement method) {
        libraryMethod = overriddenOutsideProject(method, targets);
      }
      boolean inProject = trees.getPath(element) != null
          && paths.containsKey(trees.getPath(element).getCompilationUnit()
              .getSourceFile().toUri());
      String name = element.getSimpleName().toString();
      List<Occurrence> occurrences = new ArrayList<>();
      // a library name has no declaration here, but its uses are still worth listing
      for (CompilationUnitTree each : units) {
        collect(each, targets, name, occurrences);
      }
      Element owner = element instanceof TypeElement ? element : element.getEnclosingElement();
      String ownerName = owner instanceof TypeElement type
          ? type.getQualifiedName().toString() : null;
      boolean topLevel = element instanceof TypeElement type
          && type.getEnclosingElement().getKind() == ElementKind.PACKAGE;
      occurrences.sort(Comparator.comparing((Occurrence o) -> o.file().toString())
          .thenComparingInt(Occurrence::start));
      return Optional.of(new Symbol(name, element.getKind(), inProject, ownerName,
          libraryMethod, topLevel, List.copyOf(occurrences)));
    }

    private CompilationUnitTree unitFor(Path file) {
      for (CompilationUnitTree unit : units) {
        if (file.equals(paths.get(unit.getSourceFile().toUri()))) {
          return unit;
        }
      }
      return null;
    }

    /** A method plus the project methods in its override chain (both directions). */
    private Set<Element> related(Element element) {
      Set<Element> result = new HashSet<>();
      result.add(element);
      if (!(element instanceof ExecutableElement method)
          || method.getKind() != ElementKind.METHOD
          || method.getModifiers().contains(Modifier.STATIC)
          || method.getModifiers().contains(Modifier.PRIVATE)) {
        return result;
      }
      List<ExecutableElement> projectMethods = new ArrayList<>();
      for (CompilationUnitTree unit : units) {
        new TreePathScanner<Void, Void>() {
          @Override public Void visitMethod(MethodTree node, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement m
                && m.getKind() == ElementKind.METHOD
                && m.getSimpleName().equals(method.getSimpleName())) {
              projectMethods.add(m);
            }
            return super.visitMethod(node, unused);
          }
        }.scan(unit, null);
      }
      boolean grew = true;
      while (grew) {
        grew = false;
        for (ExecutableElement candidate : projectMethods) {
          if (result.contains(candidate)) {
            continue;
          }
          for (Element known : List.copyOf(result)) {
            if (known instanceof ExecutableElement k && overrides(candidate, k)
                || known instanceof ExecutableElement k2 && overrides(k2, candidate)) {
              result.add(candidate);
              grew = true;
              break;
            }
          }
        }
      }
      return result;
    }

    private boolean overrides(ExecutableElement method, ExecutableElement other) {
      return method.getEnclosingElement() instanceof TypeElement type
          && elements.overrides(method, other, type);
    }

    /** {@code Sprite.whenClicked} when a method in the chain overrides a library method. */
    private String overriddenOutsideProject(ExecutableElement method, Set<Element> chain) {
      for (Element element : chain) {
        if (!(element instanceof ExecutableElement m)
            || !(m.getEnclosingElement() instanceof TypeElement type)) {
          continue;
        }
        String found = overriddenIn(m, type.getSuperclass(), type);
        if (found == null) {
          for (var iface : type.getInterfaces()) {
            found = overriddenIn(m, iface, type);
            if (found != null) break;
          }
        }
        if (found != null) {
          return found;
        }
      }
      return null;
    }

    private String overriddenIn(ExecutableElement method, javax.lang.model.type.TypeMirror type,
        TypeElement from) {
      if (!(type instanceof javax.lang.model.type.DeclaredType declared)
          || !(declared.asElement() instanceof TypeElement superType)) {
        return null;
      }
      TreePath path = trees.getPath(superType);
      boolean project = path != null
          && paths.containsKey(path.getCompilationUnit().getSourceFile().toUri());
      if (!project) {
        for (Element member : elements.getAllMembers(superType)) {
          if (member instanceof ExecutableElement candidate
              && candidate.getSimpleName().equals(method.getSimpleName())
              && elements.overrides(method, candidate, from)) {
            return ((TypeElement) candidate.getEnclosingElement()).getSimpleName() + "."
                + candidate.getSimpleName();
          }
        }
        return null;
      }
      String found = overriddenIn(method, superType.getSuperclass(), from);
      if (found == null) {
        for (var iface : superType.getInterfaces()) {
          found = overriddenIn(method, iface, from);
          if (found != null) break;
        }
      }
      return found;
    }

    /** The element named at {@code offset}: a use or the name in a declaration. */
    private Element elementAt(CompilationUnitTree unit, int offset) {
      SourcePositions positions = trees.getSourcePositions();
      String text = texts.get(paths.get(unit.getSourceFile().toUri()));
      Element[] best = {null};
      new TreePathScanner<Void, Void>() {
        @Override public Void scan(Tree tree, Void unused) {
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

        @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
          Element element = trees.getElement(getCurrentPath());
          if (element != null) {
            best[0] = element;
          }
          return super.visitIdentifier(node, unused);
        }

        @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
          long end = positions.getEndPosition(unit, node);
          if (offset >= end - node.getIdentifier().length() && offset <= end) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null) {
              best[0] = element;
            }
            return null;
          }
          return super.visitMemberSelect(node, unused);
        }

        @Override public Void visitMemberReference(MemberReferenceTree node, Void unused) {
          long end = positions.getEndPosition(unit, node);
          if (offset >= end - node.getName().length() && offset <= end) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null) {
              best[0] = element;
            }
            return null;
          }
          return super.visitMemberReference(node, unused);
        }

        @Override public Void visitClass(ClassTree node, Void unused) {
          declaration(node);
          return super.visitClass(node, unused);
        }

        @Override public Void visitMethod(MethodTree node, Void unused) {
          declaration(node);
          return super.visitMethod(node, unused);
        }

        @Override public Void visitVariable(VariableTree node, Void unused) {
          declaration(node);
          return super.visitVariable(node, unused);
        }

        private void declaration(Tree node) {
          Element element = trees.getElement(getCurrentPath());
          if (element == null) {
            return;
          }
          int at = declarationName(unit, node, element, text, positions);
          if (at >= 0 && offset >= at && offset <= at + element.getSimpleName().length()) {
            best[0] = element;
          }
        }
      }.scan(unit, null);
      return best[0];
    }

    private void collect(CompilationUnitTree unit, Set<Element> targets, String name,
        List<Occurrence> out) {
      SourcePositions positions = trees.getSourcePositions();
      Path file = paths.get(unit.getSourceFile().toUri());
      String text = texts.get(file);
      var lines = unit.getLineMap();
      Set<Integer> seen = new HashSet<>();
      java.util.function.BiConsumer<Integer, Boolean> add = (start, declaration) -> {
        int end = start + name.length();
        if (start < 0 || end > text.length() || !text.substring(start, end).equals(name)
            || !seen.add(start)) {
          return; // synthetic trees (a default constructor, an implicit super()) have no text
        }
        long line = lines.getLineNumber(start);
        int lineStart = (int) lines.getStartPosition(line);
        int lineEnd = text.indexOf('\n', start);
        String lineText = text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd);
        out.add(new Occurrence(file, start, end, line, lines.getColumnNumber(start),
            lineText.strip(), declaration));
      };
      new TreePathScanner<Void, Void>() {
        @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
          if (matches(trees.getElement(getCurrentPath())) && node.getName().contentEquals(name)) {
            add.accept((int) positions.getStartPosition(unit, node), false);
          }
          return super.visitIdentifier(node, unused);
        }

        @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
          if (matches(trees.getElement(getCurrentPath()))
              && node.getIdentifier().contentEquals(name)) {
            add.accept((int) positions.getEndPosition(unit, node) - name.length(), false);
          }
          return super.visitMemberSelect(node, unused);
        }

        @Override public Void visitMemberReference(MemberReferenceTree node, Void unused) {
          if (matches(trees.getElement(getCurrentPath()))
              && node.getName().contentEquals(name)) {
            add.accept((int) positions.getEndPosition(unit, node) - name.length(), false);
          }
          return super.visitMemberReference(node, unused);
        }

        @Override public Void visitClass(ClassTree node, Void unused) {
          declared(node);
          return super.visitClass(node, unused);
        }

        @Override public Void visitMethod(MethodTree node, Void unused) {
          Element element = trees.getElement(getCurrentPath());
          // a constructor carries its class's name
          if (element != null && element.getKind() == ElementKind.CONSTRUCTOR
              && targets.contains(element.getEnclosingElement())) {
            int at = declarationName(unit, node, element, text, positions);
            if (at >= 0) add.accept(at, true);
          } else {
            declared(node);
          }
          return super.visitMethod(node, unused);
        }

        @Override public Void visitVariable(VariableTree node, Void unused) {
          declared(node);
          return super.visitVariable(node, unused);
        }

        private void declared(Tree node) {
          Element element = trees.getElement(getCurrentPath());
          if (matches(element)) {
            int at = declarationName(unit, node, element, text, positions);
            if (at >= 0) add.accept(at, true);
          }
        }

        private boolean matches(Element element) {
          if (element == null) {
            return false;
          }
          if (element.getKind() == ElementKind.CONSTRUCTOR) {
            // "new Player()" names the class
            return targets.contains(element.getEnclosingElement());
          }
          return targets.contains(element);
        }
      }.scan(unit, null);
    }

    @Override public void close() throws IOException {
      files.close();
    }
  }

  /** Where the declared name starts (after modifiers, annotations and the type). */
  private static int declarationName(CompilationUnitTree unit, Tree tree, Element element,
      String text, SourcePositions positions) {
    long start = positions.getStartPosition(unit, tree);
    long end = positions.getEndPosition(unit, tree);
    if (start < 0 || text == null) {
      return -1;
    }
    String name = element.getKind() == ElementKind.CONSTRUCTOR
        ? element.getEnclosingElement().getSimpleName().toString()
        : element.getSimpleName().toString();
    int limit = (int) Math.min(text.length(), end < 0 ? text.length() : end);
    Matcher m;
    if (tree instanceof ClassTree) {
      m = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(" + Pattern.quote(name)
          + ")\\b").matcher(text).region((int) start, limit);
      return m.find() ? m.start(1) : -1;
    }
    int from = (int) start;
    if (tree instanceof VariableTree variable && variable.getType() != null) {
      long typeEnd = positions.getEndPosition(unit, variable.getType());
      if (typeEnd > 0) {
        from = (int) typeEnd; // "Player player": the name, not the type
      }
    } else if (tree instanceof MethodTree method && method.getReturnType() != null) {
      long typeEnd = positions.getEndPosition(unit, method.getReturnType());
      if (typeEnd > 0) {
        from = (int) typeEnd;
      }
    } else if (tree instanceof MethodTree method && method.getModifiers() != null) {
      long modifiersEnd = positions.getEndPosition(unit, method.getModifiers());
      if (modifiersEnd > 0) {
        from = (int) modifiersEnd; // a constructor: "public Player(" after the modifiers
      }
    }
    if (from > limit) {
      return -1;
    }
    String pattern = tree instanceof MethodTree ? "\\b" + Pattern.quote(name) + "\\s*\\("
        : "\\b" + Pattern.quote(name) + "\\b";
    m = Pattern.compile(pattern).matcher(text).region(from, limit);
    return m.find() ? m.start() : -1;
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(Path file, String text) {
      super(file.toUri(), JavaFileObject.Kind.SOURCE);
      this.text = text;
    }

    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
