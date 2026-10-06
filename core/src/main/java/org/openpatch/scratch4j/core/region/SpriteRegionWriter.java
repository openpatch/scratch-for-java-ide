package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The sprite tools' way into a sprite class's {@code setup} region: they own
 * their specific statements ({@code this.setRotationCenter}, {@code this.setHitbox},
 * {@code this.setNineSlice}, {@code this.setAnimationInterval}) and replace them in place, leaving every other
 * line — including hand-written ones — untouched. Unlike the stage designer's
 * full subset, this is an additive rewrite, so a hand-edited setup region stays
 * valid; only a missing region is an error.
 */
public final class SpriteRegionWriter {

  private static final Pattern ROTATION_CENTER =
      Pattern.compile("\\s*this\\.setRotationCenter\\(.*\\)\\s*;");
  private static final Pattern HITBOX =
      Pattern.compile("\\s*this\\.setHitbox\\(.*\\)\\s*;");
  private static final Pattern ANIMATION_INTERVAL =
      Pattern.compile("\\s*this\\.setAnimationInterval\\(.*\\)\\s*;");
  private static final Pattern NINE_SLICE =
      Pattern.compile("\\s*this\\.setNineSlice\\(.*\\)\\s*;");

  private SpriteRegionWriter() {}

  /** Writes (or replaces) the rotation centre line: {@code this.setRotationCenter(x, y);}. */
  public static String setRotationCenter(String source, double x, double y) {
    source = DesignerRegions.ensureSprite(source);
    return replaceOwned(source, ROTATION_CENTER,
        "this.setRotationCenter(" + RegionStatements.number(x) + ", "
            + RegionStatements.number(y) + ");");
  }

  /** Removes the rotation centre line. */
  public static String removeRotationCenter(String source) {
    source = DesignerRegions.ensureSprite(source);
    return replaceOwned(source, ROTATION_CENTER, null);
  }

  /** Writes (or replaces) the hitbox line: {@code this.setHitbox(x1, y1, x2, y2, ...);}. */
  public static String setHitbox(String source, double... points) {
    source = DesignerRegions.ensureSprite(source);
    if (points.length < 6 || points.length % 2 != 0) {
      throw new IllegalArgumentException("a hitbox needs at least three x/y pairs");
    }
    StringBuilder sb = new StringBuilder("this.setHitbox(");
    for (int i = 0; i < points.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(RegionStatements.number(points[i]));
    }
    return replaceOwned(source, HITBOX, sb.append(");").toString());
  }

  /** Removes a hitbox owned by the setup region. */
  public static String removeHitbox(String source) {
    source = DesignerRegions.ensureSprite(source);
    return replaceOwned(source, HITBOX, null);
  }

  /** Writes (or replaces) the nine-slice line: {@code this.setNineSlice(t, r, b, l);}. */
  public static String setNineSlice(String source, int top, int right,
      int bottom, int left) {
    source = DesignerRegions.ensureSprite(source);
    if (top < 0 || right < 0 || bottom < 0 || left < 0) {
      throw new IllegalArgumentException("nine-slice margins cannot be negative");
    }
    return replaceOwned(source, NINE_SLICE,
        "this.setNineSlice(" + top + ", " + right + ", " + bottom + ", " + left + ");");
  }

  /** Writes (or replaces) {@code this.setAnimationInterval(ms);} (AnimatedSprite). */
  public static String setAnimationInterval(String source, int millis) {
    source = DesignerRegions.ensureSprite(source);
    if (millis < 1) {
      throw new IllegalArgumentException("the interval must be at least 1 ms");
    }
    return replaceOwned(source, ANIMATION_INTERVAL, "this.setAnimationInterval(" + millis + ");");
  }

  /** The literal interval in the setup region, or -1. */
  public static int animationInterval(String source) {
    source = DesignerRegions.ensureSprite(source);
    java.util.regex.Matcher m = Pattern.compile("this\\.setAnimationInterval\\(\\s*(\\d+)\\s*\\)")
        .matcher(RegionParser.find(source, "setup").body());
    return m.find() ? Integer.parseInt(m.group(1)) : -1;
  }

  /**
   * Replaces every line matching {@code owned} with {@code newLine} (appended
   * at the position of the first match; removed entirely when null). Returns
   * the new source text.
   */
  private static String replaceOwned(String source, Pattern owned, String newLine) {
    Region region = RegionParser.find(source, "setup");
    List<String> lines = region.body().lines().toList();
    List<String> out = new ArrayList<>(lines.size() + 1);
    boolean replaced = false;
    for (String line : lines) {
      if (owned.matcher(line).matches()) {
        if (newLine != null && !replaced) {
          out.add(region.indent() + newLine);
          replaced = true;
        }
        // further owned lines are dropped (they were duplicates)
      } else {
        out.add(line);
      }
    }
    if (newLine != null && !replaced) {
      out.add(region.indent() + newLine);
    }
    String body = String.join("\n", out);
    if (!body.isEmpty() && !body.endsWith("\n")) {
      body += "\n";
    }
    return RegionParser.rewrite(source, "setup", body);
  }
}
