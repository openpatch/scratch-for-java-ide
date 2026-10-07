package org.openpatch.scratch4j.core.region;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The program's entry point: a {@code Window} subclass whose managed regions
 * hold the global settings (edited in the IDE's settings dialog) and the first
 * stage (the stage selector's start star):
 *
 * <pre>
 * public class MyWindow extends Window {
 *   public MyWindow() {
 *     super(480, 360);
 *     // scratch4j:begin window
 *     this.setStage(new MyStage());
 *     this.setDebug(true);                                    (debug overlay on start)
 *     // scratch4j:end window
 *   }
 *
 *   public static void main(String[] args) {
 *     // scratch4j:begin options
 *     Window.useFullScreen();
 *     Window.useTextureSampling(TextureSampling.POINT);       (pixel art)
 *     Window.useSplashLogo("assets/images/logo.png");
 *     // scratch4j:end options
 *     new MyWindow();
 *   }
 * }
 * </pre>
 *
 * <p>Like the stage designer, only these statements are understood; anything
 * else in the regions makes the settings read-only instead of being lost.
 */
public final class WindowDocument {

  public static final String SETUP = "window";
  public static final String OPTIONS = "options";
  public static final String MANAGED = " (managed by the project settings)";

  /** The settings a window class holds. {@code startStage} may be null. */
  public record Settings(int width, int height, String startStage, boolean fullScreen,
      boolean pixelArt, String splashLogo, boolean debug) {

    public Settings withStartStage(String stage) {
      return new Settings(width, height, stage, fullScreen, pixelArt, splashLogo, debug);
    }
  }

  private static final Pattern SUPER =
      Pattern.compile("super\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(,[^)]*)?\\)");
  private static final Pattern SET_STAGE =
      Pattern.compile("this\\.setStage\\(new\\s+(\\w+)\\(\\)\\)\\s*;");
  private static final Pattern DEBUG = Pattern.compile("this\\.setDebug\\((true|false)\\)\\s*;");
  private static final Pattern FULL_SCREEN =
      Pattern.compile("(?:org\\.openpatch\\.scratch\\.)?Window\\.useFullScreen\\(\\)\\s*;");
  private static final Pattern SAMPLING = Pattern.compile(
      "(?:org\\.openpatch\\.scratch\\.)?Window\\.useTextureSampling\\("
          + "(?:org\\.openpatch\\.scratch\\.)?TextureSampling\\.(\\w+)\\)\\s*;");
  private static final Pattern SPLASH = Pattern.compile(
      "(?:org\\.openpatch\\.scratch\\.)?Window\\.useSplashLogo\\(\"([^\"\\\\]*)\"\\)\\s*;");

  private WindowDocument() {}

  /** Whether a source is a window class the settings dialog manages. */
  public static boolean isManaged(String source) {
    return RegionParser.has(source, SETUP) && RegionParser.has(source, OPTIONS);
  }

  /** Reads the settings; throws when a region holds statements outside the subset. */
  public static Settings read(String source) {
    int width = 480;
    int height = 360;
    Matcher size = SUPER.matcher(source);
    if (size.find()) {
      width = Integer.parseInt(size.group(1));
      height = Integer.parseInt(size.group(2));
    }
    String start = null;
    boolean debug = false;
    for (String line : lines(RegionParser.find(source, SETUP).body())) {
      Matcher m;
      if ((m = SET_STAGE.matcher(line)).matches()) {
        start = m.group(1);
      } else if ((m = DEBUG.matcher(line)).matches()) {
        debug = Boolean.parseBoolean(m.group(1));
      } else {
        throw new RegionStatements.UnsupportedRegionException(
            "window setup: not a managed statement: " + line);
      }
    }
    boolean fullScreen = false;
    boolean pixelArt = false;
    String splash = "";
    for (String line : lines(RegionParser.find(source, OPTIONS).body())) {
      Matcher m;
      if (FULL_SCREEN.matcher(line).matches()) {
        fullScreen = true;
      } else if ((m = SAMPLING.matcher(line)).matches()) {
        pixelArt = m.group(1).equals("POINT");
      } else if ((m = SPLASH.matcher(line)).matches()) {
        splash = m.group(1);
      } else {
        throw new RegionStatements.UnsupportedRegionException(
            "window options: not a managed statement: " + line);
      }
    }
    return new Settings(width, height, start, fullScreen, pixelArt, splash, debug);
  }

  /** Writes the settings back into the regions (and {@code super(w, h)}). */
  public static String write(String source, Settings settings) {
    Region setup = RegionParser.find(source, SETUP);
    StringBuilder setupBody = new StringBuilder();
    if (settings.startStage() != null && !settings.startStage().isBlank()) {
      setupBody.append(setup.indent()).append("this.setStage(new ")
          .append(settings.startStage()).append("());\n");
    }
    if (settings.debug()) {
      setupBody.append(setup.indent()).append("this.setDebug(true);\n");
    }
    String result = RegionParser.rewrite(source, SETUP, setupBody.toString());
    Region options = RegionParser.find(result, OPTIONS);
    StringBuilder optionsBody = new StringBuilder();
    if (settings.fullScreen()) {
      optionsBody.append(options.indent()).append("Window.useFullScreen();\n");
    }
    if (settings.pixelArt()) {
      optionsBody.append(options.indent())
          .append("Window.useTextureSampling(TextureSampling.POINT);\n");
    }
    if (settings.splashLogo() != null && !settings.splashLogo().isBlank()) {
      optionsBody.append(options.indent()).append("Window.useSplashLogo(\"")
          .append(settings.splashLogo().replace("\\", "/").replace("\"", ""))
          .append("\");\n");
    }
    result = RegionParser.rewrite(result, OPTIONS, optionsBody.toString());
    Matcher size = SUPER.matcher(result);
    if (size.find()) {
      String rest = size.group(3) == null ? "" : size.group(3);
      result = result.substring(0, size.start()) + "super(" + settings.width() + ", "
          + settings.height() + rest + ")" + result.substring(size.end());
    }
    if (settings.pixelArt()) {
      result = org.openpatch.scratch4j.core.project.JavaImports.ensure(result,
          "org.openpatch.scratch.TextureSampling");
    }
    return result;
  }

  /** A new window class: the program's start, the first stage, the settings. */
  public static String create(String className, Settings settings) {
    String source = """
        import org.openpatch.scratch.Window;

        // The program starts here: the window, its settings and the first stage.
        // The settings dialog (Stages > window settings) edits the marked regions.
        public class %1$s extends Window {

          public %1$s() {
            super(%2$d, %3$d);
            // scratch4j:begin %4$s%6$s
            // scratch4j:end %4$s
          }

          public static void main(String[] args) {
            // scratch4j:begin %5$s%6$s
            // scratch4j:end %5$s
            new %1$s();
          }
        }
        """.formatted(className, settings.width(), settings.height(), SETUP, OPTIONS, MANAGED);
    return write(source, settings);
  }

  private static List<String> lines(String body) {
    return body.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }
}
