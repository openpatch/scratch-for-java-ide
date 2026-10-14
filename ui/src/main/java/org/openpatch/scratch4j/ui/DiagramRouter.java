package org.openpatch.scratch4j.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Right-angled routes for the edges of a diagram: around the boxes, through the
 * gaps between them, and each on a lane of its own, so two arrows never run on
 * top of each other. Edges to the same parent class share their last stretch
 * and meet under one triangle, as UML draws inheritance.
 *
 * <p>The boxes are obstacles on a grid of lanes; each edge is the cheapest path
 * on that grid (A*). A bend, a crossing and a lane right next to another edge
 * cost a little, a lane another edge already uses costs a lot. Every edge
 * leaves and enters its box at a port of its own, ordered along the side so
 * neighbouring edges do not cross right at the box.
 */
final class DiagramRouter {

  /** An edge to route; edges with the same {@code bundle} may share lanes (one parent). */
  record Link(String from, String to, String bundle) {}

  /** The polyline from the border of the source box to the border of the target box. */
  record Route(List<double[]> points) {

    double[] end() {
      return points.get(points.size() - 1);
    }

    /** The point before the end: the arrow head points from there to {@link #end()}. */
    double[] beforeEnd() {
      return points.get(points.size() - 2);
    }
  }

  private enum Side {
    TOP(0, -1), BOTTOM(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    final int dx;
    final int dy;

    Side(int dx, int dy) {
      this.dx = dx;
      this.dy = dy;
    }

    /** A side lying horizontally: its ports are spread along x. */
    boolean horizontal() {
      return this == TOP || this == BOTTOM;
    }
  }

  /** Distance between free lanes. */
  static final double STEP = 8;
  /** Lines keep this far from the boxes (and the arrow heads fit in). */
  static final double MARGIN = 14;
  private static final double BEND = 24;
  private static final double CROSS = 16;
  private static final double NEAR = 0.6;
  private static final double OVERLAP = 600;
  private static final double SHARED = 0.3;
  private static final double OFF_CENTER = 0.15;
  /** A larger diagram keeps straight lines: routing it would take too long to stay live. */
  private static final int MAX_NODES = 160_000;

  private DiagramRouter() {}

  /**
   * Routes every link between the boxes ({@code x, y, w, h} by id). The result
   * lines up with {@code links}; it holds {@code null} for a link that names a
   * missing box, joins two overlapping boxes, or finds no way through.
   */
  static List<Route> route(Map<String, double[]> boxes, List<Link> links) {
    Route[] routes = new Route[links.size()];
    // the sides each link leaves and enters by
    Side[][] sides = new Side[links.size()][];
    for (int i = 0; i < links.size(); i++) {
      Link l = links.get(i);
      double[] a = boxes.get(l.from());
      double[] b = boxes.get(l.to());
      if (a == null || b == null) continue;
      if (l.from().equals(l.to())) {
        routes[i] = loop(a);
      } else if (!overlap(a, b)) {
        sides[i] = sides(l, a, b, boxes);
      }
    }
    double[][][] ports = ports(boxes, links, sides);
    Grid grid = Grid.of(boxes, ports);
    if (grid == null) return Arrays.asList(routes);

    // the subclasses of each parent first, as one tree; then the shortest links
    Map<String, List<Integer>> bundles = new LinkedHashMap<>();
    List<Integer> single = new ArrayList<>();
    for (int i = 0; i < links.size(); i++) {
      if (ports[i] == null) continue;
      Link l = links.get(i);
      if (l.bundle() == null) {
        single.add(i);
      } else {
        bundles.computeIfAbsent(l.bundle() + "->" + l.to(), k -> new ArrayList<>()).add(i);
      }
    }
    List<List<Integer>> trees = new ArrayList<>(bundles.values());
    trees.sort(Comparator.comparingInt((List<Integer> t) -> -t.size())
        .thenComparingDouble(t -> ports[t.get(0)][1][0]));
    int id = 0;
    for (List<Integer> tree : trees) {
      id++;
      Side fromSide = sides[tree.get(0)][0];
      boolean upright = fromSide.horizontal()
          && tree.stream().allMatch(i -> sides[i][0] == fromSide && sides[i][1] != fromSide);
      Route[] grown = upright ? grid.tree(tree.stream().map(i -> ports[i][0]).toList(),
          fromSide, ports[tree.get(0)][1], id) : null;
      for (int k = 0; k < tree.size(); k++) {
        int i = tree.get(k);
        routes[i] = grown != null ? grown[k]
            : grid.route(ports[i][0], sides[i][0], ports[i][1], sides[i][1], id);
      }
    }
    single.sort(Comparator.comparingDouble(i -> Math.abs(ports[i][0][0] - ports[i][1][0])
        + Math.abs(ports[i][0][1] - ports[i][1][1])));
    for (int i : single) {
      routes[i] = grid.route(ports[i][0], sides[i][0], ports[i][1], sides[i][1], ++id);
    }
    return Arrays.asList(routes);
  }

  /** A reference to itself: a small loop out of the right side and back. */
  private static Route loop(double[] r) {
    double x = r[0] + r[2];
    double top = r[1] + Math.min(12, r[3] / 4);
    double bottom = r[1] + Math.min(36, r[3] * 3 / 4);
    return new Route(List.of(new double[] {x, top}, new double[] {x + 24, top},
        new double[] {x + 24, bottom}, new double[] {x, bottom}));
  }

  private static boolean overlap(double[] a, double[] b) {
    return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
  }

  /**
   * Above or below: top and bottom. Side by side with nothing in between: left
   * and right. Side by side with boxes in between: around underneath.
   */
  private static Side[] sides(Link link, double[] a, double[] b, Map<String, double[]> boxes) {
    if (b[1] + b[3] + 2 * MARGIN <= a[1]) return new Side[] {Side.TOP, Side.BOTTOM};
    if (a[1] + a[3] + 2 * MARGIN <= b[1]) return new Side[] {Side.BOTTOM, Side.TOP};
    boolean right = b[0] + b[2] / 2 > a[0] + a[2] / 2;
    double[] left = right ? a : b;
    double[] other = right ? b : a;
    double x1 = left[0] + left[2];
    double x2 = other[0];
    double y1 = Math.min(a[1], b[1]);
    double y2 = Math.max(a[1] + a[3], b[1] + b[3]);
    boolean free = x2 - x1 >= 2 * MARGIN + STEP;
    for (var e : boxes.entrySet()) {
      if (!free) break;
      if (e.getKey().equals(link.from()) || e.getKey().equals(link.to())) continue;
      double[] r = e.getValue();
      if (r[0] < x2 && r[0] + r[2] > x1 && r[1] < y2 && r[1] + r[3] > y1) free = false;
    }
    if (free) {
      return right ? new Side[] {Side.RIGHT, Side.LEFT} : new Side[] {Side.LEFT, Side.RIGHT};
    }
    return new Side[] {Side.BOTTOM, Side.BOTTOM};
  }

  /**
   * Where each link leaves and enters: the ends on one side of a box are spread
   * along it, ordered by where the other end is. The links of one bundle into a
   * box share a single port in the middle of the side.
   */
  private static double[][][] ports(Map<String, double[]> boxes, List<Link> links,
      Side[][] sides) {
    double[][][] ports = new double[links.size()][][];
    boolean[][] alone = new boolean[links.size()][2];
    // per box and side: the slots, each one or more link ends
    Map<String, Map<String, List<int[]>>> slots = new LinkedHashMap<>();
    for (int i = 0; i < links.size(); i++) {
      if (sides[i] == null) continue;
      ports[i] = new double[2][];
      Link l = links.get(i);
      for (int end = 0; end < 2; end++) {
        String box = end == 0 ? l.from() : l.to();
        String side = box + "|" + sides[i][end];
        String slot = end == 1 && l.bundle() != null ? "bundle:" + l.bundle() : "link:" + i;
        slots.computeIfAbsent(side, k -> new LinkedHashMap<>())
            .computeIfAbsent(slot, k -> new ArrayList<>()).add(new int[] {i, end});
      }
    }
    for (var entry : slots.entrySet()) {
      String box = entry.getKey().substring(0, entry.getKey().lastIndexOf('|'));
      Side side = Side.valueOf(entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1));
      double[] r = boxes.get(box);
      List<List<int[]>> ordered = new ArrayList<>(entry.getValue().values());
      // along the side by where the other end is; a bundle (parent) in the middle
      ordered.sort(Comparator.comparingDouble((List<int[]> s) -> {
        if (s.size() > 1 || links.get(s.get(0)[0]).bundle() != null && s.get(0)[1] == 1) {
          return side.horizontal() ? r[0] + r[2] / 2 : r[1] + r[3] / 2;
        }
        Link l = links.get(s.get(0)[0]);
        double[] o = boxes.get(s.get(0)[1] == 0 ? l.to() : l.from());
        return side.horizontal() ? o[0] + o[2] / 2 : o[1] + o[3] / 2;
      }).thenComparingInt(s -> s.get(0)[0]));
      double start = side.horizontal() ? r[0] : r[1];
      double length = side.horizontal() ? r[2] : r[3];
      int n = ordered.size();
      if (n == 1 && ordered.get(0).size() == 1) {
        alone[ordered.get(0).get(0)[0]][ordered.get(0).get(0)[1]] = true;
      }
      double spacing = Math.min(length / (n + 1), 3 * STEP);
      double first = start + length / 2 - spacing * (n - 1) / 2;
      for (int k = 0; k < n; k++) {
        double along = first + spacing * k;
        double[] p = switch (side) {
          case TOP -> new double[] {along, r[1]};
          case BOTTOM -> new double[] {along, r[1] + r[3]};
          case LEFT -> new double[] {r[0], along};
          case RIGHT -> new double[] {r[0] + r[2], along};
        };
        for (int[] end : ordered.get(k)) ports[end[0]][end[1]] = p;
      }
    }
    // a link alone on its side moves its port across from the other end: a straight line
    for (int i = 0; i < links.size(); i++) {
      if (sides[i] == null || sides[i][0] == sides[i][1]) continue;
      boolean along = sides[i][0].horizontal(); // ports lined up on x, else on y
      int axis = along ? 0 : 1;
      double[][] range = new double[2][];
      for (int end = 0; end < 2; end++) {
        double[] r = boxes.get(end == 0 ? links.get(i).from() : links.get(i).to());
        double start = along ? r[0] : r[1];
        double length = along ? r[2] : r[3];
        double inset = Math.min(MARGIN, length / 4);
        range[end] = new double[] {start + inset, start + length - inset};
      }
      double lo = Math.max(range[0][0], range[1][0]);
      double hi = Math.min(range[0][1], range[1][1]);
      double v;
      if (alone[i][0] && alone[i][1] && lo <= hi) {
        v = Math.max(lo, Math.min(hi, (ports[i][0][axis] + ports[i][1][axis]) / 2));
      } else if (alone[i][0] && within(ports[i][1][axis], range[0])) {
        v = ports[i][1][axis];
      } else if (alone[i][1] && within(ports[i][0][axis], range[1])) {
        v = ports[i][0][axis];
      } else {
        continue;
      }
      for (int end = 0; end < 2; end++) {
        ports[i][end] = ports[i][end].clone();
        ports[i][end][axis] = v;
      }
    }
    return ports;
  }

  private static boolean within(double v, double[] range) {
    return v >= range[0] && v <= range[1];
  }

  /**
   * Where a label of the given size goes: beside the route near its arrow head,
   * clear of the boxes and the labels placed so far (added to {@code taken}),
   * or where it covers the least of them. Returns the top-left corner.
   */
  static double[] placeLabel(Route route, double w, double h, Iterable<double[]> boxes,
      List<double[]> taken) {
    List<double[]> pts = route.points();
    double[] best = null;
    double bestCovered = Double.MAX_VALUE;
    // walk back from the arrow head along the route
    search:
    for (int s = pts.size() - 1; s > 0; s--) {
      double[] p = pts.get(s);
      double[] q = pts.get(s - 1);
      double len = Math.hypot(p[0] - q[0], p[1] - q[1]);
      boolean vertical = Math.abs(p[0] - q[0]) < 0.5;
      double from = s == pts.size() - 1 ? Math.min(16, len / 2) : Math.min(6, len / 2);
      for (double t = from; t <= Math.max(from, len - 6); t += 12) {
        double x = p[0] + (q[0] - p[0]) * t / len;
        double y = p[1] + (q[1] - p[1]) * t / len;
        double[][] candidates = vertical
            ? new double[][] {{x + 5, y - h / 2}, {x - 5 - w, y - h / 2}}
            : new double[][] {{x - w / 2, y - h - 2}, {x - w / 2, y + 2}};
        for (double[] c : candidates) {
          double covered = covered(c, w, h, boxes, taken);
          if (covered < bestCovered) {
            bestCovered = covered;
            best = c;
          }
          if (covered == 0) break search;
        }
      }
    }
    if (best == null) {
      double[] p = route.end();
      best = new double[] {p[0] + 5, p[1] - h};
    }
    taken.add(new double[] {best[0], best[1], w, h});
    return best;
  }

  /** How much of the boxes and other labels a label at {@code c} would cover. */
  private static double covered(double[] c, double w, double h, Iterable<double[]> boxes,
      List<double[]> taken) {
    double[] r = {c[0] - 2, c[1] - 1, w + 4, h + 2};
    double covered = 0;
    for (double[] b : boxes) covered += intersection(r, b);
    for (double[] b : taken) covered += 2 * intersection(r, b);
    return covered;
  }

  private static double intersection(double[] a, double[] b) {
    double w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
    double h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
    return w > 0 && h > 0 ? w * h : 0;
  }

  /** The lanes: free grid lines between the boxes, plus one line through every port. */
  private static final class Grid {
    final double[] xs;
    final double[] ys;
    final int nx;
    final int ny;
    final boolean[] blocked;
    /** Per node: how far off the middle of the free space across a horizontal / vertical lane. */
    final float[] offH;
    final float[] offV;
    /** Bundle using the lane from a node to its right / lower neighbour; 0 free, -1 several. */
    final int[] usedH;
    final int[] usedV;
    /** The searches' bookkeeping per node and direction, valid where {@code seen} is current. */
    private final double[] cost;
    private final int[] previous;
    private final int[] seen;
    private int search;

    private Grid(double[] xs, double[] ys, List<double[]> boxes) {
      this.xs = xs;
      this.ys = ys;
      nx = xs.length;
      ny = ys.length;
      blocked = new boolean[nx * ny];
      for (double[] r : boxes) {
        int i0 = above(xs, r[0] - MARGIN);
        int j0 = above(ys, r[1] - MARGIN);
        for (int j = j0; j < ny && ys[j] < r[1] + r[3] + MARGIN; j++) {
          for (int i = i0; i < nx && xs[i] < r[0] + r[2] + MARGIN; i++) {
            blocked[j * nx + i] = true;
          }
        }
      }
      offH = new float[nx * ny];
      offV = new float[nx * ny];
      // for a horizontal lane: the free space above and below it in its column
      for (int i = 0; i < nx; i++) {
        int j = 0;
        while (j < ny) {
          if (blocked[j * nx + i]) {
            j++;
            continue;
          }
          int end = j;
          while (end + 1 < ny && !blocked[(end + 1) * nx + i]) end++;
          for (int k = j; k <= end; k++) offH[k * nx + i] = off(ys[k], ys[j], ys[end]);
          j = end + 1;
        }
      }
      for (int j = 0; j < ny; j++) {
        int i = 0;
        while (i < nx) {
          if (blocked[j * nx + i]) {
            i++;
            continue;
          }
          int end = i;
          while (end + 1 < nx && !blocked[j * nx + end + 1]) end++;
          for (int k = i; k <= end; k++) offV[j * nx + k] = off(xs[k], xs[i], xs[end]);
          i = end + 1;
        }
      }
      usedH = new int[nx * ny];
      usedV = new int[nx * ny];
      cost = new double[nx * ny * 4];
      previous = new int[nx * ny * 4];
      seen = new int[nx * ny * 4];
    }

    private static float off(double v, double from, double to) {
      double half = (to - from) / 2;
      return half <= 0 ? 0 : (float) (Math.abs(v - (from + half)) / half);
    }

    static Grid of(Map<String, double[]> boxes, double[][][] ports) {
      if (boxes.isEmpty()) return null;
      double minX = Double.MAX_VALUE;
      double minY = Double.MAX_VALUE;
      double maxX = -Double.MAX_VALUE;
      double maxY = -Double.MAX_VALUE;
      for (double[] r : boxes.values()) {
        minX = Math.min(minX, r[0]);
        minY = Math.min(minY, r[1]);
        maxX = Math.max(maxX, r[0] + r[2]);
        maxY = Math.max(maxY, r[1] + r[3]);
      }
      double pad = MARGIN + 3 * STEP;
      // nothing left of or above the canvas
      minX = Math.max(minX - pad, 0);
      minY = Math.max(minY - pad, 0);
      maxX += pad;
      maxY += pad;
      // the lines along the boxes' margins and through the ports are exact,
      // the others fill the space between every STEP
      TreeSet<Double> fixedX = new TreeSet<>();
      TreeSet<Double> fixedY = new TreeSet<>();
      for (double[] r : boxes.values()) {
        fixedX.add(r[0] - MARGIN);
        fixedX.add(r[0] + r[2] + MARGIN);
        fixedY.add(r[1] - MARGIN);
        fixedY.add(r[1] + r[3] + MARGIN);
      }
      for (double[][] link : ports) {
        if (link == null) continue;
        for (double[] p : link) {
          fixedX.add(p[0]);
          fixedY.add(p[1]);
        }
      }
      double[] xs = lines(fixedX, minX, maxX);
      double[] ys = lines(fixedY, minY, maxY);
      if ((long) xs.length * ys.length > MAX_NODES) return null;
      return new Grid(xs, ys, List.copyOf(boxes.values()));
    }

    private static double[] lines(TreeSet<Double> fixed, double min, double max) {
      TreeSet<Double> all = new TreeSet<>(fixed);
      for (double v = Math.floor(min / STEP) * STEP; v <= max; v += STEP) {
        Double below = fixed.floor(v);
        Double above = fixed.ceiling(v);
        if ((below == null || v - below >= STEP / 2) && (above == null || above - v >= STEP / 2)) {
          all.add(v);
        }
      }
      return all.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** The index of the first value above {@code v}. */
    private static int above(double[] values, double v) {
      int i = Arrays.binarySearch(values, v);
      return i >= 0 ? i + 1 : -i - 1;
    }

    /** The index of the value nearest to {@code v}. */
    private static int index(double[] values, double v) {
      int i = Arrays.binarySearch(values, v);
      if (i >= 0) return i;
      i = -i - 1;
      if (i >= values.length) return values.length - 1;
      return i > 0 && v - values[i - 1] < values[i] - v ? i - 1 : i;
    }

    /** The first node outside the margin, straight out from a port. */
    private int stub(double[] port, Side side) {
      int i = index(xs, port[0]);
      int j = index(ys, port[1]);
      while (true) {
        int ni = i + side.dx;
        int nj = j + side.dy;
        if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) break;
        i = ni;
        j = nj;
        double out = side.horizontal() ? Math.abs(ys[j] - port[1]) : Math.abs(xs[i] - port[0]);
        if (out >= MARGIN - 0.5) break;
      }
      return j * nx + i;
    }

    // directions: 0 right, 1 left, 2 down, 3 up
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DY = {0, 0, 1, -1};

    private static int dir(Side outward) {
      return switch (outward) {
        case RIGHT -> 0;
        case LEFT -> 1;
        case BOTTOM -> 2;
        case TOP -> 3;
      };
    }

    /**
     * The links from several ports on the same side of their boxes into one
     * port, as UML draws a parent's subclasses: straight from each subclass to a
     * common lane, along it, and straight on into the parent. The lane is the
     * free one nearest the middle between them. Null when no lane is free.
     */
    Route[] tree(List<double[]> from, Side fromSide, double[] to, int bundle) {
      Side toSide = fromSide == Side.TOP ? Side.BOTTOM : Side.TOP;
      int parent = stub(to, toSide);
      int pc = parent % nx;
      int pr = parent / nx;
      int n = from.size();
      int[] cc = new int[n];
      int[] cr = new int[n];
      int left = pc;
      int right = pc;
      int lo = 0;
      int hi = ny - 1;
      for (int k = 0; k < n; k++) {
        int s = stub(from.get(k), fromSide);
        cc[k] = s % nx;
        cr[k] = s / nx;
        left = Math.min(left, cc[k]);
        right = Math.max(right, cc[k]);
        // strictly between the parent and each subclass
        if (fromSide == Side.TOP) {
          lo = Math.max(lo, pr + 1);
          hi = Math.min(hi, cr[k] - 1);
        } else {
          lo = Math.max(lo, cr[k] + 1);
          hi = Math.min(hi, pr - 1);
        }
      }
      if (lo > hi) return null;
      double middle = (ys[lo] + ys[hi]) / 2;
      int best = -1;
      double bestScore = Double.MAX_VALUE;
      for (int j = lo; j <= hi; j++) {
        int crossings = row(j, left, right, bundle);
        for (int k = 0; k < n && crossings >= 0; k++) {
          int c = column(cc[k], j, cr[k], bundle);
          crossings = c < 0 ? -1 : crossings + c;
        }
        int c = column(pc, pr, j, bundle);
        crossings = c < 0 || crossings < 0 ? -1 : crossings + c;
        if (crossings < 0) continue;
        double score = Math.abs(ys[j] - middle) + crossings * CROSS;
        for (int dj = -1; dj <= 1; dj += 2) {
          int other = j + dj;
          if (other < 0 || other >= ny || Math.abs(ys[other] - ys[j]) >= 1.5 * STEP) continue;
          for (int i = left; i < right; i++) {
            if (other(usedH, other * nx + i, bundle)) {
              score += 2 * STEP;
              break;
            }
          }
        }
        if (score < bestScore) {
          bestScore = score;
          best = j;
        }
      }
      if (best < 0) return null;
      for (int i = left; i < right; i++) mark(usedH, best * nx + i, bundle);
      for (int k = 0; k <= n; k++) {
        int col = k < n ? cc[k] : pc;
        int end = k < n ? cr[k] : pr;
        for (int j = Math.min(best, end); j < Math.max(best, end); j++) {
          mark(usedV, j * nx + col, bundle);
        }
      }
      Route[] routes = new Route[n];
      for (int k = 0; k < n; k++) {
        double[] p = from.get(k);
        routes[k] = new Route(simplify(List.of(p, new double[] {p[0], ys[best]},
            new double[] {to[0], ys[best]}, to)));
      }
      return routes;
    }

    /** Crossings along a lane of row {@code j} from column {@code i1} to {@code i2}; -1 blocked. */
    private int row(int j, int i1, int i2, int bundle) {
      int crossings = 0;
      for (int i = i1; i <= i2; i++) {
        int node = j * nx + i;
        if (blocked[node] || i < i2 && other(usedH, node, bundle)) return -1;
        if (other(usedV, node, bundle) || j > 0 && other(usedV, node - nx, bundle)) crossings++;
      }
      return crossings;
    }

    /** Crossings along a lane of column {@code i} from row {@code j1} to {@code j2}; -1 blocked. */
    private int column(int i, int j1, int j2, int bundle) {
      int crossings = 0;
      for (int j = Math.min(j1, j2); j <= Math.max(j1, j2); j++) {
        int node = j * nx + i;
        if (blocked[node] || j < Math.max(j1, j2) && other(usedV, node, bundle)) return -1;
        if (other(usedH, node, bundle) || i > 0 && other(usedH, node - 1, bundle)) crossings++;
      }
      return crossings;
    }

    private static void mark(int[] used, int edge, int bundle) {
      used[edge] = used[edge] == 0 || used[edge] == bundle ? bundle : -1;
    }

    Route route(double[] from, Side fromSide, double[] to, Side toSide, int bundle) {
      int start = stub(from, fromSide);
      int goal = stub(to, toSide);
      int startDir = dir(fromSide);
      int goalDir = dir(toSide) ^ 1; // into the box
      int[] path = search(start, startDir, goal, goalDir, bundle);
      if (path == null) return null;
      for (int k = 0; k + 1 < path.length; k++) {
        int a = Math.min(path[k], path[k + 1]);
        int b = Math.max(path[k], path[k + 1]);
        mark(b - a == 1 ? usedH : usedV, a, bundle);
      }
      List<double[]> points = new ArrayList<>();
      points.add(from);
      for (int n : path) {
        double[] p = {xs[n % nx], ys[n / nx]};
        // the line through the port is exact; the stubs keep its coordinate
        if (n == start) p = fromSide.horizontal() ? new double[] {from[0], p[1]}
            : new double[] {p[0], from[1]};
        if (n == goal) p = toSide.horizontal() ? new double[] {to[0], p[1]}
            : new double[] {p[0], to[1]};
        points.add(p);
      }
      points.add(to);
      return new Route(simplify(points));
    }

    private int[] search(int start, int startDir, int goal, int goalDir, int bundle) {
      search++;
      Heap open = new Heap();
      double gx = xs[goal % nx];
      double gy = ys[goal / nx];
      int first = start * 4 + startDir;
      seen[first] = search;
      cost[first] = 0;
      previous[first] = -1;
      open.push(first, 0);
      while (!open.isEmpty()) {
        double f = open.topKey();
        int state = open.pop();
        int node = state / 4;
        int d = state % 4;
        double g = cost[state];
        if (f > g + Math.abs(xs[node % nx] - gx) + Math.abs(ys[node / nx] - gy) + 1e-6) {
          continue; // stale entry
        }
        if (node == goal) return path(previous, state);
        int i = node % nx;
        int j = node / nx;
        for (int nd = 0; nd < 4; nd++) {
          if (nd == (d ^ 1)) continue; // no turning back
          int ni = i + DX[nd];
          int nj = j + DY[nd];
          if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) continue;
          int next = nj * nx + ni;
          if (blocked[next] && next != goal) continue;
          double step = cost(node, next, nd, d, bundle);
          if (next == goal && nd != goalDir) step += 2 * BEND;
          int ns = next * 4 + nd;
          double ng = g + step;
          if (seen[ns] != search || ng < cost[ns]) {
            seen[ns] = search;
            cost[ns] = ng;
            previous[ns] = state;
            open.push(ns, ng + Math.abs(xs[ni] - gx) + Math.abs(ys[nj] - gy));
          }
        }
      }
      return null;
    }

    private double cost(int node, int next, int nd, int d, int bundle) {
      boolean horizontal = nd < 2;
      double len = horizontal ? Math.abs(xs[next % nx] - xs[node % nx])
          : Math.abs(ys[next / nx] - ys[node / nx]);
      int edge = Math.min(node, next);
      int[] used = horizontal ? usedH : usedV;
      double c;
      if (used[edge] == 0) {
        c = len;
      } else if (used[edge] == bundle) {
        c = len * SHARED;
      } else {
        c = len + OVERLAP;
      }
      if (nd != d) c += BEND;
      // keep to the middle of the free space
      c += len * OFF_CENTER * (horizontal ? offH[next] : offV[next]);
      // crossing a line of another edge
      int i = next % nx;
      int j = next / nx;
      if (horizontal) {
        if (other(usedV, next, bundle) || j > 0 && other(usedV, next - nx, bundle)) c += CROSS;
      } else {
        if (other(usedH, next, bundle) || i > 0 && other(usedH, next - 1, bundle)) c += CROSS;
      }
      // right next to another edge's line
      if (horizontal) {
        int j0 = node / nx;
        if (j0 > 0 && ys[j0] - ys[j0 - 1] < 1.5 * STEP && other(usedH, edge - nx, bundle)
            || j0 + 1 < ny && ys[j0 + 1] - ys[j0] < 1.5 * STEP && other(usedH, edge + nx, bundle)) {
          c += len * NEAR;
        }
      } else {
        int i0 = node % nx;
        if (i0 > 0 && xs[i0] - xs[i0 - 1] < 1.5 * STEP && other(usedV, edge - 1, bundle)
            || i0 + 1 < nx && xs[i0 + 1] - xs[i0] < 1.5 * STEP && other(usedV, edge + 1, bundle)) {
          c += len * NEAR;
        }
      }
      return c;
    }

    private static boolean other(int[] used, int edge, int bundle) {
      return edge >= 0 && edge < used.length && used[edge] != 0 && used[edge] != bundle;
    }

    private static int[] path(int[] previous, int state) {
      List<Integer> nodes = new ArrayList<>();
      for (int s = state; s != -1; s = previous[s]) nodes.add(s / 4);
      int[] path = new int[nodes.size()];
      for (int k = 0; k < path.length; k++) path[k] = nodes.get(path.length - 1 - k);
      return path;
    }
  }

  /** Drops points in the middle of a straight stretch. */
  static List<double[]> simplify(List<double[]> points) {
    List<double[]> out = new ArrayList<>();
    for (double[] p : points) {
      if (!out.isEmpty()) {
        double[] last = out.get(out.size() - 1);
        if (Math.abs(last[0] - p[0]) < 1e-6 && Math.abs(last[1] - p[1]) < 1e-6) continue;
      }
      if (out.size() >= 2) {
        double[] a = out.get(out.size() - 2);
        double[] b = out.get(out.size() - 1);
        boolean sameX = Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(b[0] - p[0]) < 1e-6;
        boolean sameY = Math.abs(a[1] - b[1]) < 1e-6 && Math.abs(b[1] - p[1]) < 1e-6;
        if (sameX || sameY) out.set(out.size() - 1, p);
        else out.add(p);
      } else {
        out.add(p);
      }
    }
    return out;
  }

  /** A binary min-heap of ints by a double key. */
  private static final class Heap {
    private int[] items = new int[256];
    private double[] keys = new double[256];
    private int size;

    boolean isEmpty() {
      return size == 0;
    }

    double topKey() {
      return keys[0];
    }

    void push(int item, double key) {
      if (size == items.length) {
        items = Arrays.copyOf(items, size * 2);
        keys = Arrays.copyOf(keys, size * 2);
      }
      int k = size++;
      while (k > 0) {
        int parent = (k - 1) / 2;
        if (keys[parent] <= key) break;
        items[k] = items[parent];
        keys[k] = keys[parent];
        k = parent;
      }
      items[k] = item;
      keys[k] = key;
    }

    int pop() {
      int top = items[0];
      int item = items[--size];
      double key = keys[size];
      int k = 0;
      while (true) {
        int child = 2 * k + 1;
        if (child >= size) break;
        if (child + 1 < size && keys[child + 1] < keys[child]) child++;
        if (keys[child] >= key) break;
        items[k] = items[child];
        keys[k] = keys[child];
        k = child;
      }
      items[k] = item;
      keys[k] = key;
      return top;
    }
  }
}
