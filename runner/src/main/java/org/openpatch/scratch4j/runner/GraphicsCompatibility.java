package org.openpatch.scratch4j.runner;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compatibility graphics for machines whose OpenGL driver cannot run the
 * library's OpenGL renderer (old school PCs, virtual machines, remote
 * desktops). When on, every program the IDE starts (runs, behavior checks,
 * shader previews) asks Mesa for its software renderer. That works wherever
 * the OpenGL driver is Mesa: almost every Linux, and Windows machines where
 * IT installed Mesa (as the IDE's own Windows checks do). Elsewhere the
 * variables do nothing, so turning it on never makes things worse.
 *
 * <p>The setting is machine-wide: the IDE sets it from its preferences.
 */
public final class GraphicsCompatibility {

  /** Mesa's switches for its software rasterizer. */
  static final Map<String, String> SOFTWARE_ENVIRONMENT = Map.of(
      "LIBGL_ALWAYS_SOFTWARE", "1",
      "GALLIUM_DRIVER", "llvmpipe");

  /**
   * What Scratch for Java 7 (LWJGL/GLFW) prints when the OpenGL driver cannot do what the
   * renderer needs: no OpenGL 3.3 core context, no display, GLFW failing to start.
   */
  private static final List<Pattern> FAILURES = List.of(
      // the library's own message (WindowHost): no GL 3.3 core context or no window at all
      Pattern.compile("Scratch for Java could not open its window"),
      Pattern.compile("It needs OpenGL 3\\.3"),
      // GLFW's error callback: "[LWJGL] GLFW_VERSION_UNAVAILABLE error", "GLFW_API_UNAVAILABLE", ...
      Pattern.compile("GLFW_(VERSION|API)_UNAVAILABLE"),
      Pattern.compile("GLFW could not start"),
      Pattern.compile("no OpenGL 3\\.3 core context"),
      Pattern.compile("UnsatisfiedLinkError.*(lwjgl|glfw)", Pattern.CASE_INSENSITIVE));

  private static volatile boolean software;

  private GraphicsCompatibility() {}

  public static boolean isSoftware() {
    return software;
  }

  public static void setSoftware(boolean on) {
    software = on;
  }

  /** Puts the software-rendering variables into {@code builder} while the setting is on. */
  public static ProcessBuilder apply(ProcessBuilder builder) {
    if (software) {
      builder.environment().putAll(SOFTWARE_ENVIRONMENT);
    }
    return builder;
  }

  /** A line of a program's error output that says its OpenGL driver failed. */
  public static boolean isGraphicsFailure(String line) {
    if (line == null || line.isBlank()) {
      return false;
    }
    for (Pattern failure : FAILURES) {
      if (failure.matcher(line).find()) {
        return true;
      }
    }
    return false;
  }

  /** Whether the IDE runs on Windows (where Mesa must be installed by IT). */
  public static boolean onWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
