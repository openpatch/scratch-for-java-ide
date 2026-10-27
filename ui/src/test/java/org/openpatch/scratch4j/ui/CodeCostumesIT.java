package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

/**
 * Sprites whose costumes come from constructor arguments: the stage designer
 * draws each racer as its own creature, and Racer's editor lists them.
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class CodeCostumesIT {

  @TempDir
  Path tmp;

  @Test
  void racersLookLikeTheirCreaturesInTheDesignerAndTheSpriteEditor() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    I18n.set(I18n.Language.EN);
    Path race = BundledTemplates.create("red-light-green-light-100", tmp, "race",
        org.openpatch.scratch4j.core.project.LibraryJarSource.allJar(
            Path.of(System.getProperty("user.dir"), "target", "bundled")));
    startFx();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<javafx.stage.Stage> window = new AtomicReference<>();
    onFx(() -> {
      StudioApp studio = new StudioApp();
      javafx.stage.Stage stage = new javafx.stage.Stage();
      studio.start(stage);
      stage.setWidth(1360);
      stage.setHeight(840);
      studio.openProjectAt(race);
      app.set(studio);
      window.set(stage);
    });
    settle();
    // the racers come from the program's own first frame: Refresh draws it
    AtomicReference<StageDesignerView> designer = new AtomicReference<>();
    onFx(() -> designer.set((StageDesignerView) window.get().getScene().getRoot()
        .lookupAll(".designer").stream().findFirst().orElseThrow()));
    onFx(() -> designer.get().refreshPicture());
    for (int i = 0; i < 600; i++) {
      AtomicReference<Boolean> ready = new AtomicReference<>(false);
      onFx(() -> ready.set(designer.get().hasPreview()));
      if (ready.get()) break;
      Thread.sleep(100);
    }
    onFx(() -> {
      var root = window.get().getScene().getRoot();
      assertThat(designer.get().hasPreview()).isTrue();
      assertThat(designer.get().codeSprites().stream().filter(g -> g.type().equals("Racer")))
          .extracting(StageRenderer.CodeSprite::costume)
          .containsExactlyInAnyOrder("bee", "ladybug", "snail");
      UiSmokeIT.snapshot(root, Path.of("target/race-designer.png"));
      app.get().openSpriteAssets(race.resolve("Racer.java"));
    });
    settle();
    onFx(() -> {
      var root = window.get().getScene().getRoot();
      var tiles = root.lookupAll(".code-costume").stream()
          .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
      assertThat(tiles).containsExactly("bee", "bee_move", "ladybug", "ladybug_move", "snail",
          "snail_move");
      UiSmokeIT.snapshot(root, Path.of("target/racer-costumes.png"));
    });
  }
}
