package org.openpatch.scratch4j.core.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Heuristic palette category for a Scratch for Java method. */
final class Categories {

  private Categories() {}

  private record Rule(String category, String... keywords) {}

  private static final List<Rule> RULES = List.of(
      new Rule("Motion", "move", "turn", "goto", "go to", "glide", "x position", "y position",
          "direction", "bounce", "point in"),
      new Rule("Looks", "say", "think", "costume", "size", "show", "hide", "layer", "front",
          "back", "backdrop", "stamp", "color effect", "ghost", "facing"),
      new Rule("Sound", "sound", "volume", "pitch", "beep", "stop all sounds"),
      new Rule("Events", "key", "clicked", "receive", "broadcast", "when"),
      new Rule("Control", "wait", "repeat", "forever", "if", "stop", "clone", "every millis",
          "sleep"),
      new Rule("Sensing", "touching", "seeing", "color is", "timer", "reset timer",
          "distance to", "mouse", "ask", "key pressed", "loudness"),
      new Rule("Operators", "+", "-", "*", "/", "<", ">", "and", "or", "not",
          "pick random", "modulo", "round", "abs"),
      new Rule("Pen", "pen", "clear pen", "erase all"));

  /** The category for an entry, derived from the scratchblock text, then the method name. */
  static String of(ApiMethod entry) {
    String scratchblock = entry.scratchblock() == null ? "" : entry.scratchblock().toLowerCase(Locale.ROOT);
    for (Rule rule : RULES) {
      for (String keyword : rule.keywords()) {
        if (scratchblock.contains(keyword)) {
          return rule.category;
        }
      }
    }
    String name = entry.methodName().toLowerCase(Locale.ROOT);
    for (Rule rule : RULES) {
      for (String keyword : rule.keywords()) {
        if (name.contains(keyword.replace(" ", ""))) {
          return rule.category;
        }
      }
    }
    return defaultCategory(entry.className());
  }

  static String defaultCategory(String className) {
    return switch (className) {
      case "Sprite", "Stage", "Window", "AnimatedSprite", "UISprite", "Text" -> className;
      case "Timer" -> "Sensing";
      case "Operators", "Random", "Color", "Vector2" -> "Operators";
      case "Pen" -> "Pen";
      default -> className;
    };
  }

  /** Entries grouped by category, categories in rule order. */
  static Map<String, List<ApiMethod>> grouped(List<ApiMethod> entries) {
    return entries.stream().collect(Collectors.groupingBy(ApiMethod::category));
  }
}
