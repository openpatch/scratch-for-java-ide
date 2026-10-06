package org.openpatch.scratch4j.core.uml;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project's classes as a UML class diagram sees them: attributes and
 * methods with visibility and types, inheritance, and associations (an
 * attribute whose type is another project class; a list or array of them is
 * a "*" association).
 */
public final class ClassModel {

  public enum Visibility {
    PUBLIC('+'), PROTECTED('#'), PACKAGE('~'), PRIVATE('-');

    public final char symbol;

    Visibility(char symbol) {
      this.symbol = symbol;
    }
  }

  public record Attribute(String name, String type, Visibility visibility, boolean isStatic) {

    /** UML: {@code - score: int}. */
    public String uml() {
      return visibility.symbol + " " + name + ": " + type;
    }
  }

  public record Operation(String name, List<String> params, String returnType,
      Visibility visibility, boolean isStatic, boolean isAbstract, boolean constructor) {

    /** UML: {@code + move(steps: double): void}, constructors without a return type. */
    public String uml() {
      return visibility.symbol + " " + name + "(" + String.join(", ", params) + ")"
          + (constructor ? "" : ": " + returnType);
    }
  }

  /** A class of the project ({@code library} false) or a library base class. */
  public record UmlClass(String name, String superclass, List<String> interfaces,
      boolean isAbstract, boolean isInterface, boolean library, List<Attribute> attributes,
      List<Operation> operations, Path file) {}

  /** An attribute pointing at another project class: from -> to, "* " for many. */
  public record Association(String from, String to, String role, boolean many) {}

  public record Model(List<UmlClass> classes, List<Association> associations) {

    public UmlClass byName(String name) {
      return classes.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }
  }

  private static final Pattern MANY = Pattern.compile(
      "^(?:java\\.util\\.)?(?:List|ArrayList|LinkedList|Set|HashSet|Collection|Queue|Deque|"
          + "ArrayDeque|CopyOnWriteArrayList)<\\s*(\\w+)\\s*>$|^(\\w+)\\[\\]$");
  private static final Pattern MAP_VALUE = Pattern.compile(
      "^(?:java\\.util\\.)?(?:Map|HashMap|TreeMap|LinkedHashMap)<[^,]+,\\s*(\\w+)\\s*>$");

  private ClassModel() {}

  public static Model of(List<Path> sources) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    List<Source> units = new ArrayList<>();
    for (Path source : sources) {
      units.add(new Source(source, Files.readString(source, StandardCharsets.UTF_8)));
    }
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of(), null,
        units);
    Map<String, UmlClass> classes = new LinkedHashMap<>();
    for (CompilationUnitTree unit : task.parse()) {
      Path file = Path.of(unit.getSourceFile().toUri().getPath().replaceFirst("^/", ""));
      for (Source s : units) {
        if (s.toUri().equals(unit.getSourceFile().toUri())) file = s.file;
      }
      for (Tree type : unit.getTypeDecls()) {
        if (type instanceof ClassTree cls && cls.getKind() != Tree.Kind.ENUM
            && cls.getKind() != Tree.Kind.RECORD && cls.getKind() != Tree.Kind.ANNOTATION_TYPE) {
          classes.put(cls.getSimpleName().toString(), read(cls, file));
        }
      }
    }
    // library classes the project extends appear as small boxes
    Map<String, UmlClass> all = new LinkedHashMap<>();
    for (UmlClass c : classes.values()) {
      if (c.superclass() != null && !classes.containsKey(c.superclass())
          && !all.containsKey(c.superclass())) {
        all.put(c.superclass(), new UmlClass(c.superclass(), null, List.of(), false, false,
            true, List.of(), List.of(), null));
      }
    }
    all.putAll(classes);
    List<Association> associations = new ArrayList<>();
    for (UmlClass c : classes.values()) {
      for (Attribute a : c.attributes()) {
        String type = a.type().replace(" ", "");
        String target = type;
        boolean many = false;
        Matcher m = MANY.matcher(type);
        Matcher map = MAP_VALUE.matcher(type);
        if (m.matches()) {
          target = m.group(1) != null ? m.group(1) : m.group(2);
          many = true;
        } else if (map.matches()) {
          target = map.group(1);
          many = true;
        }
        if (classes.containsKey(target)) {
          associations.add(new Association(c.name(), target, a.name(), many));
        }
      }
    }
    return new Model(List.copyOf(all.values()), associations);
  }

  private static UmlClass read(ClassTree cls, Path file) {
    String superclass = cls.getExtendsClause() == null ? null
        : simple(cls.getExtendsClause().toString());
    List<String> interfaces = cls.getImplementsClause().stream()
        .map(t -> simple(t.toString())).toList();
    boolean isInterface = cls.getKind() == Tree.Kind.INTERFACE;
    boolean isAbstract = cls.getModifiers().getFlags().contains(Modifier.ABSTRACT);
    List<Attribute> attributes = new ArrayList<>();
    List<Operation> operations = new ArrayList<>();
    String name = cls.getSimpleName().toString();
    for (Tree member : cls.getMembers()) {
      if (member instanceof VariableTree field) {
        attributes.add(new Attribute(field.getName().toString(),
            simpleType(field.getType().toString()), visibility(field.getModifiers(),
                isInterface), field.getModifiers().getFlags().contains(Modifier.STATIC)));
      } else if (member instanceof MethodTree method) {
        boolean constructor = method.getName().contentEquals("<init>");
        List<String> params = new ArrayList<>();
        for (VariableTree p : method.getParameters()) {
          params.add(p.getName() + ": " + simpleType(p.getType().toString()));
        }
        operations.add(new Operation(constructor ? name : method.getName().toString(), params,
            method.getReturnType() == null ? "" : simpleType(method.getReturnType().toString()),
            visibility(method.getModifiers(), isInterface),
            method.getModifiers().getFlags().contains(Modifier.STATIC),
            method.getModifiers().getFlags().contains(Modifier.ABSTRACT)
                || isInterface && method.getBody() == null,
            constructor));
      }
    }
    return new UmlClass(name, superclass, interfaces, isAbstract, isInterface, false,
        attributes, operations, file);
  }

  private static Visibility visibility(ModifiersTree modifiers, boolean inInterface) {
    Set<Modifier> flags = modifiers.getFlags();
    if (flags.contains(Modifier.PUBLIC) || inInterface) return Visibility.PUBLIC;
    if (flags.contains(Modifier.PROTECTED)) return Visibility.PROTECTED;
    if (flags.contains(Modifier.PRIVATE)) return Visibility.PRIVATE;
    return Visibility.PACKAGE;
  }

  /** {@code java.util.List<org.x.Player>} -> {@code List<Player>}. */
  static String simpleType(String type) {
    return type.replaceAll("\\b(?:[a-z_][\\w]*\\.)+([A-Z]\\w*)", "$1");
  }

  private static String simple(String type) {
    String plain = type.replaceAll("<.*>", "");
    return plain.substring(plain.lastIndexOf('.') + 1);
  }

  private static final class Source extends SimpleJavaFileObject {
    private final Path file;
    private final String text;

    Source(Path file, String text) {
      super(URI.create("string:///" + file.getFileName()), Kind.SOURCE);
      this.file = file;
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
