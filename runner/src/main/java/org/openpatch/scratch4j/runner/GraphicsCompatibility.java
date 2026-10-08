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
   * What Processing and JOGL print when the OpenGL driver cannot do what the
   * renderer needs: no usable profile, no framebuffer objects, no device.
   */
  private static final List<Pattern> FAILURES = List.of(
      Pattern.compile("Framebuffer objects are not supported"),
      Pattern.compile("GLProfile: device could not be initialized"),
      Pattern.compile("Renderer cannot find a JOGL surface"),
      // JOGL only throws GLException when the driver fails (context, profile, buffers)
      Pattern.compile("^(?:Exception in thread \"[^\"]*\" )?(?:Caused by: )?"
          + "com\\.jogamp\\.opengl\\.GLException"),
      Pattern.compile("Profile \\w+ is not available", Pattern.CASE_INSENSITIVE),
      Pattern.compile("UnsatisfiedLinkError.*(jogl|gluegen|nativewindow|newt)",
          Pattern.CASE_INSENSITIVE),
      Pattern.compile("OpenGL 2(\\.0)? (is )?required|does not support OpenGL",
          Pattern.CASE_INSENSITIVE));

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
