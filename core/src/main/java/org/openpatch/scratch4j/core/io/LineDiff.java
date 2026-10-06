package org.openpatch.scratch4j.core.io;

import java.util.ArrayList;
import java.util.List;

/** A line diff (longest common subsequence) for showing what changed between versions. */
public final class LineDiff {

  /** One line: {@code ' '} the same, {@code '-'} only then, {@code '+'} only now. */
  public record Line(char kind, String text, int oldLine, int newLine) {}

  private LineDiff() {}

  public static List<Line> diff(String then, String now) {
    String[] a = then == null ? new String[0] : then.split("\n", -1);
    String[] b = now == null ? new String[0] : now.split("\n", -1);
    int n = a.length;
    int m = b.length;
    // very long files: a plain listing instead of a quadratic table
    if ((long) n * m > 4_000_000L) {
      List<Line> out = new ArrayList<>();
      for (int i = 0; i < n; i++) out.add(new Line('-', a[i], i + 1, 0));
      for (int j = 0; j < m; j++) out.add(new Line('+', b[j], 0, j + 1));
      return out;
    }
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1
            : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    List<Line> out = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && a[i].equals(b[j])) {
        out.add(new Line(' ', a[i], i + 1, j + 1));
        i++;
        j++;
      } else if (j < m && (i == n || lcs[i][j + 1] > lcs[i + 1][j])) {
        out.add(new Line('+', b[j], 0, j + 1));
        j++;
      } else {
        out.add(new Line('-', a[i], i + 1, 0));
        i++;
      }
    }
    return out;
  }
}
