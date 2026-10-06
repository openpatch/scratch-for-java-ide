package org.openpatch.scratch4j.core.lint;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** "Did you mean" for misspelled names: close candidates by edit distance. */
public final class DidYouMean {

  private DidYouMean() {}

  /**
   * Up to {@code limit} candidates closest to {@code word}: a case-only
   * difference first, then edit distance at most 1 (short words) or 2.
   */
  public static List<String> suggest(String word, Collection<String> candidates, int limit) {
    if (word == null || word.length() < 2) {
      return List.of();
    }
    int max = word.length() <= 4 ? 1 : 2;
    String lower = word.toLowerCase(Locale.ROOT);
    return new LinkedHashSet<>(candidates).stream()
        .filter(c -> !c.equals(word) && Math.abs(c.length() - word.length()) <= max)
        .map(c -> new Scored(c, c.toLowerCase(Locale.ROOT).equals(lower) ? 0
            : distance(lower, c.toLowerCase(Locale.ROOT))))
        .filter(s -> s.distance() <= max)
        .sorted(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name))
        .limit(limit)
        .map(Scored::name)
        .toList();
  }

  private record Scored(String name, int distance) {}

  /** Damerau-Levenshtein (optimal string alignment): swapped letters count once. */
  static int distance(String a, String b) {
    int[][] d = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= b.length(); j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
        }
      }
    }
    return d[a.length()][b.length()];
  }
}
