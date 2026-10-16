package org.openpatch.scratch4j.core.region;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import javax.tools.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Parses region statements without treating comments or formatting as executable code. */
final class JavaFragment {
  /** A statement: its tree, its text as javac prints it (for matching), where it is. */
  record Statement(Tree tree, String text, int start, int end, int line, String source) {

    /** The arguments of {@code name = new Type(...)} as written; null for other statements. */
    List<String> writtenArguments() {
      if (!(tree instanceof ExpressionStatementTree statement)
          || !(statement.getExpression() instanceof AssignmentTree assignment)
          || !(assignment.getExpression() instanceof NewClassTree creation)) {
        return null;
      }
      int open = source.indexOf('(', source.indexOf("new"));
      int close = source.lastIndexOf(')');
      return open < 0 || close < open ? null
          : JavaFragment.writtenArguments(source.substring(open + 1, close), creation.getArguments());
    }
  }

  static List<Statement> statements(String body, boolean fields) {
    String prefix = fields ? "class Fragment {\n" : "class Fragment { void setup() {\n";
    String source = prefix + body + (fields ? "\n}" : "\n}}");
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Fragment.java"),
        JavaFileObject.Kind.SOURCE) {
      @Override public CharSequence getCharContent(boolean ignored) { return source; }
    };
    try {
      JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics,
          List.of("-proc:none"), null, List.of(file));
      CompilationUnitTree unit = task.parse().iterator().next();
      if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
        throw new RegionStatements.UnsupportedRegionException("Incomplete Java in the "
            + (fields ? "fields" : "setup") + " region");
      }
      ClassTree type = (ClassTree) unit.getTypeDecls().get(0);
      List<? extends Tree> trees = fields ? type.getMembers()
          : ((MethodTree) type.getMembers().get(0)).getBody().getStatements();
      var positions = Trees.instance(task).getSourcePositions();
      List<Statement> out = new ArrayList<>();
      for (Tree tree : trees) {
        int start = (int) positions.getStartPosition(unit, tree) - prefix.length();
        int end = (int) positions.getEndPosition(unit, tree) - prefix.length();
        if (start < 0 || end < start || end > body.length()) continue;
        String text = tree.toString().strip();
        if (fields && !text.endsWith(";")) text += ";";
        // Explicit this.field is the same field the designer writes as field.
        text = text.replaceFirst("^this\\.(\\w+)(?=\\s*=|\\.)", "$1");
        int line = 1 + (int) body.substring(0, start).chars().filter(c -> c == '\n').count();
        out.add(new Statement(tree, text, start, end, line, body.substring(start, end)));
      }
      return out;
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Cannot parse Java region", e);
    }
  }

  /** The arguments as written ({@code -1.5f}, {@code 0x10}), each a Java literal. */
  static List<String> literalArguments(String arguments) {
    String call = "new Fragment(" + arguments + ");";
    var statement = statements(call, false).get(0);
    NewClassTree creation = (NewClassTree) ((ExpressionStatementTree) statement.tree())
        .getExpression();
    return writtenArguments(arguments, creation.getArguments());
  }

  /** Reuse the parsed creation: each sprite must not start another javac task. */
  private static List<String> writtenArguments(String arguments,
      List<? extends ExpressionTree> trees) {
    List<String> out = new ArrayList<>();
    int from = 0;
    for (int i = 0; i < trees.size(); i++) {
      ExpressionTree argument = trees.get(i);
      if (!literal(argument)) {
        throw new RegionStatements.UnsupportedRegionException(
            "Constructor arguments must be Java literals: " + argument);
      }
      int to = i == trees.size() - 1 ? arguments.length() : nextComma(arguments, from);
      out.add(arguments.substring(from, to).strip());
      from = to + 1;
    }
    return List.copyOf(out);
  }

  /** The next comma outside a string or char literal. */
  private static int nextComma(String text, int from) {
    char quote = 0;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') i++;
        else if (c == quote) quote = 0;
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == ',') {
        return i;
      }
    }
    return text.length();
  }

  private static boolean literal(ExpressionTree tree) {
    return tree instanceof LiteralTree || tree instanceof UnaryTree unary
        && (unary.getKind() == Tree.Kind.UNARY_MINUS || unary.getKind() == Tree.Kind.UNARY_PLUS)
        && unary.getExpression() instanceof LiteralTree number && number.getValue() instanceof Number;
  }

  static Object value(String literal) {
    var statement = statements("new Fragment(" + literal + ");", false).get(0);
    ExpressionTree tree = ((NewClassTree) ((ExpressionStatementTree) statement.tree())
        .getExpression()).getArguments().get(0);
    if (tree instanceof LiteralTree value) return value.getValue();
    if (tree instanceof UnaryTree unary && unary.getExpression() instanceof LiteralTree value
        && value.getValue() instanceof Number number) {
      if (unary.getKind() != Tree.Kind.UNARY_MINUS) return number;
      return switch (number) {
        case Integer n -> -n;
        case Long n -> -n;
        case Float n -> -n;
        case Double n -> -n;
        default -> throw new IllegalArgumentException("Not a numeric literal: " + literal);
      };
    }
    throw new IllegalArgumentException("Not a Java literal: " + literal);
  }
}
