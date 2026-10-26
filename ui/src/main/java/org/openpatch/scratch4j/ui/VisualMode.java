package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Which Java sources have a visual editor (stage designer or sprite asset editor). */
final class VisualMode {

  private VisualMode() { }

  /** A Java file whose class is a stage (also through project superclasses). */
  static boolean isStageSource(Path file) {
    return paletteContext(file) == PaletteContext.STAGE;
  }

  /** A window class with the managed settings regions (its "visual editor" is the settings dialog). */
  static boolean isWindowSource(Path file) {
    return className(file) != null
        && org.openpatch.scratch4j.core.region.WindowDocument.isManaged(read(file));
  }

  /** A sprite class: Sprite, AnimatedSprite or UISprite, also through project superclasses. */
  static boolean isSpriteSource(Path file) {
    return paletteContext(file) == PaletteContext.SPRITE;
  }

  /** The visual editor a class has: the stage designer, the sprite editor, window settings. */
  enum Kind { STAGE, SPRITE, WINDOW, NONE }

  static Kind kind(Path file) {
    return switch (paletteContext(file)) {
      case STAGE -> Kind.STAGE;
      case SPRITE -> Kind.SPRITE;
      case NONE -> isWindowSource(file) ? Kind.WINDOW : Kind.NONE;
    };
  }

  static String className(Path file) {
    if (file == null) return null;
    String name = file.getFileName().toString();
    return name.endsWith(".java") ? name.substring(0, name.length() - ".java".length()) : null;
  }

  /** Which blocks fit a file: a sprite's, a stage's, or none (the palette hides). */
  enum PaletteContext { SPRITE, STAGE, NONE }

  private static final Pattern EXTENDS =
      Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+(\\w+)");

  /**
   * The palette context of a Java file, following the project's own class
   * hierarchy ({@code Bamboo extends Enemy extends AnimatedSprite} is a sprite).
   */
  static PaletteContext paletteContext(Path file) {
    String base = libraryBase(file);
    if (base == null) return PaletteContext.NONE;
    return switch (base) {
      case "Sprite", "AnimatedSprite", "UISprite" -> PaletteContext.SPRITE;
      case "Stage" -> PaletteContext.STAGE;
      default -> PaletteContext.NONE;
    };
  }

  /** Scratch for Java 7: every sprite has animations (AnimatedSprite is part of Sprite). */
  static boolean isAnimatedSprite(Path file) {
    String base = libraryBase(file);
    return "Sprite".equals(base) || "AnimatedSprite".equals(base) || "UISprite".equals(base);
  }

  /**
   * The library class a Java file's class comes from through the project's own
   * classes ({@code Sprite}, {@code AnimatedSprite}, {@code UISprite},
   * {@code Stage}), or null.
   */
  private static String libraryBase(Path file) {
    String name = className(file);
    if (name == null) return null;
    Path dir = file.toAbsolutePath().getParent();
    for (int depth = 0; depth < 10 && name != null; depth++) {
      Path source = dir.resolve(name + ".java");
      java.util.regex.Matcher m = EXTENDS.matcher(read(source));
      String parent = null;
      while (m.find()) {
        if (m.group(1).equals(name)) {
          parent = m.group(2);
          break;
        }
      }
      if (parent == null) return null;
      switch (parent) {
        case "Sprite", "AnimatedSprite", "UISprite", "Stage" -> {
          return parent;
        }
        default -> name = Files.isRegularFile(dir.resolve(parent + ".java")) ? parent : null;
      }
    }
    return null;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return "";
    }
  }
}
