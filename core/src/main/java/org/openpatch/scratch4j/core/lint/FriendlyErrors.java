package org.openpatch.scratch4j.core.lint;

import org.openpatch.scratch4j.core.compile.CompilerDiagnostic;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiler error in the student's words: a headline that names what is
 * wrong in their code ("Java does not know a variable named {@code numer}")
 * and an explanation what to try. The names and types come from javac's
 * English message ({@link CompilerDiagnostic#englishMessage()}) and the
 * source line; errors without a curated headline keep javac's message and the
 * generic {@link DiagnosticsExplanations explanation}.
 *
 * <p>Texts live in the {@code explanations} bundle as {@code friendly.<case>.title}
 * (headline) and {@code friendly.<case>} (explanation, optional); {@code {0}},
 * {@code {1}}, ... are replaced literally, no {@link java.text.MessageFormat}
 * quoting.
 */
public final class FriendlyErrors {

  /**
   * The headline, the explanation (null if none), extra "did you mean" names,
   * and the {@link QuickFixes one-click fix} with its data (null if none).
   */
  public record Friendly(String title, String explanation, List<String> suggestions,
      String fix, String fixData) {

    public Friendly(String title, String explanation, List<String> suggestions) {
      this(title, explanation, suggestions, null, null);
    }

    Friendly withFix(String fix, String fixData) {
      return new Friendly(title, explanation, suggestions, fix, fixData);
    }
  }

  /**
   * Where the error is. {@code lines} is the whole source file, {@code className}
   * its top-level class; {@code importFor} gives the import line for a class
   * name the project could import (null if none); {@code apiNames} are the
   * library's method names.
   */
  public record Context(List<String> lines, String className,
      Function<String, String> importFor, Collection<String> apiNames) {

    String line(long number) {
      return number >= 1 && number <= lines.size() ? lines.get((int) number - 1) : "";
    }
  }

  /**
   * Parser errors: after one of these, the following errors in the same file
   * are often only its echo and go away with it.
   */
  public static final Set<String> SYNTAX_ERRORS = Set.of(
      "compiler.err.expected", "compiler.err.expected2", "compiler.err.expected3",
      "compiler.err.expected4", "compiler.err.premature.eof",
      "compiler.err.illegal.start.of.expr", "compiler.err.illegal.start.of.type",
      "compiler.err.illegal.start.of.stmt", "compiler.err.not.stmt",
      "compiler.err.class.method.or.field.expected", "compiler.err.unclosed.str.lit",
      "compiler.err.unclosed.char.lit", "compiler.err.unclosed.comment",
      "compiler.err.illegal.char", "compiler.err.else.without.if", "compiler.err.orphaned",
      "compiler.err.invalid.meth.decl.ret.type.req", "compiler.err.variable.not.allowed");

  private static final Map<String, String> TOKENS = Map.of(
      ";", "semicolon", ")", "paren.close", "(", "paren.open", "}", "brace.close",
      "{", "brace.open", "]", "bracket.close", "[", "bracket.open", ",", "comma",
      "=", "equals", ".", "dot");

  private static final Pattern EXPECTED = Pattern.compile("^'(.+)' expected$");
  private static final Pattern SYMBOL = Pattern.compile(
      "symbol:\\s+(variable|method|class)\\s+([\\w$]+)");
  private static final Pattern NON_STATIC = Pattern.compile(
      "^non-static (method|variable) ([\\w$]+)");
  private static final Pattern OPERANDS = Pattern.compile(
      "bad operand types for binary operator '(.+)'\\s+first type:\\s+(\\S+)\\s+"
          + "second type:\\s+(\\S+)");
  private static final Pattern LOSSY = Pattern.compile(
      "possible lossy conversion from (\\S+) to (\\S+)");
  private static final Pattern CONVERT = Pattern.compile(
      "incompatible types: (\\S+) cannot be converted to (\\S+)");
  private static final Pattern DEREF = Pattern.compile("^(\\S+) cannot be dereferenced");
  private static final Pattern PRIVATE = Pattern.compile("^(\\S+) has private access in (\\S+)");
  private static final Pattern FINAL = Pattern.compile("final variable ([\\w$]+)");
  private static final Pattern DEFINED = Pattern.compile(
      "^\\S+ ([\\w$]+(?:\\(.*?\\))?) is already defined");
  private static final Pattern UNINITIALIZED = Pattern.compile("^variable ([\\w$]+) might not");
  private static final Pattern APPLY = Pattern.compile(
      "^(?:method|constructor) ([\\w$]+) in \\S+ \\S+ cannot be applied to given types;"
          + "\\s+required: (.+?)\\s+found:\\s+(.+?)\\s+reason:", Pattern.DOTALL);
  private static final Pattern ILLEGAL_CHAR = Pattern.compile("illegal character: '(.+)'");
  private static final Pattern WRONG_FILE = Pattern.compile(
      "^class ([\\w$]+) is public, should be declared in a file named (\\S+)");
  private static final Pattern NOT_ABSTRACT = Pattern.compile(
      "^(\\S+) is not abstract and does not override abstract method (\\S+) in (\\S+)");
  private static final Pattern ABSTRACT = Pattern.compile("^(\\S+) is abstract; cannot be");
  private static final Pattern UNREPORTED = Pattern.compile("^unreported exception (\\S+);");
  /** {@code if (x = 2)}: an assignment where a condition belongs. */
  private static final Pattern ASSIGN_IN_CONDITION = Pattern.compile(
      "\\b(?:if|while)\\s*\\(.*[^=!<>+\\-*/%&|^]=[^=].*\\)");
  /** A method header where javac points: {@code public void inner(}. */
  private static final Pattern METHOD_DECLARATION = Pattern.compile(
      "(?:(?:public|private|protected|static|final)\\s+)*[\\w<>\\[\\]]+\\s+\\w+\\s*\\(");
  private static final Pattern METHOD_NAME = Pattern.compile("([\\w$]+)\\s*\\(");

  private final ResourceBundle bundle;

  public FriendlyErrors(Language language) {
    this.bundle = ResourceBundle.getBundle("org.openpatch.scratch4j.core.lint.explanations",
        language == Language.DE ? Locale.GERMAN : Locale.ENGLISH,
        FriendlyErrors.class.getClassLoader());
  }

  /** The friendly version of one error; never null. */
  public Friendly describe(CompilerDiagnostic d, Context context) {
    String code = d.code() == null ? "" : d.code();
    String english = d.englishMessage() == null ? "" : d.englishMessage();
    String line = context.line(d.line());
    Matcher m;
    Friendly friendly = switch (code) {
      case "compiler.err.expected" -> {
        m = EXPECTED.matcher(english);
        if (m.matches() && TOKENS.containsKey(m.group(1))) {
          Friendly expected = of("expected", text("token." + TOKENS.get(m.group(1))));
          yield m.group(1).equals(";") ? expected.withFix(QuickFixes.SEMICOLON, null)
              : expected;
        }
        yield english.startsWith("<identifier>") ? of("expected.name") : null;
      }
      case "compiler.err.premature.eof" -> of("eof");
      case "compiler.err.class.method.or.field.expected" -> of("outside.class");
      case "compiler.err.cant.resolve", "compiler.err.cant.resolve.location",
          "compiler.err.cant.resolve.args", "compiler.err.cant.resolve.location.args" -> {
        m = SYMBOL.matcher(english);
        if (!m.find()) yield null;
        String kind = m.group(1);
        String name = m.group(2);
        String importLine = kind.equals("class") ? context.importFor().apply(name) : null;
        if (importLine != null) {
          yield of("import", name, importLine).withFix(QuickFixes.IMPORT, importLine);
        }
        yield of("unknown." + kind, name);
      }
      case "compiler.err.non-static.cant.be.ref" -> {
        m = NON_STATIC.matcher(english);
        if (!m.find()) yield null;
        String name = m.group(2) + (m.group(1).equals("method") ? "()" : "");
        yield name.equals("this") ? of("static.this") : of("static", name);
      }
      case "compiler.err.invalid.meth.decl.ret.type.req" -> {
        String name = nameAt(line, d.column());
        if (name.isEmpty()) yield null;
        boolean constructor = context.className() != null
            && !DidYouMean.suggest(name, List.of(context.className()), 1).isEmpty();
        yield constructor ? of("constructor", name, context.className())
            : of("return.type", name);
      }
      case "compiler.err.method.does.not.override.superclass" -> {
        String name = overridingMethod(context, d.line());
        if (name == null) yield null;
        Friendly f = of("override", name);
        yield new Friendly(f.title(), f.explanation(),
            DidYouMean.suggest(name, context.apiNames(), 3));
      }
      case "compiler.err.operator.cant.be.applied.1" -> {
        m = OPERANDS.matcher(english);
        if (!m.find()) yield null;
        String left = simple(m.group(2));
        String right = simple(m.group(3));
        boolean text = left.equals("String") || right.equals("String");
        yield of(text ? "operator.text" : "operator", m.group(1), left, right);
      }
      case "compiler.err.prob.found.req" -> {
        if (english.contains("unexpected return value")) yield of("return.void");
        if (english.contains("missing return value")) yield of("return.missing");
        m = LOSSY.matcher(english);
        if (m.find()) yield of("lossy", m.group(1), m.group(2));
        m = CONVERT.matcher(english);
        if (!m.find()) yield null;
        String from = simple(m.group(1));
        String to = simple(m.group(2));
        if (to.equals("boolean") && ASSIGN_IN_CONDITION.matcher(line).find()) {
          yield of("assign.condition");
        }
        if (to.equals("boolean")) yield of("condition", from);
        if (from.equals("String") && isNumber(to)) yield of("convert.text", to);
        yield of("convert", from, to);
      }
      case "compiler.err.cant.deref" -> {
        m = DEREF.matcher(english);
        yield m.find() ? of("deref", m.group(1)) : null;
      }
      case "compiler.err.report.access" -> {
        m = PRIVATE.matcher(english);
        yield m.find() ? of("private", m.group(1), simple(m.group(2))) : null;
      }
      case "compiler.err.cant.assign.val.to.var" -> {
        m = FINAL.matcher(english);
        yield m.find() ? of("final", m.group(1)) : null;
      }
      case "compiler.err.already.defined" -> {
        m = DEFINED.matcher(english);
        yield m.find() ? of("defined", m.group(1)) : null;
      }
      case "compiler.err.var.might.not.have.been.initialized" -> {
        m = UNINITIALIZED.matcher(english);
        yield m.find() ? of("uninitialized", m.group(1)) : null;
      }
      case "compiler.err.cant.apply.symbol" -> {
        m = APPLY.matcher(english);
        if (!m.find()) yield null;
        yield of("arguments", m.group(1), arguments(m.group(2)), arguments(m.group(3)));
      }
      case "compiler.err.cant.apply.symbols" -> of("arguments.any");
      case "compiler.err.unclosed.str.lit" -> of("unclosed.string");
      case "compiler.err.unclosed.comment" -> of("unclosed.comment");
      case "compiler.err.illegal.char" -> {
        m = ILLEGAL_CHAR.matcher(english);
        if (!m.find()) yield null;
        String character = m.group(1);
        yield character.matches("\\\\u201[89cdef]") ? of("smart.quotes")
            : of("illegal.char", character);
      }
      case "compiler.err.break.outside.switch.loop" -> of("break");
      case "compiler.err.else.without.if" -> of("else");
      case "compiler.err.missing.ret.stmt" -> of("return.end");
      case "compiler.err.unreachable.stmt" -> of("unreachable");
      case "compiler.err.not.stmt" -> of("not.statement");
      case "compiler.err.illegal.start.of.expr" -> METHOD_DECLARATION.matcher(
          line.substring((int) Math.clamp(d.column() - 1, 0, line.length()))).lookingAt()
          ? of("method.in.method") : of("expression");
      case "compiler.err.illegal.start.of.type" -> of("statement.outside");
      case "compiler.err.class.public.should.be.in.file" -> {
        m = WRONG_FILE.matcher(english);
        yield m.find() ? of("file.name", m.group(1), m.group(2)) : null;
      }
      case "compiler.err.does.not.override.abstract" -> {
        m = NOT_ABSTRACT.matcher(english);
        yield m.find() ? of("abstract.missing", simple(m.group(1)), m.group(2),
            simple(m.group(3))) : null;
      }
      case "compiler.err.abstract.cant.be.instantiated" -> {
        m = ABSTRACT.matcher(english);
        yield m.find() ? of("abstract", simple(m.group(1))) : null;
      }
      case "compiler.err.unreported.exception.need.to.catch" -> {
        m = UNREPORTED.matcher(english);
        yield m.find() ? of("exception", simple(m.group(1))) : null;
      }
      default -> null;
    };
    if (friendly == null) {
      return new Friendly(d.message(),
          DiagnosticsExplanations.explain(code, languageOf()).orElse(null), List.of());
    }
    if (friendly.explanation() == null) {
      friendly = new Friendly(friendly.title(),
          DiagnosticsExplanations.explain(code, languageOf()).orElse(null),
          friendly.suggestions(), friendly.fix(), friendly.fixData());
    }
    return friendly;
  }

  private Language languageOf() {
    return bundle.getLocale().getLanguage().equals("de") ? Language.DE : Language.EN;
  }

  private Friendly of(String key, String... args) {
    String explanation = bundle.containsKey("friendly." + key)
        ? fill(bundle.getString("friendly." + key), args) : null;
    return new Friendly(fill(bundle.getString("friendly." + key + ".title"), args),
        explanation, List.of());
  }

  private String text(String key) {
    return bundle.getString("friendly." + key);
  }

  private static String fill(String text, String... args) {
    for (int i = 0; i < args.length; i++) {
      text = text.replace("{" + i + "}", args[i]);
    }
    return text;
  }

  private String arguments(String list) {
    return list.equals("no arguments") ? text("arguments.none") : simpleTypes(list);
  }

  /** {@code java.lang.String,int} -> {@code String, int}. */
  private static String simpleTypes(String list) {
    return String.join(", ", java.util.Arrays.stream(list.split(","))
        .map(String::strip).map(FriendlyErrors::simple).toList());
  }

  /** {@code java.lang.String} -> {@code String}; generics and arrays stay. */
  static String simple(String type) {
    return type.replaceAll("(?:[a-z_][\\w$]*\\.)+([A-Z])", "$1");
  }

  private static boolean isNumber(String type) {
    return Set.of("int", "double", "float", "long", "short", "byte").contains(type);
  }

  /** The identifier javac points at (1-based column). */
  private static String nameAt(String line, long column) {
    int start = (int) column - 1;
    if (start < 0 || start >= line.length()) return "";
    int end = start;
    while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
    return line.substring(start, end);
  }

  /** The method an {@code @Override} at this line belongs to: on it or the next lines. */
  private static String overridingMethod(Context context, long line) {
    for (long l = line; l < line + 3 && l <= context.lines().size(); l++) {
      String text = context.line(l).replaceAll("@\\w+", " ");
      Matcher m = METHOD_NAME.matcher(text);
      if (m.find()) return m.group(1);
    }
    return null;
  }
}
