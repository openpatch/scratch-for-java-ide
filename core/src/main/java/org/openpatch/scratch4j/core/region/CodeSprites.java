package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

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
 * The sprites a stage's constructor adds in its own code (outside the
 * designer's regions), where they can be worked out without running: {@code
 * this.add(new Coin(-240 + i * 112, GROUND_TOP + 45))} in a {@code for} loop
 * with fixed bounds, {@code var x = new X(); x.setPosition(...); this.add(x)}.
 * Positions come from the stage's own calls or from the sprite's constructor
 * ({@code setX(x)}, {@code setPosition(...)}, through {@code super(...)}).
 * The designer shows them as ghosts.
 */
public final class CodeSprites {

  /** A sprite made in code: its class, where it starts, and the line that makes it. */
  public record Ghost(String type, double x, double y, int line) {}

  private static final int MAX_LOOP = 64;
  private static final int MAX_GHOSTS = 200;

  private final Map<String, ClassTree> classes = new LinkedHashMap<>();
  private final Map<ClassTree, CompilationUnitTree> units = new HashMap<>();
  private final Map<String, Object> constants = new HashMap<>();
  private final Path root;
  private SourcePositions positions;

  private CodeSprites(Path root, List<String> sources) {
    this.root = root;
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) return;
    List<Source> files = new ArrayList<>();
    for (int i = 0; i < sources.size(); i++) files.add(new Source(i, sources.get(i)));
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of(), null, files);
    positions = Trees.instance(task).getSourcePositions();
    try {
      for (CompilationUnitTree unit : task.parse()) {
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitClass(ClassTree cls, Void p) {
            classes.putIfAbsent(cls.getSimpleName().toString(), cls);
            units.put(cls, unit);
            return super.visitClass(cls, p);
          }
        }.scan(unit, null);
      }
    } catch (IOException | RuntimeException ignored) {
      // what parsed is used
    }
    // constants: static final fields with values (GROUND_TOP, COINS), as Name and Class.Name
    for (var e : classes.entrySet()) {
      for (Tree member : e.getValue().getMembers()) {
        if (member instanceof VariableTree v && v.getInitializer() != null
            && v.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.FINAL)) {
          Object value = eval(v.getInitializer(), constants);
          if (value != null) {
            constants.putIfAbsent(v.getName().toString(), value);
            constants.put(e.getKey() + "." + v.getName(), value);
          }
        }
      }
    }
  }

  /** The ghosts of {@code stageClass} in the project at {@code root}. */
  public static List<Ghost> of(Path root, String stageClass) {
    List<String> sources = new ArrayList<>();
    try (Stream<Path> files = Files.list(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
        sources.add(Files.readString(f, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      return List.of();
    }
    return new CodeSprites(root, sources).ghosts(stageClass);
  }

  private List<Ghost> ghosts(String stageClass) {
    ClassTree stage = classes.get(stageClass);
    if (stage == null) return List.of();
    CompilationUnitTree unit = units.get(stage);
    String source;
    try {
      source = unit.getSourceFile().getCharContent(true).toString();
    } catch (IOException e) {
      return List.of();
    }
    // the designer's own regions are not ghosts
    List<int[]> managed = new ArrayList<>();
    try {
      for (Region r : RegionParser.parse(source)) {
        managed.add(new int[] {r.beginOffset(), r.endOffset()});
      }
    } catch (RuntimeException ignored) {
      // no regions
    }
    List<Ghost> out = new ArrayList<>();
    for (Tree member : stage.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("<init>")
          && m.getBody() != null) {
        Map<String, Object> env = new HashMap<>(constants);
        for (Tree f : stage.getMembers()) {
          if (f instanceof VariableTree v && v.getInitializer() != null) {
            Object value = eval(v.getInitializer(), env);
            if (value != null) env.put(v.getName().toString(), value);
          }
        }
        run(m.getBody().getStatements(), env, new HashMap<>(), unit, managed, out);
        break;
      }
    }
    return out;
  }

  /** A sprite under construction in the stage's code (a local variable). */
  private static final class Made {
    final String type;
    double x;
    double y;
    final int line;

    Made(String type, double x, double y, int line) {
      this.type = type;
      this.x = x;
      this.y = y;
      this.line = line;
    }
  }

  private void run(List<? extends StatementTree> statements, Map<String, Object> env,
      Map<String, Made> locals, CompilationUnitTree unit, List<int[]> managed, List<Ghost> out) {
    for (StatementTree st : statements) {
      if (out.size() >= MAX_GHOSTS) return;
      long start = positions.getStartPosition(unit, st);
      boolean inRegion = managed.stream().anyMatch(r -> start >= r[0] && start < r[1]);
      if (inRegion) continue;
      if (st instanceof BlockTree block) {
        run(block.getStatements(), env, locals, unit, managed, out);
      } else if (st instanceof ForLoopTree loop) {
        forLoop(loop, env, locals, unit, managed, out);
      } else if (st instanceof VariableTree v && v.getInitializer() != null) {
        if (v.getInitializer() instanceof NewClassTree created) {
          Made made = create(created, env, unit);
          if (made != null) locals.put(v.getName().toString(), made);
        } else {
          Object value = eval(v.getInitializer(), env);
          if (value != null) env.put(v.getName().toString(), value);
        }
      } else if (st instanceof ExpressionStatementTree es) {
        ExpressionTree e = es.getExpression();
        if (e instanceof AssignmentTree a && a.getExpression() instanceof NewClassTree created) {
          Made made = create(created, env, unit);
          if (made != null) locals.put(simpleName(a.getVariable()), made);
        } else if (e instanceof MethodInvocationTree call) {
          invocation(call, env, locals, unit, out);
        }
      }
    }
  }

  private void invocation(MethodInvocationTree call, Map<String, Object> env,
      Map<String, Made> locals, CompilationUnitTree unit, List<Ghost> out) {
    String name = methodName(call);
    String target = call.getMethodSelect() instanceof MemberSelectTree m
        ? m.getExpression().toString() : "this";
    List<? extends ExpressionTree> args = call.getArguments();
    if ("add".equals(name) && target.equals("this") && args.size() == 1) {
      ExpressionTree arg = args.get(0);
      Made made = arg instanceof NewClassTree created ? create(created, env, unit)
          : locals.get(arg.toString().replaceFirst("^this\\.", ""));
      if (made != null) out.add(new Ghost(made.type, made.x, made.y, made.line));
      return;
    }
    Made made = locals.get(target.replaceFirst("^this\\.", ""));
    if (made == null) return;
    position(made, name, args, env);
  }

  private static void position(Made made, String name, List<? extends ExpressionTree> args,
      Map<String, Object> env) {
    if ("setPosition".equals(name) && args.size() == 2
        && eval(args.get(0), env) instanceof Number x && eval(args.get(1), env) instanceof Number y) {
      made.x = x.doubleValue();
      made.y = y.doubleValue();
    } else if ("setX".equals(name) && args.size() == 1
        && eval(args.get(0), env) instanceof Number x) {
      made.x = x.doubleValue();
    } else if ("setY".equals(name) && args.size() == 1
        && eval(args.get(0), env) instanceof Number y) {
      made.y = y.doubleValue();
    }
  }

  private void forLoop(ForLoopTree loop, Map<String, Object> env, Map<String, Made> locals,
      CompilationUnitTree unit, List<int[]> managed, List<Ghost> out) {
    if (loop.getInitializer().size() != 1 || !(loop.getInitializer().get(0) instanceof
        VariableTree counter) || counter.getInitializer() == null || loop.getUpdate().size() != 1) {
      return;
    }
    String name = counter.getName().toString();
    Map<String, Object> inner = new HashMap<>(env);
    Object start = eval(counter.getInitializer(), inner);
    if (!(start instanceof Number)) return;
    inner.put(name, start);
    for (int i = 0; i < MAX_LOOP; i++) {
      if (!(eval(loop.getCondition(), inner) instanceof Boolean go) || !go) return;
      run(loop.getStatement() instanceof BlockTree b ? b.getStatements()
          : List.of(loop.getStatement()), inner, locals, unit, managed, out);
      ExpressionTree update = loop.getUpdate().get(0).getExpression();
      Object now = inner.get(name);
      Object next = null;
      if (update instanceof UnaryTree u && (u.getKind() == Tree.Kind.POSTFIX_INCREMENT
          || u.getKind() == Tree.Kind.PREFIX_INCREMENT)) {
        next = add(now, 1);
      } else if (update instanceof UnaryTree u && (u.getKind() == Tree.Kind.POSTFIX_DECREMENT
          || u.getKind() == Tree.Kind.PREFIX_DECREMENT)) {
        next = add(now, -1);
      } else if (update instanceof CompoundAssignmentTree c
          && eval(c.getExpression(), inner) instanceof Number step) {
        next = switch (c.getKind()) {
          case PLUS_ASSIGNMENT -> add(now, step);
          case MINUS_ASSIGNMENT -> add(now, negate(step));
          default -> null;
        };
      }
      if (next == null) return;
      inner.put(name, next);
    }
  }

  /** {@code new X(args)} for a project sprite: its start position from its constructor. */
  private Made create(NewClassTree created, Map<String, Object> env, CompilationUnitTree unit) {
    String type = simpleName(created.getIdentifier());
    if (!"Sprite".equals(DesignerRegions.kindOf(root, type))) return null;
    List<Object> args = new ArrayList<>();
    for (ExpressionTree a : created.getArguments()) args.add(eval(a, env));
    Made made = new Made(type, 0, 0, (int) unit.getLineMap().getLineNumber(
        positions.getStartPosition(unit, created)));
    construct(classes.get(type), args, made, 0);
    return made;
  }

  /** Runs a sprite constructor's position calls (and its super(...) chain). */
  private void construct(ClassTree cls, List<Object> args, Made made, int depth) {
    if (cls == null || depth > 8) return;
    MethodTree ctor = null;
    for (Tree member : cls.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("<init>")
          && m.getParameters().size() == args.size() && m.getBody() != null) {
        ctor = m;
      }
    }
    if (ctor == null) return;
    Map<String, Object> env = new HashMap<>(constants);
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i) != null) env.put(ctor.getParameters().get(i).getName().toString(), args.get(i));
    }
    for (StatementTree st : ctor.getBody().getStatements()) {
      if (st instanceof ExpressionStatementTree es && es.getExpression()
          instanceof MethodInvocationTree call) {
        String name = methodName(call);
        if ("super".equals(name) || "this".equals(name)) {
          List<Object> next = new ArrayList<>();
          for (ExpressionTree a : call.getArguments()) next.add(eval(a, env));
          ClassTree target = "this".equals(name) ? cls : cls.getExtendsClause() == null ? null
              : classes.get(simpleName(cls.getExtendsClause()));
          construct(target, next, made, depth + 1);
        } else if (!(call.getMethodSelect() instanceof MemberSelectTree m)
            || m.getExpression().toString().equals("this")) {
          position(made, name, call.getArguments(), env);
        }
      } else if (st instanceof VariableTree v && v.getInitializer() != null) {
        Object value = eval(v.getInitializer(), env);
        if (value != null) env.put(v.getName().toString(), value);
      }
    }
  }

  /** Numbers (as doubles when they have to be), booleans of comparisons, known names. */
  static Object eval(ExpressionTree e, Map<String, Object> env) {
    if (e == null) return null;
    if (e instanceof LiteralTree lit) {
      Object v = lit.getValue();
      return v instanceof Long l ? (Object) l.doubleValue() : v instanceof Float f
          ? (Object) f.doubleValue() : v;
    }
    if (e instanceof ParenthesizedTree p) return eval(p.getExpression(), env);
    if (e instanceof TypeCastTree c) return eval(c.getExpression(), env);
    if (e instanceof IdentifierTree id) return env.get(id.getName().toString());
    if (e instanceof MemberSelectTree m) {
      String qualifier = m.getExpression().toString();
      return qualifier.equals("this") ? env.get(m.getIdentifier().toString())
          : env.get(qualifier + "." + m.getIdentifier());
    }
    if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS
        && eval(u.getExpression(), env) instanceof Number n) {
      return negate(n);
    }
    if (e instanceof BinaryTree b) {
      Object l = eval(b.getLeftOperand(), env);
      Object r = eval(b.getRightOperand(), env);
      if (b.getKind() == Tree.Kind.PLUS && (l instanceof String || r instanceof String)
          && l != null && r != null) {
        return String.valueOf(l) + r;
      }
      if (!(l instanceof Number x) || !(r instanceof Number y)) return null;
      boolean ints = x instanceof Integer && y instanceof Integer;
      double a = x.doubleValue();
      double c = y.doubleValue();
      return switch (b.getKind()) {
        case PLUS -> ints ? (Object) (x.intValue() + y.intValue()) : a + c;
        case MINUS -> ints ? (Object) (x.intValue() - y.intValue()) : a - c;
        case MULTIPLY -> ints ? (Object) (x.intValue() * y.intValue()) : a * c;
        case DIVIDE -> c == 0 ? null : ints ? (Object) (x.intValue() / y.intValue()) : a / c;
        case LESS_THAN -> a < c;
        case LESS_THAN_EQUAL -> a <= c;
        case GREATER_THAN -> a > c;
        case GREATER_THAN_EQUAL -> a >= c;
        default -> null;
      };
    }
    return null;
  }

  private static Object add(Object value, Number step) {
    if (value instanceof Integer i && step instanceof Integer s) return i + s;
    return value instanceof Number n ? (Object) (n.doubleValue() + step.doubleValue()) : null;
  }

  private static Number negate(Number n) {
    return n instanceof Integer i ? (Number) (-i) : (Number) (-n.doubleValue());
  }

  private static String methodName(MethodInvocationTree call) {
    ExpressionTree select = call.getMethodSelect();
    if (select instanceof IdentifierTree id) return id.getName().toString();
    if (select instanceof MemberSelectTree m) return m.getIdentifier().toString();
    return "";
  }

  private static String simpleName(Tree tree) {
    String s = tree.toString().replaceAll("<.*>", "");
    return s.substring(s.lastIndexOf('.') + 1);
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(int i, String text) {
      super(URI.create("string:///G" + i + ".java"), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
