package org.openpatch.scratch4j.core.project;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.JavaFileObject;
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
import java.util.regex.Pattern;

/**
 * The docs' and the Online IDE's programs are compact source files: a
 * {@code void main()} beside the classes, no imports. This turns one into
 * normal project classes (one file per class, the imports the Online IDE
 * implies, and a {@code Main} class for the top-level code) and turns a
 * project back into one compact file.
 */
public final class CompactSource {

  /** What the Online IDE has in scope without imports (as the library's docs test). */
  public static final String IMPORTS = """
      import org.openpatch.scratch.*;
      import org.openpatch.scratch.extensions.camera.*;
      import org.openpatch.scratch.extensions.fs.*;
      import org.openpatch.scratch.extensions.pixels.*;
      import org.openpatch.scratch.extensions.shader.*;
      import org.openpatch.scratch.extensions.sorting.*;
      import org.openpatch.scratch.extensions.tiled.*;
      """;

  /** The files written and the class Run should start. */
  public record Imported(List<Path> files, String startClass) {}

  private static final Pattern IMPORT_LINE =
      Pattern.compile("(?m)^\\s*import\\s+[\\w.*]+\\s*;[ \\t]*\\R?");
  private static final Pattern PACKAGE_LINE =
      Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;[ \\t]*\\R?");

  private CompactSource() {}

  /**
   * Writes the snippet's classes into {@code root} (one file each) plus
   * {@code Main.java} for its top-level fields and methods. Refuses to
   * overwrite existing files.
   */
  public static Imported importSnippet(String snippet, Path root) throws IOException {
    String text = PACKAGE_LINE.matcher(IMPORT_LINE.matcher(snippet).replaceAll(""))
        .replaceAll("");
    var object = new SimpleJavaFileObject(URI.create("string:///Main.java"),
        JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
      }
    };
    JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null,
        d -> { }, List.of("-proc:none"), null, List.of(object));
    CompilationUnitTree unit = task.parse().iterator().next();
    SourcePositions positions = Trees.instance(task).getSourcePositions();

    Map<String, String> classes = new LinkedHashMap<>();
    List<String> topLevel = new ArrayList<>();
    boolean instanceMain = false;
    boolean mainWithArgs = false;
    for (Tree decl : unit.getTypeDecls()) {
      if (!(decl instanceof ClassTree type)) {
        continue;
      }
      boolean implicit = !text.substring((int) positions.getStartPosition(unit, type))
          .matches("(?s)^(?:@\\w+\\s*|public\\s+|final\\s+|abstract\\s+|sealed\\s+)*"
              + "(?:class|interface|enum|record)\\b.*");
      if (!implicit) {
        classes.put(type.getSimpleName().toString(), slice(text, positions, unit, type));
        continue;
      }
      for (Tree member : type.getMembers()) {
        if (member instanceof ClassTree nested) {
          classes.put(nested.getSimpleName().toString(), slice(text, positions, unit, nested));
        } else if (positions.getStartPosition(unit, member) >= 0) {
          topLevel.add(slice(text, positions, unit, member));
          if (member instanceof MethodTree method && method.getName().contentEquals("main")) {
            instanceMain = !method.getModifiers().getFlags()
                .contains(javax.lang.model.element.Modifier.STATIC);
            mainWithArgs = !method.getParameters().isEmpty();
          }
        }
      }
    }
    String startClass = topLevel.isEmpty() ? null : uniqueMain(classes);
    List<Path> files = new ArrayList<>();
    for (String name : classes.keySet()) {
      files.add(root.resolve(name + ".java"));
    }
    if (startClass != null) {
      files.add(root.resolve(startClass + ".java"));
    }
    List<String> clashes = files.stream().filter(Files::exists)
        .map(f -> f.getFileName().toString()).toList();
    if (!clashes.isEmpty()) {
      throw new IOException("These files already exist: " + String.join(", ", clashes));
    }
    for (Map.Entry<String, String> entry : classes.entrySet()) {
      String body = entry.getValue().replaceFirst("^(?:static\\s+)", "");
      Files.writeString(root.resolve(entry.getKey() + ".java"),
          IMPORTS + "\n" + body.strip() + "\n", StandardCharsets.UTF_8);
    }
    if (startClass != null) {
      StringBuilder main = new StringBuilder(IMPORTS).append("\n// The snippet's top-level code.\n")
          .append("public class ").append(startClass).append(" {\n");
      for (String member : topLevel) {
        main.append('\n').append(indent(member.strip())).append('\n');
      }
      main.append("\n  public static void main(String[] args) {\n    ");
      if (instanceMain) {
        main.append("new ").append(startClass).append("().main(")
            .append(mainWithArgs ? "args" : "").append(");\n");
      } else {
        main.append("// the snippet's own static main runs\n");
      }
      main.append("  }\n}\n");
      String code = main.toString();
      if (!instanceMain) {
        // a static main(String[]) of the snippet is the program's main already
        code = code.replace("\n  public static void main(String[] args) {\n    "
            + "// the snippet's own static main runs\n  }\n", "");
      }
      Files.writeString(root.resolve(startClass + ".java"), code, StandardCharsets.UTF_8);
    }
    String start = startClass != null ? startClass
        : classes.keySet().stream().findFirst().orElse(null);
    return new Imported(files, start);
  }

  /**
   * One compact source file for the Online IDE: {@code void main()} starts
   * {@code startClass}; every class follows without imports or {@code public}.
   */
  public static String export(ScratchProject project, String startClass) throws IOException {
    StringBuilder classes = new StringBuilder();
    String startBody = null;
    for (Path source : project.javaSources()) {
      String text = Files.readString(source, StandardCharsets.UTF_8);
      // classes become inner classes of the compact file: a static main there
      // could not create them, so it goes; the start class's main becomes main()
      String[] main = removeStaticMain(text);
      if (source.getFileName().toString().equals(startClass + ".java") && main[1] != null) {
        startBody = main[1];
      }
      String body = PACKAGE_LINE.matcher(IMPORT_LINE.matcher(main[0]).replaceAll(""))
          .replaceAll("");
      body = body.replaceAll(
          "(?m)^public\\s+((?:abstract\\s+|final\\s+)*(?:class|interface|enum|record)\\b)", "$1");
      classes.append('\n').append(body.strip()).append('\n');
    }
    String mainBody = startBody != null && !startBody.isBlank() ? startBody.strip()
        : "new " + startClass + "();";
    return "void main() {\n  " + mainBody.replace("\n", "\n  ").replaceAll("\n\\s+\n", "\n\n")
        + "\n}\n" + classes;
  }

  /** [text without static main(String[]) methods, the start body or null]. */
  private static String[] removeStaticMain(String text) {
    var object = new SimpleJavaFileObject(URI.create("string:///X.java"),
        JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
      }
    };
    JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null,
        d -> { }, List.of("-proc:none"), null, List.of(object));
    try {
      CompilationUnitTree unit = task.parse().iterator().next();
      SourcePositions positions = Trees.instance(task).getSourcePositions();
      StringBuilder out = new StringBuilder(text);
      String body = null;
      List<long[]> cuts = new ArrayList<>();
      for (Tree decl : unit.getTypeDecls()) {
        if (!(decl instanceof ClassTree type)) {
          continue;
        }
        for (Tree member : type.getMembers()) {
          if (member instanceof MethodTree method && method.getName().contentEquals("main")
              && method.getModifiers().getFlags()
                  .contains(javax.lang.model.element.Modifier.STATIC)
              && method.getBody() != null) {
            long start = positions.getStartPosition(unit, method);
            long end = positions.getEndPosition(unit, method);
            long bodyStart = positions.getStartPosition(unit, method.getBody());
            String block = text.substring((int) bodyStart, (int) end);
            body = block.substring(1, block.length() - 1).strip().replaceAll("(?m)^\\s+", "");
            int lineStart = text.lastIndexOf('\n', (int) start - 1) + 1;
            cuts.add(new long[] {text.substring(lineStart, (int) start).isBlank() ? lineStart
                : start, end});
          }
        }
      }
      cuts.sort((x, y) -> Long.compare(y[0], x[0]));
      for (long[] cut : cuts) {
        int end = (int) cut[1];
        while (end < out.length() && out.charAt(end) == '\n' && end < (int) cut[1] + 1) {
          end++;
        }
        out.delete((int) cut[0], end);
      }
      return new String[] {out.toString(), body};
    } catch (IOException | RuntimeException e) {
      return new String[] {text, null};
    }
  }

  private static String uniqueMain(Map<String, String> classes) {
    String name = "Main";
    for (int i = 2; classes.containsKey(name); i++) {
      name = "Main" + i;
    }
    return name;
  }

  private static String slice(String text, SourcePositions positions, CompilationUnitTree unit,
      Tree tree) {
    int start = (int) positions.getStartPosition(unit, tree);
    int end = (int) positions.getEndPosition(unit, tree);
    // keep a comment directly above the declaration
    int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
    String before = text.substring(0, lineStart);
    java.util.regex.Matcher comment = Pattern.compile("(?s)(/\\*\\*?.*?\\*/|(?:[ \\t]*//[^\\n]*\\n)+)\\s*$")
        .matcher(before);
    if (comment.find() && before.substring(comment.start()).strip().length() > 0) {
      lineStart = comment.start();
    }
    return text.substring(lineStart, Math.max(end, start));
  }

  /** Two more spaces in front of every line (members inside the Main class). */
  private static String indent(String code) {
    String common = code.lines().skip(1).filter(l -> !l.isBlank())
        .map(l -> l.substring(0, l.length() - l.stripLeading().length()))
        .reduce((a, b) -> a.length() <= b.length() ? a : b).orElse("");
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (String line : code.lines().toList()) {
      String stripped = first ? line : line.startsWith(common) ? line.substring(common.length())
          : line.stripLeading();
      sb.append(line.isBlank() ? "" : "  " + (first ? stripped : "  ".repeat(0) + stripped))
          .append('\n');
      first = false;
    }
    return sb.toString().stripTrailing();
  }
}
