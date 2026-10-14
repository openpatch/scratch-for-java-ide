package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DiagramRouterTest {

  private static DiagramRouter.Link uses(String from, String to) {
    return new DiagramRouter.Link(from, to, null);
  }

  private static DiagramRouter.Link extendsClass(String from, String to) {
    return new DiagramRouter.Link(from, to, "extends");
  }

  @Test
  void twoArrowsBetweenTheSameClassesRunApart() {
    Map<String, double[]> boxes = new LinkedHashMap<>();
    boxes.put("Game", new double[] {20, 20, 120, 80});
    boxes.put("Player", new double[] {260, 20, 120, 80});
    List<DiagramRouter.Link> links = List.of(uses("Game", "Player"), uses("Game", "Player"));
    List<DiagramRouter.Route> routes = DiagramRouter.route(boxes, links);

    assertThat(routes).doesNotContainNull();
    assertThat(routes.get(0).end()).isNotEqualTo(routes.get(1).end());
    assertNoSharedStretch(routes, links);
    assertAroundTheBoxes(routes, boxes);
  }

  @Test
  void arrowsGoAroundTheClassesInBetween() {
    Map<String, double[]> boxes = new LinkedHashMap<>();
    boxes.put("A", new double[] {20, 20, 100, 60});
    boxes.put("B", new double[] {170, 20, 100, 60});
    boxes.put("C", new double[] {320, 20, 100, 60});
    boxes.put("D", new double[] {170, 200, 100, 60});
    List<DiagramRouter.Link> links = List.of(uses("A", "C"), uses("C", "A"), uses("A", "D"),
        uses("D", "C"), uses("B", "D"));
    List<DiagramRouter.Route> routes = DiagramRouter.route(boxes, links);

    assertThat(routes).doesNotContainNull();
    assertNoSharedStretch(routes, links);
    assertAroundTheBoxes(routes, boxes);
    for (DiagramRouter.Route route : routes) assertRightAngled(route);
  }

  @Test
  void subclassesMeetOnOneLineUnderOneTriangle() {
    Map<String, double[]> boxes = new LinkedHashMap<>();
    boxes.put("Sprite", new double[] {200, 20, 90, 30});
    boxes.put("Cat", new double[] {20, 160, 120, 60});
    boxes.put("Dog", new double[] {190, 160, 120, 60});
    boxes.put("Bird", new double[] {360, 160, 120, 60});
    List<DiagramRouter.Link> links = List.of(extendsClass("Cat", "Sprite"),
        extendsClass("Dog", "Sprite"), extendsClass("Bird", "Sprite"));
    List<DiagramRouter.Route> routes = DiagramRouter.route(boxes, links);

    // one tip, at the middle of the parent's bottom
    for (DiagramRouter.Route route : routes) {
      assertThat(route.end()).containsExactly(245, 50);
    }
    // the side ones reach the common line at the same height
    assertThat(routes.get(0).points()).hasSize(4);
    assertThat(routes.get(2).points()).hasSize(4);
    assertThat(routes.get(0).points().get(1)[1]).isEqualTo(routes.get(2).points().get(1)[1]);
    // the middle one goes straight up
    assertThat(routes.get(1).points()).hasSize(2);
  }

  @Test
  void aClassRightBelowAnotherIsJoinedByAStraightLine() {
    Map<String, double[]> boxes = new LinkedHashMap<>();
    boxes.put("Enemy", new double[] {20, 20, 180, 60});
    boxes.put("Bamboo", new double[] {20, 200, 190, 60});
    List<DiagramRouter.Route> routes = DiagramRouter.route(boxes,
        List.of(extendsClass("Bamboo", "Enemy")));
    assertThat(routes.get(0).points()).hasSize(2);
    assertThat(routes.get(0).points().get(0)[0]).isEqualTo(routes.get(0).end()[0]);
  }

  @Test
  void aReferenceToItselfIsALoopOnTheRight() {
    Map<String, double[]> boxes = Map.of("Node", new double[] {20, 20, 100, 60});
    DiagramRouter.Route loop = DiagramRouter.route(boxes, List.of(uses("Node", "Node"))).get(0);
    assertThat(loop.points()).hasSize(4);
    assertThat(loop.points()).allSatisfy(p -> assertThat(p[0]).isGreaterThanOrEqualTo(120));
  }

  @Test
  void overlappingBoxesAreLeftToAStraightLine() {
    Map<String, double[]> boxes = new LinkedHashMap<>();
    boxes.put("A", new double[] {20, 20, 100, 60});
    boxes.put("B", new double[] {60, 40, 100, 60});
    assertThat(DiagramRouter.route(boxes, List.of(uses("A", "B")))).containsExactly(
        (DiagramRouter.Route) null);
  }

  @Test
  void labelsKeepClearOfBoxesAndEachOther() {
    Map<String, double[]> boxes = new LinkedHashMap<>();
    boxes.put("Game", new double[] {20, 20, 120, 80});
    boxes.put("Player", new double[] {20, 220, 120, 80});
    List<DiagramRouter.Route> routes = DiagramRouter.route(boxes,
        List.of(uses("Game", "Player"), uses("Game", "Player")));
    List<double[]> taken = new ArrayList<>();
    double[] first = DiagramRouter.placeLabel(routes.get(0), 50, 15, boxes.values(), taken);
    double[] second = DiagramRouter.placeLabel(routes.get(1), 50, 15, boxes.values(), taken);
    for (double[] at : List.of(first, second)) {
      double[] label = {at[0], at[1], 50, 15};
      for (double[] box : boxes.values()) assertThat(intersects(label, box)).isFalse();
    }
    assertThat(intersects(new double[] {first[0], first[1], 50, 15},
        new double[] {second[0], second[1], 50, 15})).isFalse();
  }

  @Test
  void aBusyDiagramIsRoutedQuickly() {
    // forty classes, a hundred associations: fast enough to follow a drag
    Map<String, double[]> boxes = new LinkedHashMap<>();
    for (int i = 0; i < 40; i++) {
      boxes.put("C" + i, new double[] {20 + (i % 8) * 190, 20 + (i / 8) * 200, 140, 90});
    }
    Random random = new Random(7);
    List<DiagramRouter.Link> links = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      links.add(uses("C" + random.nextInt(40), "C" + random.nextInt(40)));
    }
    DiagramRouter.route(boxes, links); // warm up
    long start = System.nanoTime();
    List<DiagramRouter.Route> routes = DiagramRouter.route(boxes, links);
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertThat(millis).isLessThan(1500);
    assertThat(routes.stream().filter(r -> r != null).count()).isEqualTo(links.size());
    assertAroundTheBoxes(routes, boxes);
  }

  // --- checks ------------------------------------------------------------------------------

  /** No two links of different bundles run along the same stretch. */
  private static void assertNoSharedStretch(List<DiagramRouter.Route> routes,
      List<DiagramRouter.Link> links) {
    for (int a = 0; a < routes.size(); a++) {
      for (int b = a + 1; b < routes.size(); b++) {
        if (links.get(a).bundle() != null && links.get(a).bundle().equals(links.get(b).bundle())
            && links.get(a).to().equals(links.get(b).to())) continue;
        for (double[][] s : segments(routes.get(a))) {
          for (double[][] t : segments(routes.get(b))) {
            assertThat(sharedLength(s, t)).as("links %d and %d share a stretch", a, b)
                .isLessThan(1);
          }
        }
      }
    }
  }

  /** No line runs through a box (they start and end on the border). */
  private static void assertAroundTheBoxes(List<DiagramRouter.Route> routes,
      Map<String, double[]> boxes) {
    for (DiagramRouter.Route route : routes) {
      if (route == null) continue;
      for (double[][] s : segments(route)) {
        for (double[] box : boxes.values()) {
          double[] inner = {box[0] + 1, box[1] + 1, box[2] - 2, box[3] - 2};
          double[] line = {Math.min(s[0][0], s[1][0]), Math.min(s[0][1], s[1][1]),
              Math.abs(s[0][0] - s[1][0]), Math.abs(s[0][1] - s[1][1])};
          assertThat(intersectsClosed(line, inner)).as("a line through a box").isFalse();
        }
      }
    }
  }

  private static void assertRightAngled(DiagramRouter.Route route) {
    for (double[][] s : segments(route)) {
      assertThat(s[0][0] == s[1][0] || s[0][1] == s[1][1]).isTrue();
    }
  }

  private static List<double[][]> segments(DiagramRouter.Route route) {
    List<double[][]> segments = new ArrayList<>();
    for (int k = 0; k + 1 < route.points().size(); k++) {
      segments.add(new double[][] {route.points().get(k), route.points().get(k + 1)});
    }
    return segments;
  }

  private static double sharedLength(double[][] s, double[][] t) {
    boolean sh = Math.abs(s[0][1] - s[1][1]) < 1e-6;
    boolean th = Math.abs(t[0][1] - t[1][1]) < 1e-6;
    if (sh != th) return 0;
    int along = sh ? 0 : 1;
    int across = sh ? 1 : 0;
    if (Math.abs(s[0][across] - t[0][across]) > 1e-6) return 0;
    double lo = Math.max(Math.min(s[0][along], s[1][along]), Math.min(t[0][along], t[1][along]));
    double hi = Math.min(Math.max(s[0][along], s[1][along]), Math.max(t[0][along], t[1][along]));
    return hi - lo;
  }

  private static boolean intersects(double[] a, double[] b) {
    return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
  }

  private static boolean intersectsClosed(double[] line, double[] box) {
    return line[0] <= box[0] + box[2] && box[0] <= line[0] + line[2]
        && line[1] <= box[1] + box[3] && box[1] <= line[1] + line[3];
  }
}
