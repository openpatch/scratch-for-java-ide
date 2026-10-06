package org.openpatch.scratch4j.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;

/**
 * Light/dark theme: AtlantaFX Primer as the user-agent stylesheet plus the
 * IDE's own {@code studio.css} (Scratch colours, editor syntax colours).
 * Dark mode is a {@code .dark} class on the scene root so studio.css can
 * redefine its colour tokens.
 */
final class Theme {

  private static boolean dark = Prefs.darkTheme();

  private Theme() {}

  static boolean isDark() {
    return dark;
  }

  private static double scale = Prefs.uiScale();
  private static boolean highContrast = Prefs.highContrast();
  private static String scaledCss;
  private static double scaledFor = -1;

  /** Text size of the whole UI: 1 (normal), 1.25, 1.5. */
  static double scale() {
    return scale;
  }

  static boolean isHighContrast() {
    return highContrast;
  }

  /** Applies the current theme to the application and {@code scene}. */
  static void apply(Scene scene) {
    Application.setUserAgentStylesheet(dark
        ? new PrimerDark().getUserAgentStylesheet()
        : new PrimerLight().getUserAgentStylesheet());
    scene.getStylesheets().removeIf(css -> css.endsWith("studio.css")
        || css.endsWith("high-contrast.css") || css.startsWith("data:text/css"));
    scene.getStylesheets().addAll(stylesheets());
    scene.getRoot().getStyleClass().removeAll("dark", "high-contrast");
    if (dark) {
      scene.getRoot().getStyleClass().add("dark");
    }
    if (highContrast) {
      scene.getRoot().getStyleClass().add("high-contrast");
    }
    // AtlantaFX sizes its controls from the root font size
    scene.getRoot().setStyle("-fx-font-size: " + Math.round(14 * scale) + "px;");
  }

  /** studio.css with every font size scaled, plus the high-contrast sheet when on. */
  private static java.util.List<String> stylesheets() {
    java.util.List<String> sheets = new java.util.ArrayList<>();
    sheets.add(scale == 1 ? Theme.class.getResource("studio.css").toExternalForm()
        : scaledStudioCss());
    if (highContrast) {
      sheets.add(Theme.class.getResource("high-contrast.css").toExternalForm());
    }
    return sheets;
  }

  /** px font sizes multiplied by the UI scale, as a data: stylesheet. */
  static synchronized String scaledStudioCss() {
    if (scaledCss == null || scaledFor != scale) {
      try (var in = Theme.class.getResourceAsStream("studio.css")) {
        String css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(-fx-font-size:\\s*)(\\d+(?:\\.\\d+)?)px").matcher(css);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
          long size = Math.round(Double.parseDouble(m.group(2)) * scale);
          m.appendReplacement(out, m.group(1) + size + "px");
        }
        m.appendTail(out);
        scaledCss = "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString(
            out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        scaledFor = scale;
      } catch (java.io.IOException e) {
        return Theme.class.getResource("studio.css").toExternalForm();
      }
    }
    return scaledCss;
  }

  /** Styles a dialog's scene like the main window. */
  static void style(javafx.scene.control.Dialog<?> dialog) {
    var pane = dialog.getDialogPane();
    pane.getStylesheets().addAll(stylesheets());
    if (dark) {
      pane.getStyleClass().add("dark");
    }
    if (highContrast) {
      pane.getStyleClass().add("high-contrast");
    }
    pane.setStyle("-fx-font-size: " + Math.round(14 * scale) + "px;");
  }

  static void setScale(Scene scene, double next) {
    scale = next;
    Prefs.uiScale(next);
    apply(scene);
  }

  static void setHighContrast(Scene scene, boolean on) {
    highContrast = on;
    Prefs.highContrast(on);
    apply(scene);
  }

  static void toggle(Scene scene) {
    dark = !dark;
    Prefs.darkTheme(dark);
    apply(scene);
  }
}
