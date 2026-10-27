package org.openpatch.scratch4j.ui;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Which suggestions fit what was typed, and in which order: names that start
 * with it (same case first), then names whose word starts it spells out, as
 * in IntelliJ ({@code gX} finds {@code getX}, {@code swCo}
 * {@code switchCostumeTo}). Within each group the names picked recently
 * come first.
 */
final class CompletionMatch {

  /** Not a match. */
  static final int NONE = -1;
  private static final int MAX_TYPED = 40;
  private static final int MAX_RECENT = 100;
  /** Accepted names, the most recent last (shared by all editors of the session). */
  private static final Map<String, Long> RECENT = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
      return size() > MAX_RECENT;
    }
  };
  private static long uses;

  private CompletionMatch() {}

  /**
   * 0: {@code name} starts with {@code typed}; 1: it does in another case;
   * 2: {@code typed} spells the starts of its words; {@link #NONE} otherwise.
   */
  static int rank(String name, String typed) {
    if (name.startsWith(typed)) return 0;
    if (name.regionMatches(true, 0, typed, 0, typed.length())) return 1;
    if (typed.length() <= MAX_TYPED && humps(name, 0, typed, 0)) return 2;
    return NONE;
  }

  /** Remembers that {@code name} was picked, so it comes first next time. */
  static synchronized void used(String name) {
    RECENT.put(name, ++uses);
  }

  /** By {@link #rank}, then recently picked names, then the order they came in. */
  static synchronized <T> Comparator<T> order(String typed, Function<T, String> name) {
    Map<String, Long> recent = Map.copyOf(RECENT);
    return Comparator.<T>comparingInt(item -> rank(name.apply(item), typed))
        .thenComparing(item -> -recent.getOrDefault(name.apply(item), 0L));
  }

  /**
   * Whether {@code typed} from {@code t} matches {@code name} from {@code n}:
   * a run of letters there, then the rest from the start of a later word.
   */
  private static boolean humps(String name, int n, String typed, int t) {
    for (int k = n; k < name.length() && t + k - n < typed.length()
        && sameLetter(name.charAt(k), typed.charAt(t + k - n)); k++) {
      int next = t + k - n + 1;
      if (next == typed.length()) return true;
      for (int s = k + 1; s < name.length(); s++) {
        if (wordStart(name, s) && humps(name, s, typed, next)) return true;
      }
    }
    return false;
  }

  private static boolean sameLetter(char a, char b) {
    return Character.toLowerCase(a) == Character.toLowerCase(b);
  }

  /** {@code getX}: X; {@code MAX_SPEED}: S; {@code sprite2}: 2; {@code HTTPServer}: S. */
  private static boolean wordStart(String name, int at) {
    char c = name.charAt(at);
    char before = name.charAt(at - 1);
    if (c == '_') return false;
    if (before == '_') return true;
    if (Character.isDigit(c)) return !Character.isDigit(before);
    if (!Character.isUpperCase(c)) return false;
    return !Character.isUpperCase(before)
        || at + 1 < name.length() && Character.isLowerCase(name.charAt(at + 1));
  }
}
