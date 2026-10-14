package org.openpatch.scratch4j.ui;

import javafx.beans.property.BooleanProperty;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Window behavior needs a display: xvfb-run -a and -Dscratch4j.uismoke=true. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class WindowChromeIT {

  @TempDir
  Path tmp;

  @Test
  void closeStaysVisibleWhenRunningControlsAppearAtTheMinimumWindowSize() throws Exception {
    String previousSettings = System.getProperty("scratch4j.settingsDir");
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    LayoutIT.startFx();
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicReference<BooleanProperty> running = new AtomicReference<>();
    try {
      LayoutIT.onFx(() -> {
        StudioApp app = new StudioApp();
        Stage stage = new Stage();
        window.set(stage);
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(900);
        stage.setHeight(600);
        var field = StudioApp.class.getDeclaredField("running");
        field.setAccessible(true);
        running.set((BooleanProperty) field.get(app));
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> running.get().set(true));
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        Scene scene = window.get().getScene();
        Parent header = (Parent) scene.lookup(".header");
        assertThat(header.lookup(".compact-menu").isVisible()).isTrue();
        for (Node child : header.getChildrenUnmodifiable()) {
          if (!child.isManaged()) continue;
          var bounds = child.localToScene(child.getBoundsInLocal());
          assertThat(bounds.getMaxX()).as("running header control %s", child)
              .isLessThanOrEqualTo(scene.getWidth());
        }
        UiSmokeIT.snapshot(scene.getRoot(), Path.of("target/window-controls-running.png"));
        running.get().set(false);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(window.get().getScene().lookup(".header-menus").isVisible()).isTrue();
      });
    } finally {
      if (window.get() != null) LayoutIT.onFx(() -> window.get().hide());
      if (previousSettings == null) System.clearProperty("scratch4j.settingsDir");
      else System.setProperty("scratch4j.settingsDir", previousSettings);
    }
  }

  @Test
  void studioControlsFollowWindowStateAndRespectCloseCancellation() throws Exception {
    String previousSettings = System.getProperty("scratch4j.settingsDir");
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    LayoutIT.startFx();
    try {
      LayoutIT.onFx(() -> {
        Stage stage = new Stage();
        try {
          new StudioApp().start(stage);
          assertThat(stage.getStyle()).isEqualTo(
              javafx.application.Platform.isSupported(javafx.application.ConditionalFeature.EXTENDED_WINDOW)
                  ? StageStyle.EXTENDED : StageStyle.UNDECORATED);
          Button minimize = (Button) stage.getScene().lookup("#window-minimize");
          Button maximize = (Button) stage.getScene().lookup("#window-maximize");
          Button close = (Button) stage.getScene().lookup("#window-close");
          // Simulate state updates from the OS to check the icon and tooltip bindings.
          stage.hide();
          stage.setMaximized(true);
          assertThat(maximize.getTooltip().getText()).isEqualTo(I18n.t("window.restore"));
          stage.setMaximized(false);
          assertThat(maximize.getAccessibleText()).isEqualTo(I18n.t("window.maximize"));
          minimize.fire();
          assertThat(stage.isIconified()).as("a hidden window ignores minimize").isFalse();
          stage.show();

          AtomicBoolean cancel = new AtomicBoolean(true);
          AtomicInteger requests = new AtomicInteger();
          stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, e -> {
            requests.incrementAndGet();
            if (cancel.get()) e.consume();
          });
          close.fire();
          assertThat(stage.isShowing()).as("a cancelled close keeps the IDE open").isTrue();
          cancel.set(false);
          close.fire();
          assertThat(requests.get()).isEqualTo(2);
          assertThat(stage.isShowing()).as("an accepted close hides the IDE").isFalse();
        } finally {
          stage.hide();
        }
      });
    } finally {
      if (previousSettings == null) System.clearProperty("scratch4j.settingsDir");
      else System.setProperty("scratch4j.settingsDir", previousSettings);
    }
  }

  @Test
  void titleDragsAndMaximizesWhileButtonsKeepTheirOwnInput() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      Stage stage = new Stage(StageStyle.UNDECORATED);
      try {
        WindowChrome chrome = new WindowChrome(stage, false);
        Label title = new Label("Project");
        Button action = new Button("Run");
        HBox header = new HBox(title, action);
        chrome.installHeader(header, title);
        Scene scene = new Scene(new BorderPane(null, header, null, null, null), 640, 480);
        chrome.installResize(scene);
        stage.setScene(scene);
        stage.setX(100);
        stage.setY(100);
        stage.show();
        drag(title, 10, 10, 35, 20);
        assertThat(stage.getX()).isEqualTo(135);
        assertThat(stage.getY()).isEqualTo(120);
        drag(action, 10, 10, 50, 50);
        assertThat(stage.getX()).as("dragging a button does not move the window").isEqualTo(135);
        // No WM under Xvfb: a maximize request must not invent a maximized state.
        mouse(title, MouseEvent.MOUSE_CLICKED, 10, 10, 200, 200, false, 2);
        assertThat(stage.isMaximized()).isFalse();
      } finally {
        stage.hide();
      }
    });
  }

  @Test
  void resizingClampsSizeAndKeepsTheOppositeCornerFixedAfterRootReplacement() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      Stage stage = new Stage(StageStyle.UNDECORATED);
      try {
        Scene scene = new Scene(new BorderPane(), 640, 480);
        new WindowChrome(stage, false).installResize(scene);
        stage.setScene(scene);
        stage.setMinWidth(320);
        stage.setMinHeight(240);
        stage.setMaxWidth(1000);
        stage.setMaxHeight(800);
        stage.setX(100);
        stage.setY(100);
        stage.show();
        // Language changes replace the root without reinstalling scene handlers.
        BorderPane replacement = new BorderPane();
        scene.setRoot(replacement);
        double right = stage.getX() + stage.getWidth();
        double bottom = stage.getY() + stage.getHeight();
        drag(replacement, 1, 1, 500, 500);
        assertThat(stage.getWidth()).isEqualTo(320);
        assertThat(stage.getHeight()).isEqualTo(240);
        assertThat(stage.getX() + stage.getWidth()).isEqualTo(right);
        assertThat(stage.getY() + stage.getHeight()).isEqualTo(bottom);

        drag(replacement, scene.getWidth() - 1, scene.getHeight() - 1, 1200, 1200);
        assertThat(stage.getWidth()).isEqualTo(1000);
        assertThat(stage.getHeight()).isEqualTo(800);
        stage.setResizable(false);
        drag(replacement, 1, 1, 100, 100);
        assertThat(stage.getWidth()).isEqualTo(1000);
        assertThat(stage.getHeight()).isEqualTo(800);
      } finally {
        stage.hide();
      }
    });
  }

  @Test
  void ignoredWindowRequestsLeaveTheShownWindowUsable() throws Exception {
    LayoutIT.startFx();
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicInteger clicks = new AtomicInteger();
    try {
      LayoutIT.onFx(() -> {
        Stage stage = new Stage();
        window.set(stage);
        WindowChrome chrome = new WindowChrome(stage);
        stage.setTitle("Ignored window requests");
        Button action = new Button("Still working");
        action.setId("working-action");
        action.setOnAction(e -> clicks.incrementAndGet());
        HBox content = new HBox(action, chrome.controls(stage::hide));
        Node header = chrome.header(content);
        if (chrome.isExtended()) assertThat(header).isInstanceOf(HeaderBar.class);
        stage.setScene(new Scene(new BorderPane(null, header, null, null, null), 640, 480));
        stage.show();
        ((Button) content.lookup("#window-minimize")).fire();
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        Stage stage = window.get();
        assertThat(stage.isIconified()).as("an ignored minimize does not stop rendering").isFalse();
        ((Button) stage.getScene().lookup("#window-maximize")).fire();
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        Stage stage = window.get();
        assertThat(stage.isMaximized()).as("an ignored maximize preserves the current state").isFalse();
        Button action = (Button) stage.getScene().lookup("#working-action");
        action.fire();
        assertThat(clicks.get()).isEqualTo(1);
        assertThat(stage.isShowing()).isTrue();
      });
    } finally {
      if (window.get() != null) LayoutIT.onFx(() -> window.get().hide());
    }
  }

  private static void drag(Node node, double x, double y, double dx, double dy) {
    mouse(node, MouseEvent.MOUSE_PRESSED, x, y, 200, 200, true, 1);
    mouse(node, MouseEvent.MOUSE_DRAGGED, x + dx, y + dy, 200 + dx, 200 + dy, true, 1);
    mouse(node, MouseEvent.MOUSE_RELEASED, x + dx, y + dy, 200 + dx, 200 + dy, false, 1);
  }

  private static void mouse(Node node, EventType<MouseEvent> type, double x, double y,
      double screenX, double screenY, boolean down, int clicks) {
    var point = node.localToScene(x, y);
    Event.fireEvent(node, new MouseEvent(type, point.getX(), point.getY(), screenX, screenY,
        MouseButton.PRIMARY, clicks, false, false, false, false, down, false, false,
        false, false, type == MouseEvent.MOUSE_PRESSED, new PickResult(node, x, y)));
  }
}
