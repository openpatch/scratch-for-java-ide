package org.openpatch.scratch4j.core.compile;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.source.tree.Scope;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Type-aware completion with javac: after {@code player.} the members of the
 * receiver's type (static ones after a type name such as {@code Window.}),
 * otherwise every name in scope — locals, parameters, the class's own and
 * inherited fields and methods (a Sprite subclass sees {@code move},
 * {@code say}, …), project classes and the library's classes.
 *
 * <p>The caret's identifier is replaced by a placeholder so even a half-typed
 * line parses; attribution is best effort, so broken code elsewhere still
 * completes.
 */
public final class Completions {

  /** What kind of name a completion is. */
  public enum Kind { METHOD, FIELD, VARIABLE, CLASS }

  /**
   * One completion. {@code owner} is the declaring type's qualified name
   * (null for locals), {@code signature} reads like {@code move(double steps)}.
   */
  public record Item(String name, Kind kind, String signature, String type, String owner,
      boolean isStatic, List<String> parameterTypes) {}

  /** {@code start} is where the typed prefix begins (the text to replace). */
  public record Result(int start, String prefix, boolean memberAccess, List<Item> items) {}

  private static final String PLACEHOLDER = "scratch4jCaret__";
  /** Object's plumbing that a beginner never wants first (still listed, at the end). */
  private static final Set<String> OBJECT_NOISE = Set.of("wait", "notify", "notifyAll",
      "getClass", "hashCode", "equals", "toString", "finalize", "clone");

  private Completions() {}

  public static Result complete(Path file, String text, int offset, List<Path> sources,
      List<Path> classpath) throws IOException {
    int start = offset;
    while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
      start--;
    }
    String prefix = text.substring(start, offset);
    int before = start - 1;
    while (before >= 0 && Character.isWhitespace(text.charAt(before))) {
      before--;
    }
    boolean memberAccess = before >= 0 && text.charAt(before) == '.';
    int end = offset;
    while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
      end++;
    }
    String restOfLine = text.substring(end, lineEnd(text, end));
    // at the end of a line the placeholder must form a statement: "x.name;" is
    // not one (the parser drops it), "x.name();" is
    String filler = restOfLine.isBlank() ? "();" : "";
    String probe = text.substring(0, start) + PLACEHOLDER + filler + text.substring(end);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return new Result(start, prefix, memberAccess, List.of());
    }
    try (StandardJavaFileManager files = compiler.getStandardFileManager(d -> { }, null,
        StandardCharsets.UTF_8)) {
      if (!classpath.isEmpty()) {
        files.setLocation(StandardLocation.CLASS_PATH,
            classpath.stream().map(Path::toFile).collect(Collectors.toList()));
      }
      Path current = file.toAbsolutePath().normalize();
      List<JavaFileObject> units = new ArrayList<>();
      for (Path source : sources) {
        Path normalized = source.toAbsolutePath().normalize();
        if (!normalized.equals(current)) {
          units.add(new Source(normalized, Files.readString(normalized, StandardCharsets.UTF_8)));
        }
      }
      Source edited = new Source(current, probe);
      units.add(edited);
      JavacTask task = (JavacTask) compiler.getTask(null, files, d -> { },
          List.of("-proc:none", "-encoding", "UTF-8"), null, units);
      List<CompilationUnitTree> trees = new ArrayList<>();
      task.parse().forEach(trees::add);
      try {
        task.analyze();
      } catch (RuntimeException ignored) {
        // best effort
      }
      Trees treeUtil = Trees.instance(task);
      Elements elements = task.getElements();
      CompilationUnitTree unit = trees.stream()
          .filter(t -> t.getSourceFile().toUri().equals(edited.toUri()))
          .findFirst().orElse(null);
      if (unit == null) {
        return new Result(start, prefix, memberAccess, List.of());
      }
      TreePath placeholder = find(unit);
      if (placeholder == null) {
        return new Result(start, prefix, memberAccess, List.of());
      }
      Map<String, Item> items = new LinkedHashMap<>();
      Scope scope = treeUtil.getScope(placeholder);
      if (placeholder.getLeaf() instanceof MemberSelectTree select) {
        TreePath receiver = new TreePath(placeholder, select.getExpression());
        Element receiverElement = treeUtil.getElement(receiver);
        TypeMirror type = treeUtil.getTypeMirror(receiver);
        boolean staticOnly = receiverElement != null
            && (receiverElement.getKind().isClass() || receiverElement.getKind().isInterface()
                || receiverElement.getKind() == ElementKind.ENUM);
        if (receiverElement instanceof PackageElement pkg) {
          for (Element member : pkg.getEnclosedElements()) {
            add(items, member, prefix, treeUtil, scope, null);
          }
        } else if (type instanceof ArrayType) {
          if ("length".startsWith(prefix)) {
            items.put("length", new Item("length", Kind.FIELD, "length", "int", null, false,
                List.of()));
          }
        } else if (type instanceof DeclaredType declared
            && declared.asElement() instanceof TypeElement typeElement) {
          for (Element member : elements.getAllMembers(typeElement)) {
            boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
            if (staticOnly && !isStatic && member.getKind() != ElementKind.ENUM_CONSTANT) {
              continue;
            }
            // an instance does not offer static members: Java allows player.highscore,
            // but it hides that the value is shared
            if (!staticOnly && isStatic) {
              continue;
            }
            if (!treeUtil.isAccessible(scope, member, declared)) {
              continue;
            }
            add(items, member, prefix, treeUtil, scope, declared);
          }
          if (staticOnly && "class".startsWith(prefix)) {
            items.put("class", new Item("class", Kind.FIELD, "class", "Class", null, true,
                List.of()));
          }
        }
      } else {
        // names in scope, innermost first: locals and parameters, then members
        java.util.Set<TypeElement> classes = new java.util.LinkedHashSet<>();
        for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
          for (Element local : s.getLocalElements()) {
            add(items, local, prefix, treeUtil, scope, null);
          }
          if (s.getEnclosingClass() != null) {
            classes.add(s.getEnclosingClass());
          }
        }
        for (TypeElement enclosing : classes) {
          DeclaredType site = (DeclaredType) enclosing.asType();
          for (Element member : elements.getAllMembers(enclosing)) {
            if (treeUtil.isAccessible(scope, member, site)) {
              add(items, member, prefix, treeUtil, scope, site);
            }
          }
        }
        // classes: the project's own, the library's main package, java.lang basics
        for (CompilationUnitTree other : trees) {
          for (var decl : other.getTypeDecls()) {
            Element type = treeUtil.getElement(TreePath.getPath(other, decl));
            if (type != null) {
              add(items, type, prefix, treeUtil, scope, null);
            }
          }
        }
        for (String pkg : List.of("org.openpatch.scratch", "java.lang")) {
          PackageElement element = elements.getPackageElement(pkg);
          if (element != null) {
            for (Element type : element.getEnclosedElements()) {
              if (type.getModifiers().contains(Modifier.PUBLIC)
                  && (pkg.equals("org.openpatch.scratch")
                      || Set.of("Math", "String", "Integer", "Double", "System", "Boolean",
                          "Character", "Long").contains(type.getSimpleName().toString()))) {
                add(items, type, prefix, treeUtil, scope, null);
              }
            }
          }
        }
      }
      List<Item> sorted = new ArrayList<>(items.values());
      sorted.sort(order(prefix));
      return new Result(start, prefix, memberAccess, sorted);
    }
  }

  /** Exact-case prefix matches first, Object's plumbing last, then kind and name. */
  private static Comparator<Item> order(String prefix) {
    return Comparator
        .comparing((Item item) -> !item.name().startsWith(prefix))
        .thenComparing(item -> "java.lang.Object".equals(item.owner())
            || OBJECT_NOISE.contains(item.name()))
        .thenComparing(Item::kind)
        .thenComparing(Item::name, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(item -> item.parameterTypes().size());
  }

  private static void add(Map<String, Item> items, Element element, String prefix,
      Trees trees, Scope scope, DeclaredType site) {
    String name = element.getSimpleName().toString();
    if (name.isEmpty() || name.equals(PLACEHOLDER) || name.startsWith("<")
        || !name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
      return;
    }
    ElementKind kind = element.getKind();
    boolean isStatic = element.getModifiers().contains(Modifier.STATIC);
    String owner = element.getEnclosingElement() instanceof TypeElement type
        ? type.getQualifiedName().toString() : null;
    Item item;
    if (kind == ElementKind.METHOD) {
      ExecutableElement method = (ExecutableElement) element;
      List<String> parameterTypes = method.getParameters().stream()
          .map(p -> simple(p.asType())).toList();
      String params = method.getParameters().stream()
          .map(p -> simple(p.asType()) + " " + p.getSimpleName())
          .collect(Collectors.joining(", "));
      item = new Item(name, Kind.METHOD, name + "(" + params + ")",
          simple(method.getReturnType()), owner, isStatic, parameterTypes);
      items.putIfAbsent(name + "(" + String.join(",", parameterTypes) + ")", item);
      return;
    }
    if (kind == ElementKind.FIELD || kind == ElementKind.ENUM_CONSTANT) {
      item = new Item(name, Kind.FIELD, name, simple(element.asType()), owner, isStatic,
          List.of());
    } else if (element instanceof VariableElement) {
      item = new Item(name, Kind.VARIABLE, name, simple(element.asType()), null, false,
          List.of());
    } else if (kind.isClass() || kind.isInterface() || kind == ElementKind.ENUM
        || kind == ElementKind.RECORD) {
      String qualified = element instanceof TypeElement type
          ? type.getQualifiedName().toString() : name;
      item = new Item(name, Kind.CLASS, name, qualified, owner, isStatic, List.of());
    } else {
      return;
    }
    items.putIfAbsent(name, item);
  }

  /** Type names without packages: {@code java.lang.String} reads as {@code String}. */
  private static String simple(TypeMirror type) {
    if (type.getKind() == TypeKind.DECLARED || type.getKind() == TypeKind.ARRAY) {
      return type.toString().replaceAll("\\b[a-z][\\w]*\\.", "");
    }
    return type.toString();
  }

  private static TreePath find(CompilationUnitTree unit) {
    TreePath[] found = {null};
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitIdentifier(IdentifierTree node, Void unused) {
        if (node.getName().contentEquals(PLACEHOLDER)) {
          found[0] = getCurrentPath();
        }
        return null;
      }

      @Override
      public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        if (node.getIdentifier().contentEquals(PLACEHOLDER)) {
          found[0] = getCurrentPath();
          return null;
        }
        return super.visitMemberSelect(node, unused);
      }
    }.scan(unit, null);
    return found[0];
  }

  private static int lineEnd(String text, int from) {
    int newline = text.indexOf('\n', from);
    return newline < 0 ? text.length() : newline;
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
