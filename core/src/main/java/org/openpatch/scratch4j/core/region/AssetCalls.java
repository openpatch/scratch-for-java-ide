package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The asset calls a sprite's constructors make, in the order they run, with
 * every overload the library has ({@code addCostume(name[, path[, x, y, w, h]])},
 * {@code addCostumes(prefix, sheet, w, h)}, {@code addAnimation(name, pattern,
 * frames)}, {@code addAnimation(name, sheet, frames, w, h[, row])},
 * {@code addAnimation(name, sheet, frames, w, h, column, useColumns)},
 * {@code addSound(name[, path])}), however they are formatted and with or
 * without {@code this.}. Arguments that are not literals are null.
 */
public final class AssetCalls {

  /** One call: the method, its literal arguments, and where it is (offsets, 1-based line). */
  public record Call(String method, List<Object> args, int start, int end, long line) {

    public String string(int i) {
      return i < args.size() && args.get(i) instanceof String s ? s : null;
    }

    public Integer number(int i) {
      return i < args.size() && args.get(i) instanceof Integer n ? n : null;
    }

    public Boolean bool(int i) {
      return i < args.size() && args.get(i) instanceof Boolean b ? b : null;
    }
  }

  private static final Set<String> METHODS = Set.of("addCostume", "addCostumes",
      "addAnimation", "addSound");

  private AssetCalls() {}

  /** The calls in the class's constructors, in source order. */
  public static List<Call> of(String source) {
    List<Call> out = new ArrayList<>();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) return out;
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of(), null,
        List.of(new Source(source)));
    try {
      var positions = Trees.instance(task).getSourcePositions();
      for (CompilationUnitTree unit : task.parse()) {
        new TreeScanner<Void, Boolean>() {
          @Override
          public Void visitMethod(MethodTree method, Boolean inConstructor) {
            return super.visitMethod(method, method.getName().contentEquals("<init>"));
          }

          @Override
          public Void visitClass(com.sun.source.tree.ClassTree cls, Boolean inConstructor) {
            // nested classes are other sprites
            return unit.getTypeDecls().contains(cls) ? super.visitClass(cls, false) : null;
          }

          @Override
          public Void visitMethodInvocation(MethodInvocationTree call, Boolean inConstructor) {
            String name = name(call);
            if (Boolean.TRUE.equals(inConstructor) && name != null && METHODS.contains(name)
                && onThis(call)) {
              List<Object> args = new ArrayList<>();
              for (ExpressionTree arg : call.getArguments()) {
                args.add(literal(arg));
              }
              int start = (int) positions.getStartPosition(unit, call);
              int end = (int) positions.getEndPosition(unit, call);
              out.add(new Call(name, args, start, end, unit.getLineMap().getLineNumber(start)));
            }
            return super.visitMethodInvocation(call, inConstructor);
          }
        }.scan(unit, false);
      }
    } catch (IOException | RuntimeException e) {
      return out;
    }
    out.sort(java.util.Comparator.comparingInt(Call::start));
    return out;
  }

  /**
   * The sprite's first costume, as the designer draws it: a built-in name, a
   * file path, or a part of a sheet as {@code path#x,y,w,h} (null: none).
   */
  public static String firstLook(String source) {
    for (Call c : of(source)) {
      int n = c.args().size();
      switch (c.method()) {
        case "addCostume" -> {
          if (n == 1 && c.string(0) != null) return c.string(0);
          if (n == 2 && c.string(1) != null) return c.string(1);
          if (n == 6 && c.string(1) != null && c.number(2) != null && c.number(3) != null
              && c.number(4) != null && c.number(5) != null) {
            return crop(c.string(1), c.number(2), c.number(3), c.number(4), c.number(5));
          }
        }
        case "addCostumes" -> {
          if (n == 4 && c.string(1) != null && c.number(2) != null && c.number(3) != null) {
            return crop(c.string(1), 0, 0, c.number(2), c.number(3));
          }
        }
        case "addAnimation" -> {
          if (n == 3 && c.string(1) != null) {
            String pattern = c.string(1);
            try {
              return String.format(pattern, 1);
            } catch (RuntimeException e) {
              return null;
            }
          }
          if (n >= 5 && c.string(1) != null && c.number(3) != null && c.number(4) != null) {
            int w = c.number(3);
            int h = c.number(4);
            int index = n >= 6 && c.number(5) != null ? c.number(5) : 0;
            // (…, column, true): frames go down a column; (…, row): along a row
            return n == 7 ? crop(c.string(1), index * w, 0, w, h)
                : crop(c.string(1), 0, index * h, w, h);
          }
        }
        default -> { }
      }
    }
    return null;
  }

  private static String crop(String path, int x, int y, int w, int h) {
    return path + "#" + x + "," + y + "," + w + "," + h;
  }

  private static String name(MethodInvocationTree call) {
    ExpressionTree select = call.getMethodSelect();
    if (select instanceof IdentifierTree id) return id.getName().toString();
    if (select instanceof MemberSelectTree m) return m.getIdentifier().toString();
    return null;
  }

  /** {@code addCostume(...)} or {@code this.addCostume(...)}, not {@code other.addCostume}. */
  private static boolean onThis(MethodInvocationTree call) {
    return call.getMethodSelect() instanceof IdentifierTree
        || call.getMethodSelect() instanceof MemberSelectTree m
            && m.getExpression().toString().equals("this");
  }

  private static Object literal(ExpressionTree arg) {
    if (arg instanceof LiteralTree lit) {
      return lit.getValue();
    }
    if (arg instanceof UnaryTree unary && unary.getKind() == Tree.Kind.UNARY_MINUS
        && unary.getExpression() instanceof LiteralTree lit && lit.getValue() instanceof Integer i) {
      return -i;
    }
    return null;
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(String text) {
      super(URI.create("string:///Sprite.java"), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
