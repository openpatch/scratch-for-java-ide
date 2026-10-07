package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The costume a sprite starts with, as the designer and the stage list draw
 * it, found the way Java runs the constructor: through {@code super(...)} and
 * {@code this(...)} into other project classes, with constructor parameters
 * taken from the first {@code new Racer("bee", ...)} in the project, and
 * simple expressions ({@code pathBase + "Idle (%d).png"}, attributes and
 * local variables with known values) worked out.
 *
 * <p>Returns a built-in name, a file path, or a part of a sprite sheet as
 * {@code path#x,y,w,h}; null when it cannot be known without running.
 */
public final class SpriteLook {

  private final Map<String, ClassTree> classes = new LinkedHashMap<>();
  private final List<NewClassTree> creations = new ArrayList<>();

  private SpriteLook(List<String> sources) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) return;
    List<Source> units = new ArrayList<>();
    for (int i = 0; i < sources.size(); i++) units.add(new Source(i, sources.get(i)));
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of(), null, units);
    try {
      for (CompilationUnitTree unit : task.parse()) {
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitClass(ClassTree cls, Void p) {
            classes.putIfAbsent(cls.getSimpleName().toString(), cls);
            return super.visitClass(cls, p);
          }

          @Override
          public Void visitNewClass(NewClassTree node, Void p) {
            creations.add(node);
            return super.visitNewClass(node, p);
          }
        }.scan(unit, null);
      }
    } catch (IOException | RuntimeException ignored) {
      // what parsed is used
    }
  }

  /** The first look of {@code className} in the project at {@code root}. */
  public static String of(Path root, String className) {
    List<String> sources = new ArrayList<>();
    try (Stream<Path> files = Files.list(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        sources.add(Files.readString(f, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      return null;
    }
    return of(sources, className);
  }

  /** The first look of {@code className} among these sources. */
  public static String of(List<String> sources, String className) {
    return new SpriteLook(sources).look(className);
  }

  private String look(String className) {
    ClassTree cls = classes.get(className);
    if (cls == null) return null;
    // arguments from the first place the project creates this class
    List<? extends ExpressionTree> args = null;
    for (NewClassTree creation : creations) {
      if (simple(creation.getIdentifier().toString()).equals(className)) {
        args = creation.getArguments();
        break;
      }
    }
    List<Object> values = new ArrayList<>();
    if (args != null) {
      for (ExpressionTree a : args) values.add(eval(a, Map.of()));
    }
    String[] found = {null};
    run(cls, args == null ? null : values, found, 0);
    return found[0];
  }

  /** Runs a constructor of {@code cls} (by argument count) until a costume appears. */
  private void run(ClassTree cls, List<Object> args, String[] found, int depth) {
    if (depth > 8 || found[0] != null) return;
    MethodTree ctor = constructor(cls, args);
    Map<String, Object> env = new HashMap<>();
    // attributes with known starting values
    for (Tree member : cls.getMembers()) {
      if (member instanceof VariableTree field && field.getInitializer() != null) {
        Object v = eval(field.getInitializer(), env);
        if (v != null) env.put(field.getName().toString(), v);
      }
    }
    if (ctor == null) {
      superclass(cls, List.of(), found, depth);
      return;
    }
    for (int i = 0; i < ctor.getParameters().size(); i++) {
      Object v = args != null && i < args.size() ? args.get(i) : null;
      if (v != null) env.put(ctor.getParameters().get(i).getName().toString(), v);
    }
    boolean explicitSuper = false;
    for (StatementTree statement : ctor.getBody().getStatements()) {
      if (found[0] != null) return;
      if (statement instanceof VariableTree local && local.getInitializer() != null) {
        Object v = eval(local.getInitializer(), env);
        if (v != null) env.put(local.getName().toString(), v);
      } else if (statement instanceof ExpressionStatementTree es) {
        ExpressionTree e = es.getExpression();
        if (e instanceof AssignmentTree assign) {
          Object v = eval(assign.getExpression(), env);
          String target = assign.getVariable() instanceof MemberSelectTree m
              ? m.getIdentifier().toString() : assign.getVariable().toString();
          if (v != null) env.put(target, v); else env.remove(target);
        } else if (e instanceof MethodInvocationTree call) {
          String name = name(call);
          List<Object> values = new ArrayList<>();
          for (ExpressionTree a : call.getArguments()) values.add(eval(a, env));
          if ("super".equals(name)) {
            explicitSuper = true;
            superclass(cls, values, found, depth);
          } else if ("this".equals(name)) {
            explicitSuper = true;
            run(cls, values, found, depth + 1);
          } else if (onThis(call)) {
            found[0] = costume(name, values);
          }
        }
      }
    }
    if (!explicitSuper && found[0] == null) superclass(cls, List.of(), found, depth);
  }

  private void superclass(ClassTree cls, List<Object> args, String[] found, int depth) {
    if (cls.getExtendsClause() == null) return;
    ClassTree parent = classes.get(simple(cls.getExtendsClause().toString()));
    if (parent != null) run(parent, args, found, depth + 1);
  }

  private static MethodTree constructor(ClassTree cls, List<Object> args) {
    MethodTree fallback = null;
    for (Tree member : cls.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("<init>")
          && m.getBody() != null) {
        if (args != null && m.getParameters().size() == args.size()) return m;
        if (fallback == null || m.getParameters().isEmpty()) fallback = m;
      }
    }
    return fallback;
  }

  /** The costume an asset call adds first (null: none or not known). */
  static String costume(String method, List<Object> a) {
    int n = a.size();
    switch (method == null ? "" : method) {
      case "addCostume" -> {
        if (n == 1 && a.get(0) instanceof String s) return s;
        if (n == 2 && a.get(1) instanceof String s) return s;
        if (n == 6 && a.get(1) instanceof String s && ints(a, 2, 6)) {
          return s + "#" + a.get(2) + "," + a.get(3) + "," + a.get(4) + "," + a.get(5);
        }
      }
      case "addCostumes" -> {
        if (n == 4 && a.get(1) instanceof String s && ints(a, 2, 4)) {
          return s + "#0,0," + a.get(2) + "," + a.get(3);
        }
      }
      case "addAnimation" -> {
        if (n == 3 && a.get(1) instanceof String pattern) {
          try {
            return String.format(pattern, 1);
          } catch (RuntimeException e) {
            return null;
          }
        }
        if (n >= 5 && a.get(1) instanceof String s && ints(a, 3, 5)) {
          int w = (Integer) a.get(3);
          int h = (Integer) a.get(4);
          int index = n >= 6 && a.get(5) instanceof Integer i ? i : 0;
          return n == 7 ? s + "#" + index * w + ",0," + w + "," + h
              : s + "#0," + index * h + "," + w + "," + h;
        }
      }
      default -> { }
    }
    return null;
  }

  private static boolean ints(List<Object> a, int from, int to) {
    for (int i = from; i < to; i++) {
      if (!(a.get(i) instanceof Integer)) return false;
    }
    return true;
  }

  /** Literals, + of known values, known names; anything else is unknown (null). */
  static Object eval(ExpressionTree e, Map<String, Object> env) {
    if (e instanceof LiteralTree lit) return lit.getValue();
    if (e instanceof ParenthesizedTree p) return eval(p.getExpression(), env);
    if (e instanceof ConditionalExpressionTree c && eval(c.getCondition(), env) instanceof Boolean condition) {
      return eval(condition ? c.getTrueExpression() : c.getFalseExpression(), env);
    }
    if (e instanceof IdentifierTree id) return env.get(id.getName().toString());
    if (e instanceof MemberSelectTree m && m.getExpression().toString().equals("this")) {
      return env.get(m.getIdentifier().toString());
    }
    if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS
        && eval(u.getExpression(), env) instanceof Integer i) {
      return -i;
    }
    if (e instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS) {
      Object l = eval(b.getLeftOperand(), env);
      Object r = eval(b.getRightOperand(), env);
      if (l == null || r == null) return null;
      if (l instanceof String || r instanceof String) return String.valueOf(l) + r;
      if (l instanceof Integer x && r instanceof Integer y) return x + y;
    }
    if (e instanceof BinaryTree b && b.getKind() == Tree.Kind.MULTIPLY
        && eval(b.getLeftOperand(), env) instanceof Integer x
        && eval(b.getRightOperand(), env) instanceof Integer y) {
      return x * y;
    }
    return null;
  }

  private static String name(MethodInvocationTree call) {
    ExpressionTree select = call.getMethodSelect();
    if (select instanceof IdentifierTree id) return id.getName().toString();
    if (select instanceof MemberSelectTree m) return m.getIdentifier().toString();
    return null;
  }

  private static boolean onThis(MethodInvocationTree call) {
    return call.getMethodSelect() instanceof IdentifierTree
        || call.getMethodSelect() instanceof MemberSelectTree m
            && m.getExpression().toString().equals("this");
  }

  private static String simple(String type) {
    String plain = type.replaceAll("<.*>", "");
    return plain.substring(plain.lastIndexOf('.') + 1);
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(int i, String text) {
      super(URI.create("string:///S" + i + ".java"), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
