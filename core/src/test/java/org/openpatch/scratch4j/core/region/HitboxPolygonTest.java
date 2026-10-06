package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.region.HitboxPolygon.Point;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HitboxPolygonTest {

  @Test
  void validatesSimplePolygonAndRejectsCrossedOrDegenerateShapes() {
    assertThat(HitboxPolygon.valid(List.of(new Point(0, 0), new Point(20, 0),
        new Point(20, 20), new Point(0, 20)), 20, 20)).isTrue();
    assertThat(HitboxPolygon.valid(List.of(new Point(0, 0), new Point(20, 20),
        new Point(0, 20), new Point(20, 0)), 20, 20)).isFalse();
    assertThat(HitboxPolygon.valid(List.of(new Point(0, 0), new Point(10, 10),
        new Point(20, 20)), 20, 20)).isFalse();
    assertThat(HitboxPolygon.valid(List.of(new Point(0, 0), new Point(20, 0),
        new Point(21, 20)), 20, 20)).isFalse();
  }

  @Test
  void picksNearestEdgeWithinTolerance() {
    List<Point> square = List.of(new Point(0, 0), new Point(20, 0),
        new Point(20, 20), new Point(0, 20));
    assertThat(HitboxPolygon.nearestEdge(square, new Point(10, 2), 3)).isZero();
    assertThat(HitboxPolygon.nearestEdge(square, new Point(18, 10), 3)).isEqualTo(1);
    assertThat(HitboxPolygon.nearestEdge(square, new Point(10, 10), 3)).isEqualTo(-1);
  }
}
