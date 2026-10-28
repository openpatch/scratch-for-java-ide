package org.openpatch.scratch4j.export;

import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerDiagnostic;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.PortableProject;
import org.openpatch.scratch4j.core.project.ProjectSettings;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project as a web page: compiled against the library's web jar
 * ({@code scratch-<v>-web.jar}), which then runs as its own process and
 * compiles the classes to WebAssembly with TeaVM. The page needs a web
 * server (any static host); opening index.html as a file does not work.
 */
public final class WebPage {

  /** Extensions that are not in the web jar (it brings the core and every other one). */
  private static final Pattern DESKTOP_EXTENSION =
      Pattern.compile("org\\.openpatch\\.scratch\\.(gamepad)\\b");

  /** Something in the project does not work in a browser, said for a learner. */
  public static final class NotForTheWebException extends IOException {
    private final List<String> details;

    NotForTheWebException(String message, List<String> details) {
      super(message);
      this.details = List.copyOf(details);
    }

    /** The compiler's own lines, for the log. */
    public List<String> details() {
      return this.details;
    }
  }

  private WebPage() {}

  /**
   * Writes the page into {@code out} (made empty first) and returns its index.html.
   *
   * @param webJar the library's web export jar for the project's flavour (the NRW one for NRW
   *     projects)
   * @param java the java executable that runs it
   */
  public static Path export(ScratchProject project, Path webJar, Path java, Path out)
      throws IOException {
    String start = project.startStage();
    if (start.isEmpty()) {
      throw new IOException("The project has no Stage class to start");
    }
    Path build = project.root().resolve(".scratch4j/build/web");
    if (Files.exists(build)) {
      PortableProject.deleteTree(build);
    }
    Path classes = build.resolve("classes");
    Files.createDirectories(classes);

    List<Path> classPath = new ArrayList<>(List.of(webJar));
    for (Path lib : project.libs()) {
      if (!lib.getFileName().toString().startsWith("scratch-")) {
        classPath.add(lib); // the project's own libraries; the library comes from the web jar
      }
    }
    compile(project.javaSources(), classPath, classes);

    Path launcher = build.resolve("launcher/Scratch4JWebMain.java");
    Files.createDirectories(launcher.getParent());
    boolean nrwList = "nrw".equals(project.settings().flavour)
        && org.openpatch.scratch4j.core.project.Abiturklassen.present(project)
            .contains(org.openpatch.scratch4j.core.project.Abiturklassen.LIST);
    Files.writeString(launcher, launcherSource(project.settings(), start, mainKind(classes, webJar, start),
        nrwList), StandardCharsets.UTF_8);
    List<Path> withClasses = new ArrayList<>(classPath);
    withClasses.add(classes);
    compile(List.of(launcher), withClasses, classes);

    if (Files.exists(out)) {
      PortableProject.deleteTree(out);
    }
    List<String> command = new ArrayList<>(List.of(java.toString(), "-jar", webJar.toString(),
        "--main", "Scratch4JWebMain", "--title", project.name(), "--game", project.root().toString(),
        "--out", out.toString(), classes.toString()));
    for (Path lib : classPath.subList(1, classPath.size())) {
      command.add(lib.toString());
    }
    Path errors = build.resolve("teavm-errors.txt");
    Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
    String stdout;
    try {
      stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int status = process.waitFor();
      String stderr = Files.readString(errors, StandardCharsets.UTF_8);
      if (status == 2) {
        List<String> problems = stderr.lines().filter(line -> !line.isBlank()).toList();
        throw new NotForTheWebException(explain(problems), problems);
      }
      if (status != 0) {
        throw new IOException("The web export failed: " + stderr.strip());
      }
    } catch (InterruptedException e) {
      process.destroy();
      Thread.currentThread().interrupt();
      throw new IOException("The web export was interrupted", e);
    }
    Path index = out.resolve("index.html");
    if (!Files.isRegularFile(index)) {
      throw new IOException("The web export wrote no page: " + stdout.strip());
    }
    return index;
  }

  private static void compile(List<Path> sources, List<Path> classPath, Path classes) throws IOException {
    CompileResult result = new CompilerService().compile(sources, classPath, classes);
    if (result.success()) {
      return;
    }
    List<String> details = result.errors().stream()
        .map(d -> Path.of(d.path()).getFileName() + ":" + d.line() + ": " + d.englishMessage()).toList();
    for (CompilerDiagnostic d : result.errors()) {
      Matcher m = DESKTOP_EXTENSION.matcher(d.englishMessage());
      if (m.find()) {
        throw new NotForTheWebException("The project uses the " + m.group(1)
            + " extension, which does not work in a web page yet.", details);
      }
    }
    throw new IOException("The project does not compile: " + details.get(0));
  }

  /** TeaVM's words for what the browser cannot run, with the class at fault named first. */
  static String explain(List<String> problems) {
    for (String line : problems) {
      Matcher m = Pattern.compile("(?:Method|Class|Field) ([\\w.$]+)").matcher(line);
      if (m.find()) {
        String what = m.group(1);
        int paren = what.indexOf('(');
        return "The game uses " + (paren > 0 ? what.substring(0, paren) : what)
            + ", which a web page does not have.";
      }
    }
    return "The game uses something a web page does not have.";
  }

  enum MainKind { STATIC_ARGS, STATIC, INSTANCE_ARGS, INSTANCE, STAGE, OTHER }

  /** How the start class starts: as the desktop launcher decides it, but at export time. */
  static MainKind mainKind(Path classes, Path webJar, String start) throws IOException {
    URL[] urls = {classes.toUri().toURL(), webJar.toUri().toURL()};
    try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
      Class<?> type = Class.forName(start, false, loader);
      for (boolean args : new boolean[] {true, false}) {
        try {
          Method main = args ? type.getDeclaredMethod("main", String[].class) : type.getDeclaredMethod("main");
          boolean isStatic = Modifier.isStatic(main.getModifiers());
          if (args) {
            return isStatic ? MainKind.STATIC_ARGS : MainKind.INSTANCE_ARGS;
          }
          return isStatic ? MainKind.STATIC : MainKind.INSTANCE;
        } catch (NoSuchMethodException e) {
          // the next form
        }
      }
      Class<?> stage = Class.forName("org.openpatch.scratch.Stage", false, loader);
      return stage.isAssignableFrom(type) ? MainKind.STAGE : MainKind.OTHER;
    } catch (ClassNotFoundException | LinkageError e) {
      throw new IOException("Could not load the start class " + start + ": " + e, e);
    }
  }

  static String launcherSource(ProjectSettings settings, String start, MainKind kind) {
    return launcherSource(settings, start, kind, false);
  }

  /**
   * Scratch4JLauncher's start, without reflection, which TeaVM cannot follow. With
   * {@code nrwList} the library makes the project's Abitur List through a factory: in a page it
   * cannot find the class by name.
   */
  static String launcherSource(ProjectSettings settings, String start, MainKind kind, boolean nrwList) {
    StringBuilder body = new StringBuilder();
    if (nrwList) {
      body.append("""
              org.openpatch.scratch.internal.NrwList.use(new org.openpatch.scratch.internal.NrwList.Factory() {
                public Object create() {
                  return new List<Object>();
                }

                @SuppressWarnings("unchecked")
                public void append(Object list, Object item) {
                  ((List<Object>) list).append(item);
                }
              });
          """);
    }
    if (settings.fullScreen) {
      body.append("    Window.useFullScreen();\n");
    }
    if (settings.pixelArt) {
      body.append("    Window.usePixelArt();\n");
    }
    if (settings.splashLogo != null && !settings.splashLogo.isBlank()) {
      body.append("    Window.useSplashLogo(\"").append(javaString(settings.splashLogo)).append("\");\n");
    }
    body.append(switch (kind) {
      case STATIC_ARGS -> "    " + start + ".main(args);\n";
      case STATIC -> "    " + start + ".main();\n";
      case INSTANCE_ARGS -> "    new " + start + "().main(args);\n";
      case INSTANCE -> "    new " + start + "().main();\n";
      case STAGE -> "    new Window(480, 360).setStage(new " + start + "());\n";
      case OTHER -> "    new " + start + "();\n";
    });
    if (settings.debugOnStart) {
      body.append("    Window.getInstance().setDebug(true);\n");
    }
    return String.format(Locale.ROOT, """
        import org.openpatch.scratch.Window;

        public class Scratch4JWebMain {
          public static void main(String[] args) throws Exception {
        %s  }
        }
        """, body);
  }

  private static String javaString(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
