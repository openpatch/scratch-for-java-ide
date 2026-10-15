package org.openpatch.scratch4j.core.region;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A window made in a {@code main} method, the way the tutorials and demos
 * start (the window first, then its stage):
 *
 * <pre>
 * public static void main(String[] args) {
 *   Window.useFullScreen();                                  (full screen)
 *   Window.useTextureSampling(TextureSampling.POINT);        (pixel art)
 *   Window.useSplashLogo("assets/images/logo.png");
 *   Window myWindow = new Window(600, 400);
 *   myWindow.setStage(new CatchStage());
 *   myWindow.setDebug(true);                                 (debug overlay on start)
 * }
 * </pre>
 *
 * <p>The settings dialog reads these statements and changes them where they
 * are; the rest of {@code main} stays as it was written, and so does the
 * {@code setStage} line (the start star changes that one). A size that is not
 * written as two numbers makes the settings read-only.
 */
public final class MainWindow {

  private static final Pattern MAIN = Pattern.compile(
      "\\bvoid\\s+main\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");
  /** {@code [Window|var] name = new Window(...)}, {@code name = new Window(...)} or no name. */
  private static final Pattern NEW_WINDOW = Pattern.compile(
      "(?:(?:(?:org\\.openpatch\\.scratch\\.)?Window|var)\\s+(\\w+)\\s*=\\s*|\\b(\\w+)\\s*=\\s*)?"
          + "\\bnew\\s+(?:org\\.openpatch\\.scratch\\.)?Window\\s*\\(([^()]*)\\)");
  private static final Pattern SIZE =
      Pattern.compile("\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(,.*)?", Pattern.DOTALL);

  private MainWindow() {}

  /** Where the window is made: the {@code main} body and the {@code new Window(...)} in it. */
  private record Place(int bodyStart, int bodyEnd, String variable) {}

  /** Whether {@code source} has a {@code main} that makes a {@code new Window(...)}. */
  public static boolean has(String source) {
    return place(source) != null;
  }

  private static Place place(String source) {
    Matcher main = MAIN.matcher(source);
    while (main.find()) {
      int end = DesignerRegions.matchingBrace(source, main.end() - 1);
      if (end < 0) continue;
      Matcher window = NEW_WINDOW.matcher(source.substring(main.end(), end));
      if (window.find()) {
        return new Place(main.end(), end,
            window.group(1) != null ? window.group(1) : window.group(2));
      }
    }
    return null;
  }

  /**
   * The settings the window in {@code main} is made with; throws when there is
   * none, or its size is not two numbers.
   */
  public static WindowDocument.Settings read(String source) {
    Place place = place(source);
    if (place == null) {
      throw new IllegalArgumentException("no new Window(...) in a main method");
    }
    String body = source.substring(place.bodyStart(), place.bodyEnd());
    Matcher window = NEW_WINDOW.matcher(body);
    window.find();
    int width = 480;
    int height = 360;
    if (!window.group(3).isBlank()) {
      Matcher size = SIZE.matcher(window.group(3));
      if (!size.matches()) {
        throw new RegionStatements.UnsupportedRegionException(
            "the window's size is not two numbers: new Window(" + window.group(3) + ")");
      }
      width = Integer.parseInt(size.group(1));
      height = Integer.parseInt(size.group(2));
    }
    Matcher sampling = WindowDocument.SAMPLING.matcher(body);
    Matcher splash = WindowDocument.SPLASH.matcher(body);
    Matcher stage = setStage(place.variable()).matcher(body);
    Matcher debug = debug(place.variable()).matcher(body);
    return new WindowDocument.Settings(width, height, stage.find() ? stage.group(1) : null,
        WindowDocument.FULL_SCREEN.matcher(body).find(),
        sampling.find() && sampling.group(1).equals("POINT"),
        splash.find() ? splash.group(1) : "",
        debug.find() && debug.group(1).equals("true"));
  }

  /**
   * {@code source} with the window in {@code main} made with {@code settings}:
   * the size in {@code new Window(...)}, the options before it, the debug
   * overlay after the stage is set. Unchanged when it already is.
   */
  public static String write(String source, WindowDocument.Settings settings) {
    WindowDocument.Settings now = read(source);
    if (now.withStartStage(null).equals(settings.withStartStage(null))) {
      return source;
    }
    Place place = place(source);
    String body = source.substring(place.bodyStart(), place.bodyEnd());
    for (Pattern option : new Pattern[] {WindowDocument.FULL_SCREEN, WindowDocument.SAMPLING,
        WindowDocument.SPLASH, debug(place.variable())}) {
      body = removeLines(body, option);
    }
    // the options come before the window is made
    Matcher window = NEW_WINDOW.matcher(body);
    window.find();
    int lineStart = body.lastIndexOf('\n', window.start()) + 1;
    String indent = body.substring(lineStart).replaceFirst("(?s)^([ \\t]*).*", "$1");
    StringBuilder options = new StringBuilder();
    if (settings.fullScreen()) {
      options.append(indent).append("Window.useFullScreen();\n");
    }
    if (settings.pixelArt()) {
      options.append(indent).append("Window.useTextureSampling(TextureSampling.POINT);\n");
    }
    if (settings.splashLogo() != null && !settings.splashLogo().isBlank()) {
      options.append(indent).append("Window.useSplashLogo(\"")
          .append(settings.splashLogo().replace("\\", "/").replace("\"", "")).append("\");\n");
    }
    body = body.substring(0, lineStart) + options + body.substring(lineStart);
    // the size, keeping what follows it (the assets folder)
    window = NEW_WINDOW.matcher(body);
    window.find();
    Matcher size = SIZE.matcher(window.group(3));
    String rest = size.matches() && size.group(3) != null ? size.group(3) : "";
    body = body.substring(0, window.start(3)) + settings.width() + ", " + settings.height()
        + rest + body.substring(window.end(3));
    // the debug overlay once the stage is set (or the window made)
    if (settings.debug()) {
      Matcher stage = setStage(place.variable()).matcher(body);
      window = NEW_WINDOW.matcher(body);
      window.find();
      int after = stage.find() ? stage.end() : window.end();
      int lineEnd = body.indexOf('\n', after);
      String owner = place.variable() != null ? place.variable() : "Window.getInstance()";
      String line = indent + owner + ".setDebug(true);\n";
      body = lineEnd < 0 ? body + "\n" + line : body.substring(0, lineEnd + 1) + line
          + body.substring(lineEnd + 1);
    }
    String result = source.substring(0, place.bodyStart()) + body
        + source.substring(place.bodyEnd());
    if (settings.pixelArt()) {
      result = org.openpatch.scratch4j.core.project.JavaImports.ensure(result,
          "org.openpatch.scratch.TextureSampling");
    }
    return result;
  }

  private static Pattern setStage(String variable) {
    return Pattern.compile("(?:\\b" + (variable == null ? "\\w+" : Pattern.quote(variable))
        + "|Window\\.getInstance\\(\\))\\s*\\.\\s*setStage\\(\\s*new\\s+(\\w+)\\s*\\(");
  }

  private static Pattern debug(String variable) {
    return Pattern.compile("(?:\\b" + (variable == null ? "\\w+" : Pattern.quote(variable))
        + "|Window\\.getInstance\\(\\))\\s*\\.\\s*setDebug\\((true|false)\\)\\s*;");
  }

  /** {@code body} without the whole lines that hold only {@code statement}. */
  private static String removeLines(String body, Pattern statement) {
    return Pattern.compile("(?m)^[ \\t]*(?:" + statement.pattern()
        + ")[ \\t]*(?://[^\\n]*)?\\r?\\n")
        .matcher(body).replaceAll("");
  }
}
