package org.openpatch.scratch4j.core.lint;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.openpatch.scratch4j.core.assets.BuiltinAssetIndex;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Flags asset names in the library's asset-taking calls:
 *
 * <pre>
 * addCostume / addBackdrop / addSound / addAnimation
 * </pre>
 *
 * <p>Uses the bundled {@code jdk.compiler} in parse-only mode (no classpath, no
 * symbol resolution — parsing alone is enough for string-literal arguments).
 * A string with a file ending is a path (checked against the project folder),
 * without one a built-in name (checked against the registry, with "did you
 * mean" suggestions via the library's {@code suggest}). MP3 and other
 * unsupported formats are flagged before the program even runs.
 */
public final class AssetLinter {

  /** One problem found in one file at a line (1-based). */
  public record Finding(Path file, long line, String message, List<String> suggestions) {}

  private static final Pattern FILE_ENDING =
      Pattern.compile(".*\\.(png|jpg|jpeg|gif|bmp|wav|aiff|au|ogg|mp3)$",
          Pattern.CASE_INSENSITIVE);

  private static final Set<String> SUPPORTED_IMAGES = Set.of("png", "jpg", "jpeg", "gif");
  private static final Set<String> SUPPORTED_SOUNDS = Set.of("wav", "aiff", "au", "ogg");

  private enum Kind { IMAGE, SOUND }

  private final BuiltinAssetIndex assets = BuiltinAssetIndex.get();

  /** Lints one source file of the project rooted at {@code projectRoot}. */
  public List<Finding> lint(Path projectRoot, Path file, String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    JavacTask task = (JavacTask) compiler.getTask(null, null, null,
        List.of(), null, List.of(new SourceObject(file, source)));
    Iterable<? extends CompilationUnitTree> units;
    try {
      units = task.parse();
    } catch (IOException e) {
      return List.of();
    }
    Scanner scanner = new Scanner(projectRoot, file, source,
        Trees.instance(task).getSourcePositions());
    for (CompilationUnitTree unit : units) {
      scanner.scan(unit, null);
    }
    List<Finding> sorted = new ArrayList<>(scanner.findings);
    sorted.sort(Comparator.comparing(Finding::line));
    return sorted;
  }

  private final class Scanner extends TreeScanner<Void, Void> {

    private final Path projectRoot;
    private final Path file;
    private final LineCounter lines;
    private final SourcePositions positions;
    private final List<Finding> findings = new ArrayList<>();

    Scanner(Path projectRoot, Path file, String source, SourcePositions positions) {
      this.projectRoot = projectRoot;
      this.file = file;
      this.lines = new LineCounter(source);
      this.positions = positions;
    }

    private CompilationUnitTree unit;

    @Override
    public Void visitCompilationUnit(CompilationUnitTree node, Void p) {
      this.unit = node;
      return super.visitCompilationUnit(node, p);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree call, Void p) {
      String name = methodName(call);
      if (name != null) {
        switch (name) {
          case "addCostume", "addBackdrop" -> lintLastString(call, Kind.IMAGE);
          case "addSound" -> lintLastString(call, Kind.SOUND);
          case "addAnimation" -> lintAnimationPattern(call);
          default -> { }
        }
      }
      return super.visitMethodInvocation(call, p);
    }

    /** addCostume("sit"), addCostume("sit", "assets/cat.png"), addBackdrop(name[, path[, stretch]]), addSound(name[, path]). */
    private void lintLastString(MethodInvocationTree call, Kind kind) {
      List<LiteralTree> literals = stringLiterals(call);
      if (literals.isEmpty()) {
        return;
      }
      LiteralTree ref = literals.get(literals.size() - 1);
      checkAsset(kind, (String) ref.getValue(), lineOf(ref));
    }

    /** addAnimation(name, pattern, frames[, width, height[, row]]): a {@code %d} pattern over built-ins, or a file sheet. */
    private void lintAnimationPattern(MethodInvocationTree call) {
      List<LiteralTree> literals = stringLiterals(call);
      if (literals.size() < 2) {
        return;
      }
      LiteralTree patternTree = literals.get(1);
      String pattern = (String) patternTree.getValue();
      Integer frames = intLiteral(call, 2);
      if (pattern.contains("%d")) {
        if (frames == null || frames <= 0) {
          return; // frame count unknown - nothing to check
        }
        List<String> missing = new ArrayList<>();
        for (int i = 1; i <= frames; i++) {
          String frame = pattern.replace("%d", Integer.toString(i));
          if (assets.image(frame).isEmpty()) {
            missing.add(frame);
          }
        }
        if (!missing.isEmpty()) {
          List<String> suggestions = assets.suggestImages(stripPattern(pattern), 3)
              .stream().map(s -> s + "%d").limit(3).toList();
          findings.add(new Finding(file, lineOf(patternTree),
              "The animation pattern \"" + pattern + "\" does not name built-in costumes: "
                  + String.join(", ", missing) + ".",
              suggestions));
        }
      } else if (FILE_ENDING.matcher(pattern).matches()) {
        checkAsset(Kind.IMAGE, pattern, lineOf(patternTree));
      }
    }

    private void checkAsset(Kind kind, String ref, long line) {
      if (FILE_ENDING.matcher(ref).matches()) {
        String ext = ref.substring(ref.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Set<String> supported = kind == Kind.IMAGE ? SUPPORTED_IMAGES : SUPPORTED_SOUNDS;
        if ("mp3".equals(ext)) {
          findings.add(new Finding(file, line,
              "Scratch for Java cannot play MP3 - convert the sound to WAV or OGG and use that.",
              List.of()));
          return;
        }
        if (!supported.contains(ext)) {
          findings.add(new Finding(file, line,
              "Unsupported format ." + ext + ". Images: PNG, JPG, GIF. Sounds: WAV, AIFF, AU, OGG.",
              List.of()));
          return;
        }
        Path resolved = Path.of(ref);
        if (!resolved.isAbsolute()) {
          resolved = projectRoot.resolve(resolved);
        }
        if (!Files.exists(resolved)) {
          findings.add(new Finding(file, line,
              "The file \"" + ref + "\" was not found in the project folder.", List.of()));
        }
      } else if (kind == Kind.IMAGE) {
        if (assets.image(ref).isEmpty()) {
          findings.add(new Finding(file, line,
              "\"" + ref + "\" is not a built-in image.", assets.suggestImages(ref, 3)));
        }
      } else {
        if (!assets.hasSound(ref)) {
          findings.add(new Finding(file, line,
              "\"" + ref + "\" is not a built-in sound.", assets.suggestSounds(ref, 3)));
        }
      }
    }

    private long lineOf(LiteralTree literal) {
      long pos = positions.getStartPosition(unit, literal);
      if (pos < 0) {
        return 0;
      }
      return lines.lineOfOffset(pos);
    }
  }

  private static String methodName(MethodInvocationTree call) {
    ExpressionTree select = call.getMethodSelect();
    if (select instanceof IdentifierTree id) {
      return id.getName().toString();
    }
    if (select instanceof MemberSelectTree member) {
      return member.getIdentifier().toString();
    }
    return null;
  }

  private static List<LiteralTree> stringLiterals(MethodInvocationTree call) {
    List<LiteralTree> literals = new ArrayList<>();
    for (ExpressionTree arg : call.getArguments()) {
      if (arg instanceof LiteralTree lit && lit.getValue() instanceof String) {
        literals.add(lit);
      }
    }
    return literals;
  }

  private static Integer intLiteral(MethodInvocationTree call, int index) {
    List<? extends ExpressionTree> args = call.getArguments();
    if (args.size() > index && args.get(index) instanceof LiteralTree lit
        && lit.getValue() instanceof Integer value) {
      return value;
    }
    return null;
  }

  private static String stripPattern(String pattern) {
    int idx = pattern.indexOf("%d");
    return idx < 0 ? pattern : pattern.substring(0, idx);
  }

  /** 1-based line numbers from character offsets. */
  private static final class LineCounter {
    private final long[] lineStarts;

    LineCounter(String source) {
      List<Long> starts = new ArrayList<>();
      starts.add(0L);
      for (int i = 0; i < source.length(); i++) {
        if (source.charAt(i) == '\n') {
          starts.add((long) i + 1);
        }
      }
      lineStarts = starts.stream().mapToLong(Long::longValue).toArray();
    }

    long lineOfOffset(long offset) {
      int lo = 0;
      int hi = lineStarts.length - 1;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (lineStarts[mid] <= offset) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      return lo + 1;
    }
  }

  private static final class SourceObject extends SimpleJavaFileObject {
    private final String content;

    SourceObject(Path file, String content) {
      super(URI.create("mem://lint/" + file.getFileName()), Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return content;
    }

    @Override
    public boolean isNameCompatible(String simpleName, JavaFileObject.Kind kind) {
      return true;
    }
  }
}
