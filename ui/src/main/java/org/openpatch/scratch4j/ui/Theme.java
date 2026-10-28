package org.openpatch.scratch4j.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;

/**
 * Light/dark theme: AtlantaFX Primer as the user-agent stylesheet plus the
 * IDE's own {@code studio.css} (Scratch colours, editor syntax colours).
 * Dark mode is a {@code .dark} class on the scene root so studio.css can
 * redefine its colour tokens. Light, dark, or following the operating
 * system's setting, also when it changes while the IDE runs. Code (the
 * editor, console, problems: every {@code "monospace"} in studio.css) is in
 * the Monaspace family chosen in Settings.
 */
final class Theme {

  private static Prefs.ThemeMode mode = Prefs.themeMode();
  private static boolean dark = resolveDark();

  private Theme() {}

  static boolean isDark() {
    return dark;
  }

  static Prefs.ThemeMode mode() {
    return mode;
  }

  private static boolean resolveDark() {
    return mode == Prefs.ThemeMode.SYSTEM ? systemDark() : mode == Prefs.ThemeMode.DARK;
  }

  /** The operating system asks for dark colours (light when it cannot say). */
  private static boolean systemDark() {
    try {
      return Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
    } catch (RuntimeException e) {
      // the toolkit is not running yet
      return false;
    }
  }

  /** {@code scene} follows the operating system's light or dark setting while it changes. */
  static void followSystem(Scene scene) {
    Platform.getPreferences().colorSchemeProperty().addListener((o, old, scheme) -> {
      if (mode == Prefs.ThemeMode.SYSTEM && resolveDark() != dark) {
        dark = resolveDark();
        apply(scene);
      }
    });
  }

  private static double scale = Prefs.uiScale();
  private static boolean highContrast = Prefs.highContrast();
  private static Prefs.CodeFont codeFont = Prefs.codeFont();
  private static final java.util.Map<Prefs.CodeFont, String> FAMILIES =
      new java.util.EnumMap<>(Prefs.CodeFont.class);
  private static String studioCss;
  private static String studioCssFor;
  private static String userAgent;

  /** Text size of the whole UI: 1 (normal), 1.25, 1.5. */
  static double scale() {
    return scale;
  }

  static boolean isHighContrast() {
    return highContrast;
  }

  /** Applies the current theme to the application and {@code scene}. */
  static void apply(Scene scene) {
    dark = resolveDark();
    String next = dark ? new PrimerDark().getUserAgentStylesheet()
        : new PrimerLight().getUserAgentStylesheet();
    // only when light or dark changes: setting it restyles every window, also a
    // list closing in a dialog, which then misses the theme's colours for a moment
    if (!next.equals(userAgent)) {
      Application.setUserAgentStylesheet(next);
      userAgent = next;
    }
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

  static Prefs.CodeFont codeFont() {
    return codeFont;
  }

  static void setCodeFont(Scene scene, Prefs.CodeFont next) {
    codeFont = next;
    Prefs.codeFont(next);
    CodeEditor.FONT_FAMILY.set(codeFamily(next));
    apply(scene);
  }

  /**
   * Loads a Monaspace family (regular, bold for keywords, italic for comments)
   * once; its name as JavaFX knows it, or "monospace" if it does not load.
   */
  static synchronized String codeFamily(Prefs.CodeFont font) {
    return FAMILIES.computeIfAbsent(font, f -> {
      String family = null;
      for (String style : java.util.List.of("Regular", "Bold", "Italic")) {
        var url = Theme.class.getResource("fonts/Monaspace" + f.label() + "Frozen-" + style + ".ttf");
        javafx.scene.text.Font loaded = url == null ? null
            : javafx.scene.text.Font.loadFont(url.toExternalForm(), 14);
        if (loaded != null && family == null) family = loaded.getFamily();
      }
      return family == null ? "monospace" : family;
    });
  }

  /** studio.css in the code font with every font size scaled, plus the high-contrast sheet when on. */
  private static java.util.List<String> stylesheets() {
    java.util.List<String> sheets = new java.util.ArrayList<>();
    sheets.add(studioCss());
    if (highContrast) {
      sheets.add(Theme.class.getResource("high-contrast.css").toExternalForm());
    }
    return sheets;
  }

  /** studio.css with the code font and px font sizes multiplied by the UI scale, as data:. */
  static synchronized String studioCss() {
    String family = codeFamily(codeFont);
    String key = family + "@" + scale;
    if (studioCss == null || !key.equals(studioCssFor)) {
      try (var in = Theme.class.getResourceAsStream("studio.css")) {
        String css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("-fx-font-family: \"monospace\"", "-fx-font-family: \"" + family + "\"");
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(-fx-font-size:\\s*)(\\d+(?:\\.\\d+)?)px").matcher(css);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
          long size = Math.round(Double.parseDouble(m.group(2)) * scale);
          m.appendReplacement(out, m.group(1) + size + "px");
        }
        m.appendTail(out);
        studioCss = "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString(
            out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        studioCssFor = key;
      } catch (java.io.IOException e) {
        return Theme.class.getResource("studio.css").toExternalForm();
      }
    }
    return studioCss;
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

  /** Keeps a modal dialog attached to its window, including under XWayland. */
  static void style(javafx.scene.control.Dialog<?> dialog, javafx.stage.Window owner) {
    dialog.initOwner(owner);
    dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
    style(dialog);
  }

  /** Styles another window's scene like the main window. */
  static void style(Scene scene) {
    scene.getStylesheets().addAll(stylesheets());
    if (dark) {
      scene.getRoot().getStyleClass().add("dark");
    }
    if (highContrast) {
      scene.getRoot().getStyleClass().add("high-contrast");
    }
    scene.getRoot().setStyle("-fx-font-size: " + Math.round(14 * scale) + "px;");
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

  static void setMode(Scene scene, Prefs.ThemeMode next) {
    mode = next;
    Prefs.themeMode(next);
    apply(scene);
  }
}
