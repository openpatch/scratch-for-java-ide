package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Draws UML boxes (classes or objects) in layers with their edges:
 * inheritance (hollow triangle at the parent) and associations or references
 * (open arrow at the target, the role as label). Layer 0 is at the top. The
 * boxes can be dragged; from the first drag on, every box stays where it is
 * until {@link #arrangeAutomatically()}.
 */
final class UmlCanvas extends Canvas {

  enum EdgeKind { INHERITANCE, ASSOCIATION }
  enum BoxKind { CLASS, OBJECT }

  /** A box: title (italic for abstract, underlined for objects), then sections of lines. */
  record Box(String id, BoxKind kind, String title, boolean italic, boolean underline, boolean faded,
      List<List<String>> sections, int layer) {}

  record Edge(String from, String to, EdgeKind kind, String label) {}

  private static final double PAD = 8;
  private static final double LINE = 17;
  private static final double GAP_X = 48;
  private static final double GAP_Y = 110;
  /** Between the lines of one wrapped layer of objects. */
  private static final double GAP_WRAP = 44;
  private final Font font = Font.font("System", 12.5);
  private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
  private final Font italicTitle = Font.font("System", FontWeight.BOLD, FontPosture.ITALIC, 13);

  private List<Box> boxes = List.of();
  private List<Edge> edges = List.of();
  private final Map<String, double[]> placed = new LinkedHashMap<>();
  private double zoom = 1;
  /** Visible width in screen pixels; a layer of objects wider than this wraps (0: never). */
  private double wrapWidth;
  /** The route of each edge (same order as {@link #edges}); null where it is drawn straight. */
  private List<DiagramRouter.Route> routes = List.of();
  private Consumer<String> onOpen = id -> { };
  /** Top-left corners the boxes were dragged to, by id; they win over the layout. */
  private final Map<String, double[]> pinned = new LinkedHashMap<>();
  private Consumer<Map<String, double[]>> onArranged = positions -> { };
  /** The box being dragged, and where in it the mouse holds it. */
  private String dragging;
  private double[] grip;
  private boolean dragged;
  private boolean redrawQueued;

  void setOnOpen(Consumer<String> action) {
    onOpen = action;
  }

  /** Told the positions of all boxes after a drag, or none after arranging automatically. */
  void setOnArranged(Consumer<Map<String, double[]>> action) {
    onArranged = action;
  }

  UmlCanvas() {
    setOnMouseClicked(e -> {
      if (e.getClickCount() != 2 || !e.isStillSincePress()) return;
      String id = boxAt(e.getX() / zoom, e.getY() / zoom);
      if (id != null) onOpen.accept(id);
    });
    // a box follows the mouse; the scroll pane pans only from the free space around them
    setOnMousePressed(e -> {
      dragging = e.getButton() == MouseButton.PRIMARY ? boxAt(e.getX() / zoom, e.getY() / zoom)
          : null;
      dragged = false;
      requestFocus(); // for Ctrl+Z
      if (dragging == null) return;

      double[] r = placed.get(dragging);
      grip = new double[] {e.getX() / zoom - r[0], e.getY() / zoom - r[1]};
      setCursor(Cursor.CLOSED_HAND);
      e.consume();
    });
    setOnMouseDragged(e -> {
      if (dragging == null) return;
      if (!dragged) {
        dragged = true;
        // from now on the student arranges: every box keeps its place
        for (var entry : placed.entrySet()) {
          pinned.putIfAbsent(entry.getKey(), new double[] {entry.getValue()[0],
              entry.getValue()[1]});
        }
      }
      double[] r = placed.get(dragging);
      if (r == null) {
        dragging = null; // the object is gone (live object diagram)
        return;
      }
      r[0] = Math.max(0, e.getX() / zoom - grip[0]);
      r[1] = Math.max(0, e.getY() / zoom - grip[1]);
      pinned.put(dragging, new double[] {r[0], r[1]});
      // routing a large diagram takes a moment: once for all the moves that came meanwhile
      if (!redrawQueued) {
        redrawQueued = true;
        Platform.runLater(() -> {
          redrawQueued = false;
          route();
          draw();
        });
      }
      e.consume();
    });
    setOnMouseReleased(e -> {
      if (dragging == null) return;
      dragging = null;
      setCursor(boxAt(e.getX() / zoom, e.getY() / zoom) != null ? Cursor.OPEN_HAND : null);
      if (dragged) {
        route();
        draw();
        onArranged.accept(pinned());
      }
      e.consume();
    });
    setOnMouseMoved(e -> setCursor(boxAt(e.getX() / zoom, e.getY() / zoom) != null
        ? Cursor.OPEN_HAND : null));
  }

  /** Where the boxes were dragged to (top-left corners by id), copied. */
  Map<String, double[]> pinned() {
    Map<String, double[]> copy = new LinkedHashMap<>();
    pinned.forEach((id, at) -> copy.put(id, at.clone()));
    return copy;
  }

  /** Places the boxes as they were dragged before; used by the next {@link #show}. */
  void setPinned(Map<String, double[]> positions) {
    pinned.clear();
    positions.forEach((id, at) -> pinned.put(id, at.clone()));
  }

  boolean arrangedByHand() {
    return !pinned.isEmpty();
  }

  /** Puts the boxes where {@code positions} has them (undo, redo); none: laid out by itself. */
  void place(Map<String, double[]> positions) {
    setPinned(positions);
    layout();
    draw();
  }

  /** Forgets where the boxes were dragged and lays them out again. */
  void arrangeAutomatically() {
    pinned.clear();
    layout();
    draw();
    onArranged.accept(Map.of());
  }

  void show(List<Box> boxes, List<Edge> edges) {
    this.boxes = boxes;
    this.edges = edges;
    layout();
    draw();
  }

  void setZoom(double next) {
    zoom = Math.max(0.3, Math.min(3, next));
    if (wrapWidth > 0) layout(); // more or fewer objects fit on a line
    draw();
  }

  /** The width the diagram may use before a layer of objects wraps onto the next line. */
  void setWrapWidth(double width) {
    if (Math.abs(width - wrapWidth) < 1) return;
    wrapWidth = width;
    if (boxes.stream().anyMatch(b -> b.kind() == BoxKind.OBJECT)) {
      layout();
      draw();
    }
  }

  double zoom() {
    return zoom;
  }

  /** Box positions (x, y, w, h) by id, for tests. */
  Map<String, double[]> placed() {
    return placed;
  }

  /** The box at a point of the diagram; of boxes on top of each other the one drawn last. */
  String boxAt(double x, double y) {
    String found = null;
    for (Box b : boxes) {
      double[] r = placed.get(b.id());
      if (r != null && x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
        found = b.id();
      }
    }
    return found;
  }

  private double width(String text, Font f) {
    Text t = new Text(text);
    t.setFont(f);
    return t.getLayoutBounds().getWidth();
  }

  /** Layers top to bottom; inside a layer, boxes under their parent (barycenter). */
  private void layout() {
    placed.clear();
    Map<String, double[]> size = new HashMap<>();
    for (Box b : boxes) {
      double w = width(b.title(), b.italic() ? italicTitle : titleFont) + 2 * PAD;
      int lines = 1;
      for (List<String> section : b.sections()) {
        for (String line : section) w = Math.max(w, width(line, font) + 2 * PAD);
        lines += Math.max(section.size(), 0);
      }
      double h = PAD * 2 + LINE + b.sections().size() * (PAD / 2)
          + (lines - 1) * LINE + (b.sections().isEmpty() ? 0 : PAD / 2);
      size.put(b.id(), new double[] {Math.max(w, 90), h});
    }
    int maxLayer = boxes.stream().mapToInt(Box::layer).max().orElse(0);
    Map<String, String> parentOf = new HashMap<>();
    for (Edge e : edges) {
      if (e.kind() == EdgeKind.INHERITANCE) parentOf.put(e.from(), e.to());
    }
    for (Edge e : edges) {
      // objects: the first reference to an object decides where it hangs
      if (e.kind() == EdgeKind.ASSOCIATION) parentOf.putIfAbsent(e.to(), e.from());
    }
    // objects that reference others (the stage) come before the ones that do not (walls)
    java.util.Set<String> referencing = new java.util.HashSet<>();
    // a box referencing itself keeps room on its right for the loop and its label
    Map<String, Double> loop = new HashMap<>();
    for (Edge e : edges) {
      if (e.kind() == EdgeKind.ASSOCIATION && !e.from().equals(e.to())) referencing.add(e.from());
      if (e.from().equals(e.to())) {
        loop.merge(e.from(), width(e.label() == null ? "" : e.label(), font) + 36, Math::max);
      }
    }
    List<List<Box>> rows = new ArrayList<>();
    double y = 20;
    for (int layer = 0; layer <= maxLayer; layer++) {
      List<Box> row = new ArrayList<>();
      for (Box b : boxes) if (b.layer() == layer) row.add(b);
      int current = layer;
      row.sort((a, b) -> {
        if (a.faded() != b.faded()) return a.faded() ? -1 : 1;
        double pa = parentX(parentOf.get(a.id()));
        double pb = parentX(parentOf.get(b.id()));
        if (pa != pb) return Double.compare(pa, pb);
        boolean ra = a.kind() == BoxKind.OBJECT && referencing.contains(a.id());
        boolean rb = b.kind() == BoxKind.OBJECT && referencing.contains(b.id());
        return ra != rb ? (ra ? -1 : 1) : a.title().compareTo(b.title());
      });
      double rowHeight = 0;
      // objects wrap at the visible width; classes keep one line under their parents
      double limit = wrapWidth > 0 && row.stream().anyMatch(b -> b.kind() == BoxKind.OBJECT)
          ? Math.max(wrapWidth / zoom - 20, 200) : Double.MAX_VALUE;
      String previous = null;
      for (Box b : row) {
        double[] s = size.get(b.id());
        double x = previous == null ? 20 : right(previous) + gap(previous, b.id(), loop);
        // under the parent when there is room
        if (parentOf.containsKey(b.id()) && placed.containsKey(parentOf.get(b.id()))) {
          x = Math.max(x, parentX(parentOf.get(b.id())) - s[0] / 2);
        }
        if (x + s[0] > limit && x > 20) {
          y += rowHeight + GAP_WRAP;
          x = 20;
          rowHeight = 0;
        }
        placed.put(b.id(), new double[] {x, y, s[0], s[1]});
        previous = b.id();
        rowHeight = Math.max(rowHeight, s[1]);
      }
      if (!row.isEmpty() || current == 0) y += rowHeight + GAP_Y;
      rows.add(row);
    }
    if (boxes.stream().allMatch(b -> b.kind() == BoxKind.CLASS)) {
      centerParents(rows, parentOf, loop);
    }
    pin();
    route();
  }

  /**
   * The boxes the student dragged go back where they were put. Boxes that came
   * since (a new class) keep their arrangement among themselves, below.
   */
  private void pin() {
    if (pinned.isEmpty()) return;
    double bottom = 0;
    double top = Double.MAX_VALUE;
    List<double[]> loose = new ArrayList<>();
    for (var entry : placed.entrySet()) {
      double[] r = entry.getValue();
      double[] at = pinned.get(entry.getKey());
      if (at != null) {
        r[0] = at[0];
        r[1] = at[1];
        bottom = Math.max(bottom, r[1] + r[3]);
      } else {
        loose.add(r);
        top = Math.min(top, r[1]);
      }
    }
    if (loose.isEmpty() || bottom == 0) return;
    for (double[] r : loose) r[1] += bottom + GAP_Y - top;
  }

  /**
   * From the bottom up, every class moves over the middle of the classes that
   * hang below it (its subclasses, or the classes it holds), so the lines go
   * straight down instead of across the whole diagram. The gaps between the
   * boxes of a layer stay.
   */
  private void centerParents(List<List<Box>> rows, Map<String, String> parentOf,
      Map<String, Double> loop) {
    Map<String, Integer> layerOf = new HashMap<>();
    for (Box b : boxes) layerOf.put(b.id(), b.layer());
    for (int layer = rows.size() - 2; layer >= 0; layer--) {
      List<Box> row = new ArrayList<>(rows.get(layer));
      // the middles of the boxes below (moved already) hanging from each box
      Map<String, double[]> span = new HashMap<>();
      for (var entry : parentOf.entrySet()) {
        double[] child = placed.get(entry.getKey());
        Integer above = layerOf.get(entry.getValue());
        if (child == null || above == null || layerOf.get(entry.getKey()) <= above) continue;
        double center = child[0] + child[2] / 2;
        span.merge(entry.getValue(), new double[] {center, center},
            (a, b) -> new double[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
      }
      Map<String, Double> wanted = new HashMap<>();
      for (Box b : row) {
        double[] r = placed.get(b.id());
        double[] children = span.get(b.id());
        wanted.put(b.id(), children == null ? r[0] + r[2] / 2 : (children[0] + children[1]) / 2);
      }
      row.sort(java.util.Comparator.comparingDouble(b -> wanted.get(b.id())));
      String previous = null;
      for (Box b : row) {
        double[] r = placed.get(b.id());
        double x = previous == null ? 20 : right(previous) + gap(previous, b.id(), loop);
        r[0] = Math.max(x, wanted.get(b.id()) - r[2] / 2);
        previous = b.id();
      }
    }
  }

  private double right(String id) {
    double[] r = placed.get(id);
    return r[0] + r[2];
  }

  /** Room between neighbours: more for a loop, or for the name on an arrow between them. */
  private double gap(String left, String right, Map<String, Double> loop) {
    double gap = GAP_X + loop.getOrDefault(left, 0.0);
    for (Edge e : edges) {
      boolean between = e.from().equals(left) && e.to().equals(right)
          || e.from().equals(right) && e.to().equals(left);
      if (between && e.kind() == EdgeKind.ASSOCIATION && e.label() != null) {
        gap = Math.max(gap, width(e.label(), font) + 2 * DiagramRouter.MARGIN + 16);
      }
    }
    return gap;
  }

  /** Lines around the boxes, each on its own lane; inheritance lines to one parent merge. */
  private void route() {
    List<DiagramRouter.Link> links = new ArrayList<>();
    for (Edge e : edges) {
      links.add(new DiagramRouter.Link(e.from(), e.to(),
          e.kind() == EdgeKind.INHERITANCE ? "extends" : null));
    }
    routes = DiagramRouter.route(placed, links);
  }

  private double parentX(String id) {
    double[] r = id == null ? null : placed.get(id);
    return r == null ? Double.MAX_VALUE / 2 : r[0] + r[2] / 2;
  }

  void draw() {
    double w = 40;
    double h = 40;
    for (double[] r : placed.values()) {
      w = Math.max(w, r[0] + r[2] + 40);
      h = Math.max(h, r[1] + r[3] + 40);
    }
    for (DiagramRouter.Route route : routes) {
      if (route == null) continue;
      for (double[] p : route.points()) {
        w = Math.max(w, p[0] + 40);
        h = Math.max(h, p[1] + 40);
      }
    }
    setWidth(w * zoom);
    setHeight(h * zoom);
    GraphicsContext g = getGraphicsContext2D();
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, getWidth(), getHeight());
    g.setFill(Color.web("#fbfbfd"));
    g.fillRect(0, 0, getWidth(), getHeight());
    g.scale(zoom, zoom);
    drawRoutes(g);
    for (int i = 0; i < edges.size(); i++) drawEdge(g, edges.get(i), route(i));
    for (Box b : boxes) {
      if (!b.id().equals(dragging)) drawBox(g, b);
    }
    // the box in the hand on top
    for (Box b : boxes) {
      if (b.id().equals(dragging)) drawBox(g, b);
    }
    // the role names last, beside their arrow heads and clear of the boxes
    List<double[]> taken = new ArrayList<>();
    for (int i = 0; i < edges.size(); i++) {
      Edge e = edges.get(i);
      DiagramRouter.Route route = route(i);
      if (route == null || e.label() == null || e.label().isEmpty()) continue;
      double[] at = DiagramRouter.placeLabel(route, width(e.label(), font), 15, placed.values(),
          taken);
      label(g, e.label(), at[0], at[1]);
    }
  }

  /**
   * The routed lines, every stretch once: the subclasses of one parent share
   * their last stretch, which would look darker drawn over itself.
   */
  private void drawRoutes(GraphicsContext g) {
    Map<String, List<double[]>> runs = new LinkedHashMap<>();
    for (DiagramRouter.Route route : routes) {
      if (route == null) continue;
      List<double[]> pts = route.points();
      for (int k = 0; k + 1 < pts.size(); k++) {
        double[] p = pts.get(k);
        double[] q = pts.get(k + 1);
        boolean horizontal = Math.abs(p[1] - q[1]) < 0.01;
        String key = horizontal ? "h" + Math.round(p[1] * 100) : "v" + Math.round(p[0] * 100);
        int along = horizontal ? 0 : 1;
        runs.computeIfAbsent(key, k2 -> new ArrayList<>()).add(new double[] {
            Math.min(p[along], q[along]), Math.max(p[along], q[along]), horizontal ? p[1] : p[0]});
      }
    }
    g.setStroke(Color.web("#5b5f6b"));
    g.setLineWidth(1.3);
    g.setLineCap(javafx.scene.shape.StrokeLineCap.SQUARE);
    for (var entry : runs.entrySet()) {
      boolean horizontal = entry.getKey().charAt(0) == 'h';
      List<double[]> parts = entry.getValue();
      parts.sort(java.util.Comparator.comparingDouble(r -> r[0]));
      double[] run = null;
      for (double[] part : parts) {
        if (run != null && part[0] <= run[1] + 0.01) {
          run[1] = Math.max(run[1], part[1]);
          continue;
        }
        if (run != null) strokeRun(g, run, horizontal);
        run = part.clone();
      }
      if (run != null) strokeRun(g, run, horizontal);
    }
    g.setLineCap(javafx.scene.shape.StrokeLineCap.BUTT);
  }

  private static void strokeRun(GraphicsContext g, double[] run, boolean horizontal) {
    if (horizontal) {
      g.strokeLine(run[0], run[2], run[1], run[2]);
    } else {
      g.strokeLine(run[2], run[0], run[2], run[1]);
    }
  }

  private DiagramRouter.Route route(int edge) {
    return edge < routes.size() ? routes.get(edge) : null;
  }

  private void drawBox(GraphicsContext g, Box b) {
    double[] r = placed.get(b.id());
    if (r == null) return;
    Color stroke = b.faded() ? Color.web("#a0a4ad") : Color.web("#3d2a6b");
    g.setFill(b.faded() ? Color.web("#f1f2f5") : Color.web("#f3efff"));
    if (b.kind() == BoxKind.OBJECT) {
      g.fillRoundRect(r[0], r[1], r[2], r[3], 12, 12);
    } else {
      g.fillRect(r[0], r[1], r[2], r[3]);
    }
    g.setStroke(stroke);
    g.setLineWidth(1.4);
    if (b.kind() == BoxKind.OBJECT) {
      g.strokeRoundRect(r[0], r[1], r[2], r[3], 12, 12);
    } else {
      g.strokeRect(r[0], r[1], r[2], r[3]);
    }
    g.setTextBaseline(VPos.TOP);
    g.setTextAlign(TextAlignment.CENTER);
    g.setFill(stroke);
    g.setFont(b.italic() ? italicTitle : titleFont);
    double y = r[1] + PAD;
    g.fillText(b.title(), r[0] + r[2] / 2, y);
    if (b.underline()) {
      double tw = width(b.title(), titleFont);
      g.strokeLine(r[0] + r[2] / 2 - tw / 2, y + 15, r[0] + r[2] / 2 + tw / 2, y + 15);
    }
    y += LINE + PAD / 2;
    g.setTextAlign(TextAlignment.LEFT);
    g.setFont(font);
    g.setFill(Color.web("#1f2430"));
    for (List<String> section : b.sections()) {
      g.setStroke(stroke);
      g.strokeLine(r[0], y, r[0] + r[2], y);
      y += PAD / 2;
      for (String line : section) {
        g.fillText(line, r[0] + PAD, y);
        y += LINE;
      }
    }
  }

  private void drawEdge(GraphicsContext g, Edge e, DiagramRouter.Route route) {
    double[] a = placed.get(e.from());
    double[] b = placed.get(e.to());
    if (a == null || b == null) return;
    g.setStroke(Color.web("#5b5f6b"));
    g.setFill(Color.web("#5b5f6b"));
    g.setLineWidth(1.3);
    double[] from;
    double[] to;
    if (route != null) {
      // the line itself is drawn with the others (drawRoutes)
      from = route.beforeEnd();
      to = route.end();
    } else {
      // no way around the boxes (they overlap): straight across
      from = border(a, b[0] + b[2] / 2, b[1] + b[3] / 2);
      to = border(b, a[0] + a[2] / 2, a[1] + a[3] / 2);
      g.strokeLine(from[0], from[1], to[0], to[1]);
      if (e.label() != null && !e.label().isEmpty()) {
        label(g, e.label(), (from[0] * 0.35 + to[0] * 0.65) + 4,
            (from[1] * 0.35 + to[1] * 0.65) - 16);
      }
    }
    if (e.kind() == EdgeKind.INHERITANCE) {
      triangle(g, from[0], from[1], to[0], to[1]);
    } else {
      arrow(g, from[0], from[1], to[0], to[1]);
    }
  }

  private void label(GraphicsContext g, String text, double x, double y) {
    g.setFont(font);
    g.setTextAlign(TextAlignment.LEFT);
    g.setTextBaseline(VPos.TOP);
    double w = width(text, font);
    g.setFill(Color.web("#fbfbfd", 0.9));
    g.fillRect(x - 2, y, w + 4, 15);
    g.setFill(Color.web("#3d2a6b"));
    g.fillText(text, x, y);
  }

  /** Where the line from the box centre towards (tx, ty) leaves the box. */
  private static double[] border(double[] r, double tx, double ty) {
    double cx = r[0] + r[2] / 2;
    double cy = r[1] + r[3] / 2;
    double dx = tx - cx;
    double dy = ty - cy;
    if (dx == 0 && dy == 0) return new double[] {cx, cy};
    double sx = dx == 0 ? Double.MAX_VALUE : (r[2] / 2) / Math.abs(dx);
    double sy = dy == 0 ? Double.MAX_VALUE : (r[3] / 2) / Math.abs(dy);
    double s = Math.min(sx, sy);
    return new double[] {cx + dx * s, cy + dy * s};
  }

  /** An open arrow head at (x2, y2), pointing away from (x1, y1). */
  private static void arrow(GraphicsContext g, double x1, double y1, double x2, double y2) {
    double angle = Math.atan2(y2 - y1, x2 - x1);
    double len = 11;
    double a1 = angle + Math.PI - 0.45;
    double a2 = angle + Math.PI + 0.45;
    g.strokeLine(x2, y2, x2 + len * Math.cos(a1), y2 + len * Math.sin(a1));
    g.strokeLine(x2, y2, x2 + len * Math.cos(a2), y2 + len * Math.sin(a2));
  }

  /** The hollow inheritance triangle with its tip at (x2, y2), pointing away from (x1, y1). */
  private static void triangle(GraphicsContext g, double x1, double y1, double x2, double y2) {
    double angle = Math.atan2(y2 - y1, x2 - x1);
    double back = 12;
    double half = 8;
    double bx = x2 - back * Math.cos(angle);
    double by = y2 - back * Math.sin(angle);
    double[] xs = {x2, bx - half * Math.sin(angle), bx + half * Math.sin(angle)};
    double[] ys = {y2, by + half * Math.cos(angle), by - half * Math.cos(angle)};
    g.setFill(Color.web("#fbfbfd"));
    g.fillPolygon(xs, ys, 3);
    g.strokePolygon(xs, ys, 3);
  }
}
