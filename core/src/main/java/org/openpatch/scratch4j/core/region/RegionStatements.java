package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * The statements the stage designer reads and writes in its blocks, matched on
 * javac's syntax tree, so spacing, comments and {@code this.} make no
 * difference:
 *
 * <pre>
 * fields:  Type name;
 * setup:   this.addBackdrop("name");   this.addBackdrop("name", "path"[, stretch]);
 *          this.addSound("name");      this.addSound("name", "path");
 *          name = new Type(literals...);
 *          name = new Text("words", x, y, width[, TextStyle.BOX]);
 *          name.addCostume("costume");  name.switchCostume("costume");
 *          name.setPosition(x, y);      name.setDirection(deg);  name.setSize(percent);
 *          name.setRotationStyle(RotationStyle.LEFT_RIGHT);
 *          name.setWidth(px);  name.setHeight(px);  name.hide();  name.show();
 *          this.add(name);
 * </pre>
 *
 * <p>Any other statement is the student's own: it stays as it is, and the
 * sprites it touches are left to the code (see {@link StageDocument}).
 */
public final class RegionStatements {

  /** The blocks cannot be read at all (unfinished Java). */
  public static final class UnsupportedRegionException extends RuntimeException {
    public UnsupportedRegionException(String message) {
      super(message);
    }
  }

  private RegionStatements() {}

  /** Reads a field of the fields block; false when it is not a plain field declaration. */
  static boolean readField(Tree tree, StageModel model) {
    if (!(tree instanceof VariableTree field) || field.getInitializer() != null
        || !(field.getType() instanceof IdentifierTree type)) {
      return false;
    }
    var flags = field.getModifiers().getFlags();
    if (!field.getModifiers().getAnnotations().isEmpty() || flags.size() > 1) return false;
    String visibility = "";
    for (Modifier flag : flags) {
      if (flag != Modifier.PRIVATE && flag != Modifier.PROTECTED && flag != Modifier.PUBLIC) {
        return false;
      }
      visibility = flag.toString();
    }
    String name = field.getName().toString();
    if (model.sprites().byName(name) != null) return false;
    model.sprites().add(new SpriteRef(name, type.getName().toString()).visibility(visibility));
    return true;
  }

  /**
   * Reads a statement of the setup block into the model; false when it is not
   * one the designer writes. {@code written} is the statement as it stands in
   * the file, for constructor arguments as they were typed ({@code -1.5f}).
   */
  static boolean readSetup(JavaFragment.Statement statement, StageModel model) {
    if (!(statement.tree() instanceof ExpressionStatementTree es)) return false;
    ExpressionTree e = es.getExpression();
    if (e instanceof AssignmentTree assignment) {
      return readCreation(assignment, statement, model);
    }
    if (!(e instanceof MethodInvocationTree call)
        || !(call.getMethodSelect() instanceof MemberSelectTree select)) {
      return false;
    }
    String method = select.getIdentifier().toString();
    List<? extends ExpressionTree> args = call.getArguments();
    if (isThis(select.getExpression())) {
      return readStageCall(method, args, model);
    }
    SpriteRef ref = model.sprites().byName(name(select.getExpression()));
    if (ref == null) return false;
    switch (method) {
      case "addCostume" -> {
        if (args.size() != 1 || string(args.get(0)) == null) return false;
        ref.costume(string(args.get(0)));
      }
      case "switchCostume" -> {
        if (args.size() != 1 || string(args.get(0)) == null) return false;
        ref.currentCostume(string(args.get(0)));
      }
      case "setPosition" -> {
        if (args.size() != 2 || number(args.get(0)) == null || number(args.get(1)) == null) {
          return false;
        }
        ref.setPosition(number(args.get(0)), number(args.get(1)));
      }
      case "setDirection", "setSize", "setWidth", "setHeight" -> {
        if (args.size() != 1 || number(args.get(0)) == null) return false;
        double value = number(args.get(0));
        switch (method) {
          case "setDirection" -> ref.direction(value);
          case "setSize" -> ref.size(value);
          case "setWidth" -> ref.width(value);
          default -> ref.height(value);
        }
      }
      case "setRotationStyle" -> {
        String style = constant(args, "RotationStyle", "ALL_AROUND", "LEFT_RIGHT", "DONT");
        if (style == null) return false;
        ref.rotationStyle(style);
      }
      case "hide", "show" -> {
        if (!args.isEmpty()) return false;
        ref.visible(method.equals("show"));
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  private static boolean readStageCall(String method, List<? extends ExpressionTree> args,
      StageModel model) {
    switch (method) {
      case "addBackdrop" -> {
        if (args.isEmpty() || args.size() > 3 || string(args.get(0)) == null
            || args.size() > 1 && string(args.get(1)) == null
            || args.size() > 2 && bool(args.get(2)) == null) {
          return false;
        }
        String name = string(args.get(0));
        String path = args.size() > 1 ? string(args.get(1)) : null;
        Boolean stretch = args.size() > 2 ? bool(args.get(2)) : null;
        if (stretch != null && name.equals(path)) {
          path = null; // generated built-in stretch uses the name as its path
        }
        model.addBackdrop(new StageModel.Backdrop(name, path, stretch));
      }
      case "addSound" -> {
        if (args.isEmpty() || args.size() > 2 || string(args.get(0)) == null
            || args.size() > 1 && string(args.get(1)) == null) {
          return false;
        }
        model.addSound(new StageModel.Sound(string(args.get(0)),
            args.size() > 1 ? string(args.get(1)) : null));
      }
      case "add" -> {
        SpriteRef ref = args.size() == 1 ? model.sprites().byName(name(args.get(0))) : null;
        if (ref == null) return false;
        ref.added(true);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** {@code name = new Type(...)} for a field of the block. */
  private static boolean readCreation(AssignmentTree assignment, JavaFragment.Statement statement,
      StageModel model) {
    SpriteRef ref = model.sprites().byName(name(assignment.getVariable()));
    if (ref == null || !(assignment.getExpression() instanceof NewClassTree created)
        || created.getClassBody() != null
        || !(created.getIdentifier() instanceof IdentifierTree type)
        || !type.getName().contentEquals(ref.type())) {
      return false;
    }
    List<? extends ExpressionTree> args = created.getArguments();
    if (ref.isText()) {
      if (args.size() < 4 || args.size() > 5 || string(args.get(0)) == null
          || number(args.get(1)) == null || number(args.get(2)) == null
          || number(args.get(3)) == null) {
        return false;
      }
      String style = null;
      if (args.size() == 5) {
        String constant = constant(args.subList(4, 5), "TextStyle", "PLAIN", "BOX", "SPEAK",
            "THINK");
        if (constant == null) return false;
        style = args.get(4).toString();
      }
      // the words as written in the source, escapes and all (the designer keeps them so)
      String written = args.get(0).toString();
      ref.text(written.substring(1, written.length() - 1));
      ref.setPosition(number(args.get(1)), number(args.get(2)));
      ref.textWidth(number(args.get(3)));
      ref.textStyle(style);
    } else {
      List<String> written;
      try {
        written = statement.writtenArguments();
      } catch (UnsupportedRegionException notLiterals) {
        return false;
      }
      if (written == null) return false;
      ref.constructorArguments(written);
    }
    ref.instantiated(true);
    return true;
  }

  private static boolean isThis(ExpressionTree tree) {
    return tree instanceof IdentifierTree id && id.getName().contentEquals("this");
  }

  /** {@code name} or {@code this.name}; null for anything else. */
  static String name(ExpressionTree tree) {
    if (tree instanceof IdentifierTree id) return id.getName().toString();
    if (tree instanceof MemberSelectTree select && isThis(select.getExpression())) {
      return select.getIdentifier().toString();
    }
    return null;
  }

  private static String string(ExpressionTree tree) {
    return tree instanceof LiteralTree literal && literal.getValue() instanceof String value
        ? value : null;
  }

  private static Boolean bool(ExpressionTree tree) {
    return tree instanceof LiteralTree literal && literal.getValue() instanceof Boolean value
        ? value : null;
  }

  private static Double number(ExpressionTree tree) {
    if (tree instanceof LiteralTree literal && literal.getValue() instanceof Number value
        && !(literal.getValue() instanceof Character)) {
      return value.doubleValue();
    }
    if (tree instanceof UnaryTree unary && (unary.getKind() == Tree.Kind.UNARY_MINUS
        || unary.getKind() == Tree.Kind.UNARY_PLUS)) {
      Double inner = number(unary.getExpression());
      if (inner == null || !(unary.getExpression() instanceof LiteralTree)) return null;
      return unary.getKind() == Tree.Kind.UNARY_MINUS ? -inner : inner;
    }
    return null;
  }

  /**
   * {@code Type.CONSTANT} (also fully qualified) as the single argument, as it
   * is written; null when it is something else.
   */
  private static String constant(List<? extends ExpressionTree> args, String type,
      String... constants) {
    if (args.size() != 1 || !(args.get(0) instanceof MemberSelectTree select)) return null;
    String owner = select.getExpression().toString();
    if (!owner.equals(type) && !owner.equals("org.openpatch.scratch." + type)) return null;
    String name = select.getIdentifier().toString();
    for (String constant : constants) {
      if (constant.equals(name)) return args.get(0).toString();
    }
    return null;
  }

  /** Generates the canonical fields-region body (no trailing newline beyond the last line). */
  public static String generateFields(StageModel model, String indent) {
    StringBuilder sb = new StringBuilder();
    for (SpriteRef ref : model.sprites()) {
      // the field keeps its modifier; new sprites are private
      sb.append(indent).append(ref.visibility().isEmpty() ? "" : ref.visibility() + " ")
          .append(ref.type()).append(' ').append(ref.name()).append(";\n");
    }
    return sb.toString();
  }

  /** Generates the canonical setup-region body. */
  public static String generateSetup(StageModel model, String indent) {
    StringBuilder sb = new StringBuilder();
    for (StageModel.Backdrop backdrop : model.backdrops()) {
      sb.append(indent).append("this.addBackdrop(\"").append(backdrop.name()).append('"');
      if (backdrop.path() != null || backdrop.stretch() != null) {
        sb.append(", \"").append(backdrop.path() == null ? backdrop.name()
            : backdrop.path()).append('"');
      }
      if (backdrop.stretch() != null) {
        sb.append(", ").append(backdrop.stretch());
      }
      sb.append(");\n");
    }
    for (StageModel.Sound sound : model.sounds()) {
      sb.append(indent).append("this.addSound(\"").append(sound.name()).append('"');
      if (sound.path() != null) {
        sb.append(", \"").append(sound.path()).append('"');
      }
      sb.append(");\n");
    }
    for (SpriteRef ref : model.sprites()) {
      if (ref.isText()) {
        sb.append(indent).append(ref.name()).append(" = new Text(\"")
            .append(ref.text() == null ? "" : ref.text()).append("\", ")
            .append(number(ref.x())).append(", ").append(number(ref.y())).append(", ")
            .append(number(ref.textWidth()));
        if (ref.textStyle() != null) {
          sb.append(", ").append(ref.textStyle());
        }
        sb.append(");\n");
        if (!ref.isVisible()) {
          sb.append(indent).append(ref.name()).append(".hide();\n");
        }
        sb.append(indent).append("this.add(").append(ref.name()).append(");\n");
        continue;
      }
      sb.append(indent).append(ref.name()).append(" = new ").append(ref.type()).append('(')
          .append(String.join(", ", ref.constructorArguments())).append(");\n");
      if (ref.costume() != null) {
        sb.append(indent).append(ref.name()).append(".addCostume(\"")
            .append(ref.costume()).append("\");\n");
      }
      if (ref.currentCostume() != null) {
        sb.append(indent).append(ref.name()).append(".switchCostume(\"")
            .append(ref.currentCostume()).append("\");\n");
      }
      if (ref.hasPosition()) {
        sb.append(indent).append(ref.name()).append(".setPosition(")
            .append(number(ref.x())).append(", ").append(number(ref.y())).append(");\n");
      }
      if (ref.hasDirection()) {
        sb.append(indent).append(ref.name()).append(".setDirection(")
            .append(number(ref.direction())).append(");\n");
      }
      if (ref.rotationStyle() != null) {
        // older designer versions wrote org.openpatch.scratch.RotationStyle...
        sb.append(indent).append(ref.name()).append(".setRotationStyle(")
            .append(ref.rotationStyle().replaceFirst("^org\\.openpatch\\.scratch\\.", ""))
            .append(");\n");
      }
      if (ref.hasSize()) {
        sb.append(indent).append(ref.name()).append(".setSize(")
            .append(number(ref.size())).append(");\n");
      }
      if (ref.hasWidth()) {
        sb.append(indent).append(ref.name()).append(".setWidth(")
            .append(number(ref.width())).append(");\n");
      }
      if (ref.hasHeight()) {
        sb.append(indent).append(ref.name()).append(".setHeight(")
            .append(number(ref.height())).append(");\n");
      }
      if (!ref.isVisible()) {
        sb.append(indent).append(ref.name()).append(".hide();\n");
      }
      sb.append(indent).append("this.add(").append(ref.name()).append(");\n");
    }
    return sb.toString();
  }

  /** Numbers print without a trailing .0 when they are whole. */
  public static String number(double value) {
    if (value == Math.floor(value) && !Double.isInfinite(value)) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }
}
