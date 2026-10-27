package org.openpatch.scratch4j.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.LibraryJarSource;
import org.openpatch.scratch4j.core.project.ProjectSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

/**
 * The full interface by default; a teacher's beginner flag in project.json
 * hides the advanced menu items and editor actions. The Reference finds a
 * method and the tour walks through the window. Run under {@code xvfb-run -a}
 * with {@code -Dscratch4j.uismoke=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class BeginnerInterfaceIT {

  @TempDir
  Path tmp;

  @Test
  void beginnerFlagHidesAdvancedItemsAndReferenceAndTourWork() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    Path jar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    Path full = BundledTemplates.create("demo-tiled", tmp, "full", jar);
    Path beginner = BundledTemplates.create("demo-tiled", tmp, "beginner", jar);
    ProjectSettings settings = ProjectSettings.load(beginner);
    settings.beginnerMode = true;
    settings.save(beginner);

    startFx();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<javafx.stage.Stage> window = new AtomicReference<>();
    onFx(() -> {
      StudioApp studio = new StudioApp();
      javafx.stage.Stage stage = new javafx.stage.Stage();
      studio.start(stage);
      stage.setWidth(1360);
      stage.setHeight(840);
      studio.openProjectAt(full);
      app.set(studio);
      window.set(stage);
    });
    settle();
    onFx(() -> {
      assertThat(CodeEditor.BEGINNER.get()).as("full interface by default").isFalse();
      MenuItem debug = item(window.get(), I18n.t("menu.run.debug"));
      assertThat(debug.isVisible()).isTrue();
      assertThat(debug.isDisable()).isFalse();

      // F1's Reference: every method, searchable, the exact name first
      ReferenceView reference = (ReferenceView) window.get().getScene().getRoot()
          .lookup(".reference-view");
      assertThat(reference).isNotNull();
      reference.lookUp("move");
      assertThat(reference.shown()).isNotEmpty();
      assertThat(reference.shown()).allMatch(m -> (m.methodName() + m.className()
          + m.scratchblock() + m.summary()).toLowerCase().contains("move"));
      var sidebar = (javafx.scene.control.TabPane) window.get().getScene().lookup(".sidebar-tabs");
      sidebar.getSelectionModel().select(2);
    });

    settle();
    onFx(() -> UiSmokeIT.snapshot(window.get().getScene().getRoot(),
        Path.of("target/reference.png")));
    onFx(() -> app.get().openProjectAt(beginner));
    settle();
    onFx(() -> {
      assertThat(CodeEditor.BEGINNER.get()).isTrue();
      for (String key : List.of("menu.run.debug", "menu.run.step", "menu.file.quickopen",
          "menu.edit.findproject", "diagram.menu")) {
        MenuItem advanced = item(window.get(), I18n.t(key));
        assertThat(advanced.isVisible()).as(key).isFalse();
        assertThat(advanced.isDisable()).as("%s shortcut off", key).isTrue();
      }
      assertThat(item(window.get(), I18n.t("menu.run.run")).isVisible()).isTrue();
    });

    // the tour: one card per part on screen, Next to the end remembers it
    onFx(() -> app.get().startTour());
    settle();
    onFx(() -> {
      for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
        if (w instanceof javafx.stage.Popup popup && popup.isShowing()) {
          UiSmokeIT.snapshot(popup.getContent().get(0), Path.of("target/tour-card.png"));
        }
      }
      UiSmokeIT.snapshot(window.get().getScene().getRoot(), Path.of("target/tour-window.png"));
    });
    for (int i = 0; i < 10; i++) {
      AtomicReference<Button> next = new AtomicReference<>();
      onFx(() -> {
        for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
          if (w instanceof javafx.stage.Popup popup && popup.isShowing()) {
            for (javafx.scene.Node content : popup.getContent()) {
              if (content.lookup("#tour-next") instanceof Button button) next.set(button);
            }
          }
        }
        if (next.get() != null) next.get().fire();
      });
      if (next.get() == null) break;
    }
    onFx(() -> assertThat(Prefs.tourShown()).isTrue());
    onFx(() -> window.get().close());
  }

  @Test
  void handInNamesAreFileSafe() {
    assertThat(StudioApp.handInPart(" Anna Müller ")).isEqualTo("Anna-Müller");
    assertThat(StudioApp.handInPart("../x")).isEqualTo("x");
    assertThat(StudioApp.handInPart("///")).isEqualTo("project");
  }

  private static MenuItem item(javafx.stage.Window window, String text) {
    MenuBar bar = (MenuBar) window.getScene().getRoot().lookup(".header-menus");
    List<MenuItem> all = new ArrayList<>();
    for (Menu menu : bar.getMenus()) collect(menu, all);
    return all.stream().filter(i -> text.equals(i.getText())).findFirst()
        .orElseThrow(() -> new AssertionError("no menu item " + text));
  }

  private static void collect(Menu menu, List<MenuItem> into) {
    for (MenuItem item : menu.getItems()) {
      into.add(item);
      if (item instanceof Menu sub) collect(sub, into);
    }
  }
}
