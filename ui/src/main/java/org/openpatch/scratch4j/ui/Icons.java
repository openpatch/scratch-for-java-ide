package org.openpatch.scratch4j.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;

/** Feather icons (Ikonli) and small factories for icon buttons. */
final class Icons {

  private Icons() {}

  /**
   * A Feather icon by its Ikonli literal, e.g. {@code fth-play}. An unknown
   * name falls back to a dot instead of failing (IconsTest keeps every
   * literal in the sources valid).
   */
  static FontIcon of(String literal) {
    try {
      return new FontIcon(literal);
    } catch (IllegalArgumentException e) {
      return new FontIcon("fth-circle");
    }
  }

  static FontIcon of(String literal, int size) {
    FontIcon icon = of(literal);
    icon.setIconSize(size);
    return icon;
  }

  /** The icon for a file in the tree and on editor tabs. */
  static FontIcon forFile(java.nio.file.Path file) {
    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (java.nio.file.Files.isDirectory(file)) {
      return of("fth-folder");
    }
    if (name.endsWith(".java")) {
      return of("fth-code");
    }
    if (name.matches(".*\\.(png|jpg|jpeg|gif)$")) {
      return of("fth-image");
    }
    if (name.matches(".*\\.(wav|ogg|mp3|aiff|au)$")) {
      return of("fth-music");
    }
    if (name.endsWith(".jar")) {
      return of("fth-package");
    }
    return of("fth-file-text");
  }

  /** A flat, icon-only button with a tooltip (toolbars). */
  static Button button(String literal, String tooltip, Runnable action) {
    Button button = new Button(null, of(literal));
    button.getStyleClass().addAll("button-icon", "flat");
    button.setTooltip(new Tooltip(tooltip));
    button.setOnAction(e -> action.run());
    return button;
  }

  /** A flat button with icon and text. */
  static Button labeled(String literal, String text, Runnable action) {
    Button button = new Button(text, of(literal));
    button.getStyleClass().add("flat");
    button.setOnAction(e -> action.run());
    return button;
  }
}
