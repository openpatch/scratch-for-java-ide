package org.openpatch.scratch4j.ui;

import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window settings of a project that makes its window in {@code main}, as
 * the tutorials do (needs a display: {@code xvfb-run -a} and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class WindowSettingsIT {

  @TempDir
  Path tmp;

  @Test
  void theWindowMadeInMainIsReadAndChangedWhereItIs() throws Exception {
    I18n.Language language = I18n.current();
    I18n.set(I18n.Language.EN);
    Path coins = BundledTemplates.create("catch-the-coins-100", tmp, "coins", null);
    Path stageFile = coins.resolve("CatchStage.java");
    assertThat(stageFile).content().contains("Window myWindow = new Window(600, 400);");
    LayoutIT.startFx();
    try {
      AtomicReference<StageSelectorView> selector = new AtomicReference<>();
      LayoutIT.onFx(() -> {
        selector.set(new StageSelectorView(ScratchProject.open(coins), s -> { }, s -> { },
            () -> { }));
        // the dialog waits for its answer: open it after this step
        javafx.application.Platform.runLater(() -> selector.get().showWindowSettings());
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        DialogPane pane = javafx.stage.Window.getWindows().stream()
            .filter(w -> w.isShowing() && w.getScene() != null
                && w.getScene().getRoot() instanceof DialogPane)
            .map(w -> (DialogPane) w.getScene().getRoot()).findFirst().orElseThrow();
        assertThat(pane.lookupAll(".label").stream().map(n -> ((Label) n).getText()))
            .contains(I18n.t("stages.window.inmain", "CatchStage.java"));
        var fields = pane.lookupAll(".text-field").stream().map(n -> (TextField) n).toList();
        TextField width = fields.stream().filter(f -> "600".equals(f.getText()))
            .findFirst().orElseThrow();
        assertThat(fields).anyMatch(f -> "400".equals(f.getText()));
        // no window class to create: the window is already there
        assertThat(pane.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
            .filter(c -> c.getText().equals(I18n.t("stages.window.create"))))
            .allMatch(c -> !c.isVisible());
        width.setText("800");
        pane.lookupAll(".check-box").stream().map(n -> (CheckBox) n)
            .filter(c -> c.getText().equals(I18n.t("stages.window.fullscreen")))
            .findFirst().orElseThrow().setSelected(true);
        UiSmokeIT.snapshot(pane, Path.of("target/window-settings-main.png"));
        ((javafx.scene.control.Button) pane.lookupButton(I18n.ok())).fire();
      });
      LayoutIT.settle();
      String source = Files.readString(stageFile);
      assertThat(source).contains("""
              Window.useFullScreen();
              Window myWindow = new Window(800, 400);
              myWindow.setStage(new CatchStage());
          """);
      assertThat(coins.resolve("MyWindow.java")).doesNotExist();
      ScratchProject reopened = ScratchProject.open(coins);
      assertThat(reopened.settings().fullScreen).as("the code holds it, not the metadata")
          .isFalse();
    } finally {
      I18n.set(language);
    }
  }
}
