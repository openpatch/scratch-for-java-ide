package org.openpatch.scratch4j.core.region;

import java.util.List;

/** Geometry checks and edge picking for the polygon hitbox editor. */
public final class HitboxPolygon {

  public record Point(double x, double y) {}

  private HitboxPolygon() {}

  /** A hitbox is a nonzero-area, non-self-intersecting polygon inside its image. */
  public static boolean valid(List<Point> points, double width, double height) {
    if (points.size() < 3 || width <= 0 || height <= 0) return false;
    double twiceArea = 0;
    for (int i = 0; i < points.size(); i++) {
      Point a = points.get(i), b = points.get((i + 1) % points.size());
      if (!Double.isFinite(a.x()) || !Double.isFinite(a.y())
          || a.x() < 0 || a.y() < 0 || a.x() > width || a.y() > height
          || a.equals(b)) return false;
      twiceArea += a.x() * b.y() - b.x() * a.y();
    }
    if (Math.abs(twiceArea) < 0.01) return false;
    for (int i = 0; i < points.size(); i++) {
      Point a = points.get(i), b = points.get((i + 1) % points.size());
      for (int j = i + 1; j < points.size(); j++) {
        if (j == i + 1 || i == 0 && j == points.size() - 1) continue;
        Point c = points.get(j), d = points.get((j + 1) % points.size());
        if (intersects(a, b, c, d)) return false;
      }
    }
    return true;
  }

  /** Index of the nearest edge's first vertex, or -1 beyond maxDistance. */
  public static int nearestEdge(List<Point> points, Point target, double maxDistance) {
    if (points.size() < 2) return -1;
    int nearest = -1;
    double best = maxDistance * maxDistance;
    for (int i = 0; i < points.size(); i++) {
      Point a = points.get(i), b = points.get((i + 1) % points.size());
      double dx = b.x() - a.x(), dy = b.y() - a.y();
      double lengthSquared = dx * dx + dy * dy;
      double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1,
          ((target.x() - a.x()) * dx + (target.y() - a.y()) * dy) / lengthSquared));
      double px = a.x() + t * dx, py = a.y() + t * dy;
      double distance = Math.pow(target.x() - px, 2) + Math.pow(target.y() - py, 2);
      if (distance < best || nearest < 0 && distance <= best) {
        best = distance;
        nearest = i;
      }
    }
    return nearest;
  }

  private static boolean intersects(Point a, Point b, Point c, Point d) {
    double abC = cross(a, b, c), abD = cross(a, b, d);
    double cdA = cross(c, d, a), cdB = cross(c, d, b);
    if (abC * abD < 0 && cdA * cdB < 0) return true;
    return abC == 0 && onSegment(a, b, c)
        || abD == 0 && onSegment(a, b, d)
        || cdA == 0 && onSegment(c, d, a)
        || cdB == 0 && onSegment(c, d, b);
  }

  private static double cross(Point a, Point b, Point c) {
    return (b.x() - a.x()) * (c.y() - a.y())
        - (b.y() - a.y()) * (c.x() - a.x());
  }

  private static boolean onSegment(Point a, Point b, Point p) {
    return p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
        && p.y() >= Math.min(a.y(), b.y()) && p.y() <= Math.max(a.y(), b.y());
  }
}
