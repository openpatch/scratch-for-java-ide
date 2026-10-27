package org.openpatch.scratch4j.ui;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Zooming a scrolled canvas around the mouse, as the image, map and diagram
 * editors do: the point under the mouse stays where it is.
 */
final class ScrollZoom {

  private ScrollZoom() {}

  /**
   * Ctrl + wheel zooms by {@code step} (a pinch by its own factor) around the
   * mouse. {@code zoom} reads the editor's zoom, {@code setZoom} asks for a new
   * one (the editor may clamp it) and draws {@code content} at it.
   */
  static void install(ScrollPane scroll, Node content, double step, DoubleSupplier zoom,
      DoubleConsumer setZoom) {
    scroll.addEventFilter(ScrollEvent.SCROLL, e -> {
      if (e.isShortcutDown() && e.getDeltaY() != 0) {
        double next = e.getDeltaY() > 0 ? zoom.getAsDouble() * step : zoom.getAsDouble() / step;
        around(scroll, content, e.getSceneX(), e.getSceneY(), zoom,
            () -> setZoom.accept(next));
        e.consume();
      }
    });
    scroll.addEventFilter(ZoomEvent.ZOOM, e -> {
      double next = zoom.getAsDouble() * e.getZoomFactor();
      around(scroll, content, e.getSceneX(), e.getSceneY(), zoom, () -> setZoom.accept(next));
      e.consume();
    });
  }

  /**
   * Runs {@code applyZoom}, then scrolls so the point of {@code content} that
   * was at ({@code sceneX}, {@code sceneY}) is there again.
   */
  static void around(ScrollPane scroll, Node content, double sceneX, double sceneY,
      DoubleSupplier zoom, Runnable applyZoom) {
    double before = zoom.getAsDouble();
    Point2D local = content.sceneToLocal(sceneX, sceneY);
    applyZoom.run();
    double ratio = zoom.getAsDouble() / before;
    scroll.applyCss();
    scroll.layout();
    Point2D now = content.localToScene(local.getX() * ratio, local.getY() * ratio);
    var viewport = scroll.getViewportBounds();
    var bounds = scroll.getContent().getLayoutBounds();
    double extraW = bounds.getWidth() - viewport.getWidth();
    double extraH = bounds.getHeight() - viewport.getHeight();
    if (extraW > 0) {
      scroll.setHvalue(clamp(scroll.getHvalue() + (now.getX() - sceneX) / extraW));
    }
    if (extraH > 0) {
      scroll.setVvalue(clamp(scroll.getVvalue() + (now.getY() - sceneY) / extraH));
    }
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }
}
