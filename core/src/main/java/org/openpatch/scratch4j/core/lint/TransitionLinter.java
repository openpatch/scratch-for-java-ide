package org.openpatch.scratch4j.core.lint;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import javax.lang.model.element.Modifier;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lints for the pitfalls of moving from Scratch to Java
 * ({@code docs/book/differences-scratch.md} in the library):
 *
 * <ul>
 *   <li>a loop that never ends ({@code while (true)} without break/return) or
 *       counts very far, in {@code run()}, a constructor or an event method —
 *       it freezes the program; Scratch's "forever" is {@code run()};</li>
 *   <li>{@code Thread.sleep} / {@code wait} in a stage or sprite — there is no
 *       "wait" block; use {@code getTimer().everyMillis(ms)};</li>
 *   <li>{@code new Window(...)} inside a stage or sprite (outside {@code main}),
 *       or two windows in one method — the window is a singleton;</li>
 *   <li>a counter field that is not {@code static} although every instance of
 *       the sprite keeps its own copy — shared variables need {@code static};</li>
 *   <li>a method the library never calls although it looks like one of its
 *       callbacks: {@code Run()}, {@code whenKeyPressed(int)} in 5.x, or
 *       {@code whenClicked()} in a stage ({@link LibraryCallbacks}).</li>
 * </ul>
 *
 * <p>Parse-only like {@link AssetLinter}; class relationships come from a light
 * scan over all project sources ({@link #facts}).
 */
public final class TransitionLinter {

  /** One finding: a short message, a longer explanation, at a 1-based line. */
  public record Finding(Path file, long line, String kind, String message, String explanation) {}

  /** What the linter needs to know about the whole project. */
  public record Facts(Set<String> stageClasses, Set<String> spriteClasses,
      Set<String> windowClasses, Map<String, Integer> instantiations,
      Map<String, Integer> calls) {}

  /** Loop bounds from here on count as "very long" for a single frame. */
  static final long LONG_LOOP = 100_000;

  private static final Pattern EXTENDS =
      Pattern.compile("\\bclass\\s+(\\w+)(?:\\s*<[^>]*>)?\\s+extends\\s+(\\w+)");
  private static final Pattern NEW =
      Pattern.compile("\\bnew\\s+(\\w+)\\s*\\(");
  /** A name followed by "(": a call, or a declaration when a type stands before it. */
  private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*\\(");
  private static final Pattern WORD_BEFORE = Pattern.compile("(\\w+)[\\s>\\]]*$");
  private static final Set<String> SPRITE_BASES = Set.of("Sprite", "AnimatedSprite", "UISprite");
  /** Field names that sound like something every instance should share. */
  private static final Pattern SHARED_NAME = Pattern.compile(
      "(?i).*(score|points?|lives|count(er)?|total|punkte|leben|zaehler|zähler|anzahl|highscore).*");

  private final DiagnosticsExplanations.Language language;
  private LibraryCallbacks callbacks;

  public TransitionLinter(DiagnosticsExplanations.Language language) {
    this.language = language;
  }

  /** The callbacks of the project's library version; the bundled one by default. */
  public TransitionLinter withCallbacks(LibraryCallbacks callbacks) {
    this.callbacks = callbacks;
    return this;
  }

  private LibraryCallbacks callbacks() {
    if (callbacks == null) {
      callbacks = LibraryCallbacks.bundled();
    }
    return callbacks;
  }

  /** Scans all sources once for class relationships and instantiation counts. */
  public static Facts facts(Map<Path, String> sources) {
    Map<String, String> parents = new HashMap<>();
    Map<String, Integer> instantiations = new HashMap<>();
    for (String text : sources.values()) {
      Matcher m = EXTENDS.matcher(stripComments(text));
      while (m.find()) {
        parents.put(m.group(1), m.group(2));
      }
    }
    Set<String> stages = new HashSet<>();
    Set<String> sprites = new HashSet<>();
    Set<String> windows = new HashSet<>();
    for (String type : parents.keySet()) {
      String base = root(type, parents);
      if ("Stage".equals(base)) {
        stages.add(type);
      } else if (SPRITE_BASES.contains(base)) {
        sprites.add(type);
      } else if ("Window".equals(base)) {
        windows.add(type);
      }
    }
    Map<String, Integer> calls = new HashMap<>();
    for (String source : sources.values()) {
      String code = stripComments(source);
      Matcher m = NEW.matcher(code);
      while (m.find()) {
        instantiations.merge(m.group(1), 1, Integer::sum);
      }
      Matcher call = CALL.matcher(code);
      while (call.find()) {
        // "void run(" declares, "new Cat(" creates; "return run(" and "this.run(" call
        Matcher before = WORD_BEFORE.matcher(
            code.substring(Math.max(0, call.start() - 40), call.start()));
        if (!before.find() || before.group(1).equals("return")) {
          calls.merge(call.group(1), 1, Integer::sum);
        }
      }
    }
    return new Facts(stages, sprites, windows, instantiations, calls);
  }

  /** Follows {@code extends} up to the first type that is not a project class. */
  private static String root(String type, Map<String, String> parents) {
    String current = type;
    Set<String> seen = new HashSet<>();
    while (parents.containsKey(current) && seen.add(current)) {
      String parent = parents.get(current);
      if (SPRITE_BASES.contains(parent) || "Stage".equals(parent) || "Window".equals(parent)) {
        return parent;
      }
      current = parent;
    }
    return current;
  }

  /** Lints one file. */
  public List<Finding> lint(Path file, String source, Facts facts) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { },
        List.of("-proc:none"), null, List.of(new SourceObject(file, source)));
    Iterable<? extends CompilationUnitTree> units;
    try {
      units = task.parse();
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
    SourcePositions positions = Trees.instance(task).getSourcePositions();
    List<Finding> findings = new ArrayList<>();
    for (CompilationUnitTree unit : units) {
      new Scanner(file, unit, positions, facts, findings).scan(unit, null);
    }
    findings.sort(Comparator.comparingLong(Finding::line));
    return dedupe(findings);
  }

  private static List<Finding> dedupe(List<Finding> findings) {
    Map<String, Finding> unique = new LinkedHashMap<>();
    for (Finding f : findings) {
      unique.putIfAbsent(f.line() + ":" + f.kind(), f);
    }
    return new ArrayList<>(unique.values());
  }

  private Finding finding(Path file, long line, String kind, Object... args) {
    Locale locale = language == DiagnosticsExplanations.Language.DE
        ? Locale.GERMAN : Locale.ENGLISH;
    ResourceBundle bundle = ResourceBundle.getBundle(
        "org.openpatch.scratch4j.core.lint.explanations", locale,
        TransitionLinter.class.getClassLoader());
    String message = MessageFormat.format(bundle.getString("lint." + kind), args);
    String explanation = MessageFormat.format(bundle.getString("lint." + kind + ".explain"), args);
    return new Finding(file, line, kind, message, explanation);
  }

  private final class Scanner extends TreePathScanner<Void, Void> {
    private final Path file;
    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final Facts facts;
    private final List<Finding> findings;
    private String currentClass;
    private String currentMethod;
    /** Windows created so far in the current method (separate mains are separate programs). */
    private int windowsInMethod;

    Scanner(Path file, CompilationUnitTree unit, SourcePositions positions, Facts facts,
        List<Finding> findings) {
      this.file = file;
      this.unit = unit;
      this.positions = positions;
      this.facts = facts;
      this.findings = findings;
    }

    private long line(Tree tree) {
      return unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
    }

    private boolean inScratchClass() {
      return currentClass != null && (facts.stageClasses().contains(currentClass)
          || facts.spriteClasses().contains(currentClass));
    }

    private boolean inFrameMethod() {
      // run() is called every frame; constructors and event methods must return quickly too
      return currentMethod != null && (currentMethod.equals("run")
          || currentMethod.equals("<init>") || currentMethod.startsWith("when"));
    }

    @Override
    public Void visitClass(ClassTree tree, Void unused) {
      String outer = currentClass;
      String outerMethod = currentMethod;
      currentClass = tree.getSimpleName().toString();
      currentMethod = null;
      if (facts.spriteClasses().contains(currentClass)) {
        checkCounters(tree);
      }
      super.visitClass(tree, unused);
      currentClass = outer;
      currentMethod = outerMethod;
      return null;
    }

    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
      String outer = currentMethod;
      int outerWindows = windowsInMethod;
      currentMethod = tree.getName().toString();
      windowsInMethod = 0;
      checkCallback(tree);
      super.visitMethod(tree, unused);
      currentMethod = outer;
      windowsInMethod = outerWindows;
      return null;
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
      // a timer callback or a thread body is not the frame method itself
      String outer = currentMethod;
      currentMethod = null;
      super.visitLambdaExpression(tree, unused);
      currentMethod = outer;
      return null;
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
      if (inScratchClass() && inFrameMethod() && isTrue(tree.getCondition())
          && !leaves(tree.getStatement())) {
        findings.add(finding(file, line(tree), "forever", currentMethodLabel()));
      }
      return super.visitWhileLoop(tree, unused);
    }

    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
      if (inScratchClass() && inFrameMethod() && isTrue(tree.getCondition())
          && !leaves(tree.getStatement())) {
        findings.add(finding(file, line(tree), "forever", currentMethodLabel()));
      }
      return super.visitDoWhileLoop(tree, unused);
    }

    @Override
    public Void visitForLoop(ForLoopTree tree, Void unused) {
      if (inScratchClass() && inFrameMethod()) {
        if ((tree.getCondition() == null || isTrue(tree.getCondition()))
            && !leaves(tree.getStatement())) {
          findings.add(finding(file, line(tree), "forever", currentMethodLabel()));
        } else if (tree.getCondition() instanceof BinaryTree bound
            && "run".equals(currentMethod)) {
          long limit = Math.max(literal(bound.getRightOperand()),
              literal(bound.getLeftOperand()));
          if (limit >= LONG_LOOP) {
            findings.add(finding(file, line(tree), "longloop", limit));
          }
        }
      }
      return super.visitForLoop(tree, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
      if (inScratchClass() || facts.windowClasses().contains(currentClass)) {
        ExpressionTree select = tree.getMethodSelect();
        boolean sleep = select instanceof MemberSelectTree member
            && member.getIdentifier().contentEquals("sleep")
            && member.getExpression().toString().endsWith("Thread");
        boolean wait = (select instanceof IdentifierTree id && id.getName().contentEquals("wait"))
            || (select instanceof MemberSelectTree member
                && member.getIdentifier().contentEquals("wait")
                && member.getExpression().toString().equals("this"));
        if (sleep || wait) {
          findings.add(finding(file, line(tree), "sleep", sleep ? "Thread.sleep" : "wait"));
        }
      }
      return super.visitMethodInvocation(tree, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, Void unused) {
      String type = tree.getIdentifier().toString();
      if (("Window".equals(type) || facts.windowClasses().contains(type))
          && currentMethod != null && ++windowsInMethod > 1) {
        findings.add(finding(file, line(tree), "window.second"));
      }
      // main may create the window before the first stage (the stress-test demo does)
      if (inScratchClass() && !"main".equals(currentMethod)
          && ("Window".equals(type) || facts.windowClasses().contains(type))) {
        findings.add(finding(file, line(tree), "window.inside", currentClass));
      }
      return super.visitNewClass(tree, unused);
    }

    /**
     * A method that looks like a library callback but is never called: a
     * misspelled name, other parameter types, or a sprite event in a stage.
     * Methods the student calls somewhere are theirs, and @Override already
     * makes javac check the rest.
     */
    private void checkCallback(MethodTree method) {
      boolean sprite = facts.spriteClasses().contains(currentClass);
      if (!sprite && !facts.stageClasses().contains(currentClass)) return;
      var flags = method.getModifiers().getFlags();
      if (method.getReturnType() == null || flags.contains(Modifier.STATIC)
          || flags.contains(Modifier.PRIVATE)) return;
      boolean override = method.getModifiers().getAnnotations().stream()
          .anyMatch(a -> a.getAnnotationType().toString().endsWith("Override"));
      String name = method.getName().toString();
      if (override || facts.calls().containsKey(name)) return;
      List<String> params = method.getParameters().stream()
          .map(p -> FriendlyErrors.simple(p.getType().toString())).toList();
      Map<String, List<List<String>>> own = callbacks().of(sprite);
      Map<String, List<List<String>>> other = callbacks().of(!sprite);
      if (own.containsKey(name)) {
        if (!own.get(name).contains(params)) {
          findings.add(finding(file, line(method), "callback.params", name,
              String.join(", ", params), String.join(", ", own.get(name).get(0))));
        }
      } else if (other.containsKey(name)) {
        findings.add(finding(file, line(method), "callback.other", name,
            sprite ? "Sprite" : "Stage", sprite ? "Stage" : "Sprite"));
      } else {
        List<String> close = DidYouMean.suggest(name, own.keySet(), 1);
        if (!close.isEmpty()) {
          findings.add(finding(file, line(method), "callback.name", name, close.get(0)));
        }
      }
    }

    private String currentMethodLabel() {
      return "<init>".equals(currentMethod) ? currentClass + "()" : currentMethod + "()";
    }

    /**
     * A number field changed with ++/--/+=/-= in a sprite class that exists
     * more than once (or whose name sounds shared) is a per-instance copy.
     */
    private void checkCounters(ClassTree type) {
      Map<String, VariableTree> counters = new LinkedHashMap<>();
      for (Tree member : type.getMembers()) {
        if (member instanceof VariableTree field
            && !field.getModifiers().getFlags().contains(Modifier.STATIC)
            && !field.getModifiers().getFlags().contains(Modifier.FINAL)
            && isNumber(field.getType())) {
          counters.put(field.getName().toString(), field);
        }
      }
      if (counters.isEmpty()) {
        return;
      }
      Set<String> changed = new HashSet<>();
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitUnary(UnaryTree tree, Void unused) {
          if (tree.getKind() == Tree.Kind.POSTFIX_INCREMENT
              || tree.getKind() == Tree.Kind.PREFIX_INCREMENT
              || tree.getKind() == Tree.Kind.POSTFIX_DECREMENT
              || tree.getKind() == Tree.Kind.PREFIX_DECREMENT) {
            changed.add(fieldName(tree.getExpression()));
          }
          return super.visitUnary(tree, unused);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
          changed.add(fieldName(tree.getVariable()));
          return super.visitCompoundAssignment(tree, unused);
        }
      }.scan(type, null);
      String className = type.getSimpleName().toString();
      boolean many = facts.instantiations().getOrDefault(className, 0) > 1;
      for (Map.Entry<String, VariableTree> counter : counters.entrySet()) {
        String name = counter.getKey();
        if (changed.contains(name) && (many || SHARED_NAME.matcher(name).matches())) {
          findings.add(finding(file, line(counter.getValue()), "static", name, className));
        }
      }
    }
  }

  private static String fieldName(ExpressionTree tree) {
    if (tree instanceof IdentifierTree id) {
      return id.getName().toString();
    }
    if (tree instanceof MemberSelectTree member && member.getExpression().toString().equals("this")) {
      return member.getIdentifier().toString();
    }
    return "";
  }

  private static boolean isNumber(Tree type) {
    if (type instanceof PrimitiveTypeTree primitive) {
      return switch (primitive.getPrimitiveTypeKind()) {
        case INT, LONG, DOUBLE, FLOAT, SHORT -> true;
        default -> false;
      };
    }
    String name = type.toString();
    return name.equals("Integer") || name.equals("Double") || name.equals("Long");
  }

  private static boolean isTrue(ExpressionTree condition) {
    ExpressionTree c = condition;
    while (c instanceof ParenthesizedTree p) {
      c = p.getExpression();
    }
    return c instanceof LiteralTree literal && Boolean.TRUE.equals(literal.getValue());
  }

  private static long literal(ExpressionTree tree) {
    return tree instanceof LiteralTree literal && literal.getValue() instanceof Number n
        ? n.longValue() : -1;
  }

  /** Whether a loop body can leave the loop (break, return, or throw). */
  private static boolean leaves(Tree body) {
    boolean[] found = {false};
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitBreak(BreakTree tree, Void unused) {
        found[0] = true;
        return null;
      }

      @Override
      public Void visitReturn(ReturnTree tree, Void unused) {
        found[0] = true;
        return null;
      }

      @Override
      public Void visitThrow(com.sun.source.tree.ThrowTree tree, Void unused) {
        found[0] = true;
        return null;
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
        if (tree.getMethodSelect().toString().equals("System.exit")) {
          found[0] = true;
        }
        return super.visitMethodInvocation(tree, unused);
      }

      @Override
      public Void visitClass(ClassTree tree, Void unused) {
        return null; // a break in a nested class does not leave this loop
      }

      @Override
      public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
        return null;
      }
    }.scan(body, null);
    return found[0];
  }

  /** Blanks out comments (keeping line breaks, so offsets map to the same lines). */
  private static String stripComments(String text) {
    StringBuilder sb = new StringBuilder(text);
    Matcher m = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*").matcher(text);
    while (m.find()) {
      for (int i = m.start(); i < m.end(); i++) {
        if (sb.charAt(i) != '\n') {
          sb.setCharAt(i, ' ');
        }
      }
    }
    return sb.toString();
  }

  private static final class SourceObject extends SimpleJavaFileObject {
    private final String source;

    SourceObject(Path file, String source) {
      super(URI.create("string:///" + file.getFileName()), JavaFileObject.Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
