package org.openpatch.scratch4j.ui;

import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Locale;

/** Native extended header, with custom controls and an undecorated fallback. */
@SuppressWarnings("deprecation") // JavaFX 25 HeaderBar is a preview API.
final class WindowChrome {

  private static final double RESIZE_MARGIN = 5;
  private final Stage stage;
  private final boolean extended;
  private final X11WindowActions x11Actions;
  private Edges resizing;
  private boolean moving;
  private double pressX, pressY, windowX, windowY, windowWidth, windowHeight;

  WindowChrome(Stage stage) {
    this(stage, Platform.isSupported(ConditionalFeature.EXTENDED_WINDOW));
  }

  WindowChrome(Stage stage, boolean extended) {
    this.stage = stage;
    this.extended = extended;
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    x11Actions = os.contains("linux") || os.contains("bsd") ? new X11WindowActions() : null;
    stage.initStyle(extended ? StageStyle.EXTENDED : StageStyle.UNDECORATED);
    if (extended) HeaderBar.setPrefButtonHeight(stage, 0);
  }

  boolean isExtended() {
    return extended;
  }

  Node header(Node content, Node... handles) {
    if (!extended) {
      installHeader(content, handles);
      return content;
    }
    HeaderBar header = new HeaderBar();
    header.setCenter(content);
    HeaderBar.setAlignment(content, Pos.CENTER_LEFT);
    HeaderBar.setDragType(content, HeaderDragType.DRAGGABLE);
    for (Node handle : handles) HeaderBar.setDragType(handle, HeaderDragType.DRAGGABLE_SUBTREE);
    header.getStyleClass().add("studio-header");
    return header;
  }

  /** Recreated with the header so tooltips follow the current language. */
  HBox controls(Runnable close) {
    Button minimize = button("window-minimize", "fth-minus", "window.minimize",
        this::minimize);
    Button maximize = button("window-maximize", "fth-square", "window.maximize",
        this::toggleMaximized);
    maximize.disableProperty().bind(stage.resizableProperty().not());
    maximize.graphicProperty().bind(Bindings.createObjectBinding(
        () -> Icons.of(stage.isMaximized() ? "fth-copy" : "fth-square", 16),
        stage.maximizedProperty()));
    maximize.tooltipProperty().bind(Bindings.createObjectBinding(
        () -> new Tooltip(I18n.t(stage.isMaximized() ? "window.restore" : "window.maximize")),
        stage.maximizedProperty()));
    maximize.accessibleTextProperty().bind(Bindings.createStringBinding(
        () -> I18n.t(stage.isMaximized() ? "window.restore" : "window.maximize"),
        stage.maximizedProperty()));
    Button exit = button("window-close", "fth-x", "window.close", close);
    HBox controls = new HBox(2, minimize, maximize, exit);
    controls.setAlignment(Pos.CENTER);
    controls.getStyleClass().add("window-controls");
    return controls;
  }

  private Button button(String id, String icon, String label, Runnable action) {
    Button button = Icons.button(icon, I18n.t(label), action);
    button.setId(id);
    button.setGraphic(Icons.of(icon, 16));
    button.setAccessibleText(I18n.t(label));
    button.getStyleClass().addAll("header-icon", "window-button");
    return button;
  }

  private void toggleMaximized() {
    if (!stage.isResizable() || !stage.isShowing()) return;
    if (x11Actions != null) {
      x11Actions.request(stage.getTitle(), stage.isMaximized()
          ? X11WindowActions.Action.RESTORE : X11WindowActions.Action.MAXIMIZE);
    } else {
      stage.setMaximized(!stage.isMaximized());
    }
  }

  private void minimize() {
    if (!stage.isShowing()) return;
    if (x11Actions != null) {
      x11Actions.request(stage.getTitle(), X11WindowActions.Action.MINIMIZE);
    } else {
      stage.setIconified(true);
    }
  }

  void maximize() {
    if (!stage.isMaximized()) toggleMaximized();
  }

  /** Only empty header space and the title act as a drag handle. */
  void installHeader(Node header, Node... handles) {
    header.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
      if (e.getButton() != MouseButton.PRIMARY || !isHandle(e, header, handles)) return;
      if (stage.isMaximized() || stage.isFullScreen()) return;
      rememberBounds(e);
      moving = true;
      e.consume();
    });
    header.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
      if (!moving || !e.isPrimaryButtonDown()) return;
      stage.setX(windowX + e.getScreenX() - pressX);
      stage.setY(windowY + e.getScreenY() - pressY);
      e.consume();
    });
    header.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
          && isHandle(e, header, handles) && !stage.isFullScreen()) {
        toggleMaximized();
        e.consume();
      }
    });
  }

  private boolean isHandle(MouseEvent event, Node header, Node[] handles) {
    if (event.getTarget() == header) return true;
    if (!(event.getTarget() instanceof Node target)) return false;
    for (Node node = target; node != null && node != header; node = node.getParent()) {
      for (Node handle : handles) if (node == handle) return true;
    }
    return false;
  }

  /** Install once on the scene; language switches can replace its root. */
  void installResize(Scene scene) {
    if (extended) return; // EXTENDED provides native edge resizing.
    scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
      Edges edges = edgesAt(e, scene);
      scene.setCursor(edges == null ? null : edges.cursor());
    });
    scene.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
      if (resizing == null) scene.setCursor(null);
    });
    scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
      moving = false;
      if (e.getButton() != MouseButton.PRIMARY) return;
      resizing = edgesAt(e, scene);
      if (resizing != null) {
        rememberBounds(e);
        e.consume();
      }
    });
    scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
      if (resizing == null || !e.isPrimaryButtonDown()) return;
      double dx = e.getScreenX() - pressX;
      double dy = e.getScreenY() - pressY;
      if (resizing.left || resizing.right) {
        double width = clamp(windowWidth + (resizing.left ? -dx : dx),
            stage.getMinWidth(), stage.getMaxWidth());
        stage.setWidth(width);
        if (resizing.left) stage.setX(windowX + windowWidth - width);
      }
      if (resizing.top || resizing.bottom) {
        double height = clamp(windowHeight + (resizing.top ? -dy : dy),
            stage.getMinHeight(), stage.getMaxHeight());
        stage.setHeight(height);
        if (resizing.top) stage.setY(windowY + windowHeight - height);
      }
      e.consume();
    });
    scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
      moving = false;
      if (resizing != null) {
        resizing = null;
        scene.setCursor(null);
        e.consume();
      }
    });
  }

  private Edges edgesAt(MouseEvent e, Scene scene) {
    if (!stage.isResizable() || stage.isMaximized() || stage.isFullScreen()) return null;
    boolean left = e.getSceneX() <= RESIZE_MARGIN;
    boolean right = e.getSceneX() >= scene.getWidth() - RESIZE_MARGIN;
    boolean top = e.getSceneY() <= RESIZE_MARGIN;
    boolean bottom = e.getSceneY() >= scene.getHeight() - RESIZE_MARGIN;
    return left || right || top || bottom ? new Edges(left, right, top, bottom) : null;
  }

  private void rememberBounds(MouseEvent e) {
    pressX = e.getScreenX();
    pressY = e.getScreenY();
    windowX = stage.getX();
    windowY = stage.getY();
    windowWidth = stage.getWidth();
    windowHeight = stage.getHeight();
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private record Edges(boolean left, boolean right, boolean top, boolean bottom) {
    Cursor cursor() {
      if (left) return top ? Cursor.NW_RESIZE : bottom ? Cursor.SW_RESIZE : Cursor.W_RESIZE;
      if (right) return top ? Cursor.NE_RESIZE : bottom ? Cursor.SE_RESIZE : Cursor.E_RESIZE;
      return top ? Cursor.N_RESIZE : Cursor.S_RESIZE;
    }
  }
}
