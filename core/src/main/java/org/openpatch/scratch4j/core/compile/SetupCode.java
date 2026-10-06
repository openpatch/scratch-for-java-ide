package org.openpatch.scratch4j.core.compile;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The code that runs when objects are made: constructors, attribute starting
 * values ({@code int lives = 3;}) and initializer blocks, per class
 * ({@code Outer$Inner} for nested ones). Hot reload swaps method bodies into a
 * running program, but its objects were already made with the old setup:
 * comparing this before and after a save tells the student when a change
 * only shows after a restart. Comments and formatting do not count.
 */
public final class SetupCode {

  /**
   * An attribute whose starting value is a plain literal ({@code int speed = 5;},
   * {@code String name = "Bob";}): hot reload can give running objects a new one.
   */
  public record StartValue(String className, String field, String type, String literal,
      boolean isStatic) {}

  private SetupCode() {}

  /** "Class.field" -> the literal starting values of non-final attributes. */
  public static Map<String, StartValue> startValues(String source) {
    Map<String, StartValue> out = new TreeMap<>();
    for (CompilationUnitTree unit : parse(source)) {
      for (Tree type : unit.getTypeDecls()) {
        if (type instanceof ClassTree cls) {
          collectValues(cls, cls.getSimpleName().toString(), out);
        }
      }
    }
    return out;
  }

  private static void collectValues(ClassTree cls, String name, Map<String, StartValue> out) {
    for (Tree member : cls.getMembers()) {
      if (member instanceof VariableTree field && isLiveLiteral(field)) {
        out.put(name + "." + field.getName(), new StartValue(name, field.getName().toString(),
            field.getType().toString(), field.getInitializer().toString(),
            field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)));
      } else if (member instanceof ClassTree nested) {
        collectValues(nested, name + "$" + nested.getSimpleName(), out);
      }
    }
  }

  /** A non-final attribute starting with a literal (or a negative number). */
  private static boolean isLiveLiteral(VariableTree field) {
    if (field.getInitializer() == null
        || field.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.FINAL)) {
      return false;
    }
    Tree init = field.getInitializer();
    if (init instanceof com.sun.source.tree.UnaryTree unary
        && (unary.getKind() == Tree.Kind.UNARY_MINUS || unary.getKind() == Tree.Kind.UNARY_PLUS)) {
      init = unary.getExpression();
    }
    return init instanceof com.sun.source.tree.LiteralTree literal
        && literal.getKind() != Tree.Kind.NULL_LITERAL;
  }

  private static List<? extends CompilationUnitTree> parse(String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return List.of();
    }
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of(), null,
        List.of(new Source(source)));
    try {
      List<CompilationUnitTree> units = new java.util.ArrayList<>();
      task.parse().forEach(units::add);
      return units;
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
  }

  /** Class name -> its setup code in a normalized form (empty map if unparsable). */
  public static Map<String, String> of(String source) {
    Map<String, String> out = new TreeMap<>();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return out;
    }
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of(), null,
        List.of(new Source(source)));
    try {
      for (CompilationUnitTree unit : task.parse()) {
        for (Tree type : unit.getTypeDecls()) {
          if (type instanceof ClassTree cls) {
            collect(cls, cls.getSimpleName().toString(), out);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      return Map.of();
    }
    return out;
  }

  private static void collect(ClassTree cls, String name, Map<String, String> out) {
    StringBuilder setup = new StringBuilder();
    for (Tree member : cls.getMembers()) {
      if (member instanceof MethodTree method && method.getName().contentEquals("<init>")) {
        // the pretty-printed tree: no comments, one formatting
        setup.append(method).append('\n');
      } else if (member instanceof VariableTree field && field.getInitializer() != null
          && !isLiveLiteral(field)) {
        // literal starting values go into running objects (startValues), the rest needs a restart
        setup.append(field).append('\n');
      } else if (member instanceof BlockTree block) {
        setup.append(block).append('\n');
      } else if (member instanceof ClassTree nested) {
        collect(nested, name + "$" + nested.getSimpleName(), out);
      }
    }
    out.put(name, setup.toString());
  }

  /** The classes whose setup differs between two versions of the sources. */
  public static List<String> changed(Map<String, String> before, Map<String, String> after) {
    return after.entrySet().stream()
        .filter(e -> before.containsKey(e.getKey())
            && !before.get(e.getKey()).equals(e.getValue()))
        .map(Map.Entry::getKey).toList();
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(String text) {
      super(URI.create("string:///Setup.java"), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
