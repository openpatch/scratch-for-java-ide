package org.openpatch.scratch4j.ui;

import javafx.scene.Node;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.LibraryJarSource;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;

/**
 * The workbench: the bottom panel collapses to its tab row (button, a click
 * on the shown tab, Ctrl+J) and opens again, the sidebar collapses to a rail
 * (Ctrl+B), and Ctrl+P finds and opens a file (needs a display and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class WorkbenchIT {

  @TempDir Path tmp;

  private static void press(Node target, KeyCode code, boolean ctrl) {
    javafx.event.Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
        false, ctrl, false, false));
  }

  private static Popup quickOpenPopup() {
    return Window.getWindows().stream()
        .filter(w -> w instanceof Popup p && p.isShowing()
            && p.getContent().stream().anyMatch(n -> n.getStyleClass().contains("quick-open")))
        .map(w -> (Popup) w).findFirst().orElse(null);
  }

  @Test
  void collapsesTheBottomPanelAndOpensFilesWithCtrlP() throws Exception {
    String previousSettings = System.getProperty("scratch4j.settingsDir");
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "project",
        LibraryJarSource.allJar(Path.of(System.getProperty("user.dir"), "target", "bundled")));
    Path root = tmp.resolve("project");
    Path hero = root.resolve("assets/images/hero-sheet.png");
    java.nio.file.Files.createDirectories(hero.getParent());
    ImageIO.write(new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB), "png", hero.toFile());
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    LayoutIT.startFx();
    onFx(() -> {
      StudioApp studio = new StudioApp();
      Stage stage = new Stage();
      studio.start(stage);
      studio.openProjectAt(root);
      window.set(stage);
      app.set(studio);
    });
    settle();
    try {
      AtomicReference<Double> openHeight = new AtomicReference<>();
      onFx(() -> {
        TabPane bottom = (TabPane) window.get().getScene().lookup(".bottom-tabs");
        openHeight.set(bottom.getHeight());
        assertThat(openHeight.get()).isGreaterThan(80);
        app.get().setBottomCollapsed(true);
      });
      settle();
      onFx(() -> {
        TabPane bottom = (TabPane) window.get().getScene().lookup(".bottom-tabs");
        assertThat(bottom.getHeight()).isLessThan(60);
        // the IDE shows another tab: the panel opens at its old height
        bottom.getSelectionModel().select(1);
        assertThat(app.get().bottomCollapsed()).isFalse();
        // Ctrl+J collapses and opens it
        press(window.get().getScene().getRoot(), KeyCode.J, true);
        assertThat(app.get().bottomCollapsed()).isTrue();
        press(window.get().getScene().getRoot(), KeyCode.J, true);
        assertThat(app.get().bottomCollapsed()).isFalse();
      });
      settle();
      onFx(() -> {
        TabPane bottom = (TabPane) window.get().getScene().lookup(".bottom-tabs");
        assertThat(bottom.getHeight()).isCloseTo(openHeight.get(),
            org.assertj.core.data.Offset.offset(4.0));
        // Ctrl+P: "hsh" finds hero-sheet.png, Enter opens it in the image editor
        press(window.get().getScene().getRoot(), KeyCode.P, true);
        Popup popup = quickOpenPopup();
        assertThat(popup).isNotNull();
        TextField field = (TextField) popup.getContent().getFirst().lookup(".quick-open-field");
        field.setText("hsh");
        press(field, KeyCode.ENTER, false);
        assertThat(popup.isShowing()).isFalse();
      });
      settle();
      onFx(() -> {
        var editor = app.get().editorForTests();
        assertThat(EditorTabs.fileOf(editor.getSelectionModel().getSelectedItem()))
            .isEqualTo(hero.toAbsolutePath().normalize());
        // Ctrl + wheel in the image editor zooms in, the other way out
        ImageEditorView image = (ImageEditorView) editor.getSelectionModel().getSelectedItem()
            .getContent();
        double before = image.zoomForTest();
        Node canvas = image.canvasForTest();
        var center = canvas.localToScene(canvas.getLayoutBounds().getCenterX(),
            canvas.getLayoutBounds().getCenterY());
        javafx.event.Event.fireEvent(canvas, new javafx.scene.input.ScrollEvent(
            javafx.scene.input.ScrollEvent.SCROLL, center.getX(), center.getY(), 0, 0,
            false, true, false, false, false, false, 0, 40, 0, 40,
            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
        assertThat(image.zoomForTest()).isGreaterThan(before);
        javafx.event.Event.fireEvent(canvas, new javafx.scene.input.ScrollEvent(
            javafx.scene.input.ScrollEvent.SCROLL, center.getX(), center.getY(), 0, 0,
            false, true, false, false, false, false, 0, -40, 0, -40,
            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
        assertThat(image.zoomForTest()).isCloseTo(before,
            org.assertj.core.data.Offset.offset(0.001));
      });
      // Ctrl+B collapses the sidebar to a rail of its tabs; a tab's icon opens it there
      AtomicReference<Double> sidebarWidth = new AtomicReference<>();
      onFx(() -> {
        TabPane sidebar = (TabPane) window.get().getScene().lookup(".sidebar-tabs");
        sidebarWidth.set(sidebar.getWidth());
        sidebar.getSelectionModel().select(0);
        press(window.get().getScene().getRoot(), KeyCode.B, true);
        assertThat(app.get().sidebarCollapsed()).isTrue();
      });
      settle();
      onFx(() -> {
        Node rail = window.get().getScene().lookup(".sidebar-rail");
        assertThat(rail.getBoundsInParent().getWidth()).isLessThan(60);
        assertThat(window.get().getScene().lookup(".sidebar-tabs")).isNull();
        // the expand button, then Stages, Files and the Reference
        var buttons = ((javafx.scene.layout.VBox) rail).getChildren();
        assertThat(buttons).hasSize(4);
        ((javafx.scene.control.Button) buttons.get(2)).fire();
        assertThat(app.get().sidebarCollapsed()).isFalse();
      });
      settle();
      onFx(() -> {
        TabPane sidebar = (TabPane) window.get().getScene().lookup(".sidebar-tabs");
        assertThat(sidebar.getSelectionModel().getSelectedIndex()).isEqualTo(1);
        assertThat(sidebar.getWidth()).isCloseTo(sidebarWidth.get(),
            org.assertj.core.data.Offset.offset(4.0));
      });
    } finally {
      onFx(() -> window.get().hide());
      if (previousSettings == null) System.clearProperty("scratch4j.settingsDir");
      else System.setProperty("scratch4j.settingsDir", previousSettings);
    }
  }
}
