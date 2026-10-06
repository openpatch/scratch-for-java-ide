package org.openpatch.scratch4j.ui;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
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
 * (open arrow at the target, the role as label). Layer 0 is at the top.
 */
final class UmlCanvas extends Canvas {

  enum EdgeKind { INHERITANCE, ASSOCIATION }

  /** A box: title (italic for abstract, underlined for objects), then sections of lines. */
  record Box(String id, String title, boolean italic, boolean underline, boolean faded,
      List<List<String>> sections, int layer) {}

  record Edge(String from, String to, EdgeKind kind, String label) {}

  private static final double PAD = 8;
  private static final double LINE = 17;
  private static final double GAP_X = 48;
  private static final double GAP_Y = 110;
  private final Font font = Font.font("System", 12.5);
  private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
  private final Font italicTitle = Font.font("System", FontWeight.BOLD, FontPosture.ITALIC, 13);

  private List<Box> boxes = List.of();
  private List<Edge> edges = List.of();
  private final Map<String, double[]> placed = new LinkedHashMap<>();
  private double zoom = 1;
  /** Lanes already used below each row (y -> count) and per inheritance parent. */
  private final Map<Long, Integer> lanes = new HashMap<>();
  private final Map<String, Double> parentLanes = new HashMap<>();
  private Consumer<String> onOpen = id -> { };

  void setOnOpen(Consumer<String> action) {
    onOpen = action;
  }

  UmlCanvas() {
    setOnMouseClicked(e -> {
      if (e.getClickCount() != 2) return;
      String id = boxAt(e.getX() / zoom, e.getY() / zoom);
      if (id != null) onOpen.accept(id);
    });
  }

  void show(List<Box> boxes, List<Edge> edges) {
    this.boxes = boxes;
    this.edges = edges;
    layout();
    draw();
  }

  void setZoom(double next) {
    zoom = Math.max(0.3, Math.min(3, next));
    draw();
  }

  double zoom() {
    return zoom;
  }

  /** Box positions (x, y, w, h) by id, for tests. */
  Map<String, double[]> placed() {
    return placed;
  }

  String boxAt(double x, double y) {
    for (var entry : placed.entrySet()) {
      double[] r = entry.getValue();
      if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) return entry.getKey();
    }
    return null;
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
    double y = 20;
    for (int layer = 0; layer <= maxLayer; layer++) {
      List<Box> row = new ArrayList<>();
      for (Box b : boxes) if (b.layer() == layer) row.add(b);
      int current = layer;
      row.sort((a, b) -> {
        if (a.faded() != b.faded()) return a.faded() ? -1 : 1;
        double pa = parentX(parentOf.get(a.id()));
        double pb = parentX(parentOf.get(b.id()));
        return pa != pb ? Double.compare(pa, pb) : a.title().compareTo(b.title());
      });
      double x = 20;
      double rowHeight = 0;
      for (Box b : row) {
        double[] s = size.get(b.id());
        // under the parent when there is room
        double wanted = parentOf.containsKey(b.id()) && placed.containsKey(parentOf.get(b.id()))
            ? parentX(parentOf.get(b.id())) - s[0] / 2 : x;
        x = Math.max(x, wanted);
        placed.put(b.id(), new double[] {x, y, s[0], s[1]});
        x += s[0] + GAP_X;
        rowHeight = Math.max(rowHeight, s[1]);
      }
      if (!row.isEmpty() || current == 0) y += rowHeight + GAP_Y;
    }
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
    setWidth(w * zoom);
    setHeight(h * zoom);
    GraphicsContext g = getGraphicsContext2D();
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, getWidth(), getHeight());
    g.setFill(Color.web("#fbfbfd"));
    g.fillRect(0, 0, getWidth(), getHeight());
    g.scale(zoom, zoom);
    lanes.clear();
    parentLanes.clear();
    for (Edge e : edges) drawEdge(g, e);
    for (Box b : boxes) drawBox(g, b);
  }

  private void drawBox(GraphicsContext g, Box b) {
    double[] r = placed.get(b.id());
    if (r == null) return;
    Color stroke = b.faded() ? Color.web("#a0a4ad") : Color.web("#3d2a6b");
    g.setFill(b.faded() ? Color.web("#f1f2f5") : Color.web("#f3efff"));
    g.fillRect(r[0], r[1], r[2], r[3]);
    g.setStroke(stroke);
    g.setLineWidth(1.4);
    g.strokeRect(r[0], r[1], r[2], r[3]);
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

  private void drawEdge(GraphicsContext g, Edge e) {
    double[] a = placed.get(e.from());
    double[] b = placed.get(e.to());
    if (a == null || b == null) return;
    g.setStroke(Color.web("#5b5f6b"));
    g.setFill(Color.web("#5b5f6b"));
    g.setLineWidth(1.3);
    if (e.from().equals(e.to())) {
      // a reference to itself: a small loop on the right
      double x = a[0] + a[2];
      double y = a[1] + 12;
      g.strokeLine(x, y, x + 24, y);
      g.strokeLine(x + 24, y, x + 24, y + 24);
      g.strokeLine(x + 24, y + 24, x, y + 24);
      arrow(g, x + 24, y + 24, x, y + 24, false);
      label(g, e.label(), x + 4, y + 26);
      return;
    }
    double[] from = border(a, b[0] + b[2] / 2, b[1] + b[3] / 2);
    double[] to = border(b, a[0] + a[2] / 2, a[1] + a[3] / 2);
    if (e.kind() == EdgeKind.INHERITANCE) {
      // up from the child to its parent's own lane just above the children's row,
      // so lines to different parents never share a bar
      double childX = a[0] + a[2] / 2;
      double parentX = b[0] + b[2] / 2;
      double laneY = parentLanes.computeIfAbsent(e.to() + "@" + a[1],
          k -> a[1] - 22 - 9 * parentLanes.keySet().stream()
              .filter(other -> other.endsWith("@" + a[1])).count());
      g.strokeLine(childX, a[1], childX, laneY);
      g.strokeLine(childX, laneY, parentX, laneY);
      g.strokeLine(parentX, laneY, parentX, b[1] + b[3] + 12);
      triangle(g, parentX, b[1] + b[3]);
      return;
    }
    boolean sameRow = a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    if (sameRow) {
      // around underneath the row instead of through the boxes between
      double left = Math.min(a[0], b[0]);
      double right = Math.max(a[0] + a[2], b[0] + b[2]);
      double bottom = Math.max(a[1] + a[3], b[1] + b[3]);
      for (double[] r : placed.values()) {
        boolean between = r[0] < right && r[0] + r[2] > left;
        boolean inRow = r[1] < bottom && r[1] + r[3] > Math.min(a[1], b[1]);
        if (between && inRow) bottom = Math.max(bottom, r[1] + r[3]);
      }
      long key = Math.round(bottom);
      int lane = lanes.merge(key, 1, Integer::sum) - 1;
      double y = bottom + 14 + lane * 10;
      double x1 = a[0] + a[2] / 2 + (b[0] > a[0] ? 10 : -10);
      double x2 = b[0] + b[2] / 2 + (a[0] > b[0] ? 10 : -10);
      g.strokeLine(x1, a[1] + a[3], x1, y);
      g.strokeLine(x1, y, x2, y);
      g.strokeLine(x2, y, x2, b[1] + b[3]);
      arrow(g, x2, y, x2, b[1] + b[3], true);
      if (e.label() != null && !e.label().isEmpty()) {
        label(g, e.label(), Math.min(x1, x2) + Math.abs(x2 - x1) / 2 - 10, y + 1);
      }
      return;
    }
    g.strokeLine(from[0], from[1], to[0], to[1]);
    arrow(g, from[0], from[1], to[0], to[1], true);
    if (e.label() != null && !e.label().isEmpty()) {
      label(g, e.label(), (from[0] * 0.35 + to[0] * 0.65) + 4, (from[1] * 0.35 + to[1] * 0.65)
          - 16);
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

  private static void arrow(GraphicsContext g, double x1, double y1, double x2, double y2,
      boolean open) {
    double angle = Math.atan2(y2 - y1, x2 - x1);
    double len = 11;
    double a1 = angle + Math.PI - 0.45;
    double a2 = angle + Math.PI + 0.45;
    g.strokeLine(x2, y2, x2 + len * Math.cos(a1), y2 + len * Math.sin(a1));
    g.strokeLine(x2, y2, x2 + len * Math.cos(a2), y2 + len * Math.sin(a2));
  }

  private static void triangle(GraphicsContext g, double x, double y) {
    g.setFill(Color.web("#fbfbfd"));
    double[] xs = {x, x - 8, x + 8};
    double[] ys = {y, y + 12, y + 12};
    g.fillPolygon(xs, ys, 3);
    g.strokePolygon(xs, ys, 3);
  }
}
