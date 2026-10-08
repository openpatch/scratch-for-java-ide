package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole IDE window at its smallest size (900x600) with the tiled demo's
 * World in the designer: the header fits, the stage preview fits its space
 * and shows the map. Run under {@code xvfb-run -a} with
 * {@code -Dscratch4j.uismoke=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class LayoutIT {

  @TempDir
  Path tmp;

  static void startFx() {
    try {
      Platform.startup(() -> { });
    } catch (IllegalStateException alreadyRunning) {
      // another test started the toolkit
    }
    Platform.setImplicitExit(false);
  }

  static void onFx(ThrowingRunnable action) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        action.run();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        done.countDown();
      }
    });
    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
  }

  interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** Lets layout and deferred work (runLater) settle. */
  static void settle() throws Exception {
    for (int i = 0; i < 5; i++) {
      onFx(() -> { });
      Thread.sleep(150);
    }
  }

  @Test
  void smallWindowKeepsHeaderAndStagePreviewInside() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    Path tiled = BundledTemplates.create("demo-tiled", tmp, "tiled",
        org.openpatch.scratch4j.core.project.NewProject.classpathJar(
            org.openpatch.scratch.internal.BuiltinAssets.class));
    startFx();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<javafx.stage.Stage> window = new AtomicReference<>();
    onFx(() -> {
      StudioApp studio = new StudioApp();
      javafx.stage.Stage stage = new javafx.stage.Stage();
      studio.start(stage);
      stage.setWidth(900);
      stage.setHeight(600);
      // the start stage opens with its designer beside the code
      studio.openProjectAt(tiled);
      app.set(studio);
      window.set(stage);
    });
    settle();
    onFx(() -> {
      var scene = window.get().getScene();
      UiSmokeIT.snapshot(scene.getRoot(), Path.of("target/layout-small.png"));
      double width = scene.getWidth();
      // the slim header fits 900px spelled out, so the menus need not fold here
      // every header control is inside the window
      Node header = scene.getRoot().lookup(".header");
      for (Node child : ((Parent) header).getChildrenUnmodifiable()) {
        if (!child.isVisible()) continue;
        var bounds = child.localToScene(child.getBoundsInLocal());
        assertThat(bounds.getMaxX()).as("header item %s", child).isLessThanOrEqualTo(width + 1);
      }
      // the stage preview fits into its pane
      StageDesignerView designer = (StageDesignerView) scene.getRoot()
          .lookupAll(".designer").stream().findFirst().orElse(null);
      assertThat(designer).as("designer panel beside World.java").isNotNull();
      var canvas = designer.canvas().localToScene(designer.canvas().getBoundsInLocal());
      assertThat(canvas.getMaxX()).isLessThanOrEqualTo(width + 1);
      assertThat(designer.shownMap()).isNotNull();
      // designer and code beside it follow each other
      CodeEditor code = null;
      for (javafx.scene.Node n = designer.getParent(); n != null; n = n.getParent()) {
        if (n instanceof CodeEditor c) {
          code = c;
          break;
        }
      }
      assertThat(code).as("the designer sits in World.java's tab").isNotNull();
      designer.selectFromCodeLine("inventory = new UIItem();");
      assertThat(code.linkedLines()).as("inventory's lines light up").isNotEmpty();
      int line = code.content().lines().toList().indexOf("    inventory = new UIItem();") + 1;
      assertThat(code.linkedLines()).contains(line);
      // one history: a sprite added in the designer is undone through the code's history
      int sprites = designer.model().sprites().size();
      String before = code.content();
      designer.addInstance("Player");
      assertThat(designer.model().sprites()).hasSize(sprites + 1);
      assertThat(code.content()).as("the code beside shows it").isNotEqualTo(before);
      designer.undo();
      assertThat(code.content()).isEqualTo(before);
      assertThat(designer.model().sprites()).hasSize(sprites);
      designer.redo();
      assertThat(designer.model().sprites()).hasSize(sprites + 1);
      designer.undo();
    });
    onFx(() -> window.get().setWidth(1360));
    onFx(() -> window.get().setHeight(840));
    settle();
    onFx(() -> UiSmokeIT.snapshot(window.get().getScene().getRoot(),
        Path.of("target/layout-split.png")));
    // suggestions while typing; Enter takes one, but not a word that is already complete
    AtomicReference<CodeEditor> worldCode = new AtomicReference<>();
    onFx(() -> {
      for (var n : window.get().getScene().getRoot().lookupAll(".code-editor")) {
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
          if (p instanceof CodeEditor c && c.file().getFileName().toString().equals("World.java")) {
            worldCode.set(c);
          }
        }
      }
      var area = worldCode.get().area();

      int at = worldCode.get().content().indexOf("    GameState.get().map = mapFile;");
      area.insertText(at, "    this.addB\n");
      area.moveTo(at + "    this.addB".length());
      area.requestFocus();
      area.requestFollowCaret();
    });
    settle();
    // real keys (the robot): "a" completes "addBa" to the suggestions, Enter takes one
    onFx(() -> {
      window.get().toFront();
      window.get().requestFocus();
      worldCode.get().area().requestFocus();
      new javafx.scene.robot.Robot().keyType(javafx.scene.input.KeyCode.A);
    });
    settle();
    onFx(() -> {
      assertThat(worldCode.get().completionShowing()).as("suggestions while typing").isTrue();
      new javafx.scene.robot.Robot().keyType(javafx.scene.input.KeyCode.ENTER);
    });
    settle();
    onFx(() -> {
      String line = worldCode.get().caretLineText();
      assertThat(line).as("Enter took the suggestion").startsWith("    this.addBackdrop(");
      assertThat(worldCode.get().completionShowing()).isFalse();
      worldCode.get().undo();
      worldCode.get().undo();
      worldCode.get().undo();
    });

    // a colour swatch in the gutter: its picker stays open while the line changes
    onFx(() -> {
      var area = worldCode.get().area();
      int at = worldCode.get().content().indexOf("    GameState.get().map = mapFile;");
      area.insertText(at, "    this.setTint(0);\n");
      area.moveTo(at);
      area.requestFollowCaret();
    });
    settle();
    AtomicReference<javafx.stage.Popup> colourPopup = new AtomicReference<>();
    onFx(() -> {
      var swatch = worldCode.get().area().lookupAll(".code-preview").stream()
          .filter(n -> n instanceof javafx.scene.control.Label l
              && l.getGraphic() instanceof javafx.scene.shape.Rectangle)
          .findFirst().orElseThrow();
      swatch.fireEvent(new javafx.scene.input.MouseEvent(
          javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
          javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false,
          false, false, false, false, null));
      colourPopup.set(javafx.stage.Window.getWindows().stream()
          .filter(w -> w instanceof javafx.stage.Popup p && p.isShowing()
              && !p.getContent().isEmpty()
              && !p.getContent().get(0).lookupAll(".slider").isEmpty())
          .map(w -> (javafx.stage.Popup) w).findFirst().orElseThrow());
      var hue = (javafx.scene.control.Slider)
          colourPopup.get().getContent().get(0).lookupAll(".slider").iterator().next();
      hue.setValue(hue.getMax() / 3);
      assertThat(hue.getCursor()).isEqualTo(javafx.scene.Cursor.HAND);
      assertThat(colourPopup.get().getContent().get(0).getCursor())
          .isEqualTo(javafx.scene.Cursor.DEFAULT);
    });
    settle();
    onFx(() -> {
      // nothing written while picking: the popup stays open and the line unchanged
      assertThat(worldCode.get().content()).contains("this.setTint(0);");
      var hue = (javafx.scene.control.Slider)
          colourPopup.get().getContent().get(0).lookupAll(".slider").iterator().next();
      hue.setValue(hue.getMax() / 2);
    });
    settle();
    onFx(() -> {
      assertThat(colourPopup.get().isShowing()).as("picker stays open").isTrue();
      // Enter writes the colour: one change, one undo step
      colourPopup.get().getContent().get(0).fireEvent(new javafx.scene.input.KeyEvent(
          javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER,
          false, false, false, false));
    });
    settle();
    onFx(() -> {
      assertThat(colourPopup.get().isShowing()).isFalse();
      assertThat(worldCode.get().content()).doesNotContain("this.setTint(0);")
          .contains("this.setTint(128);");
      worldCode.get().undo();
      assertThat(worldCode.get().content()).contains("this.setTint(0);");
      worldCode.get().undo();
      assertThat(worldCode.get().content()).doesNotContain("this.setTint(");
    });

    // the Visual button closes the panel and opens it again
    onFx(() -> app.get().openVisualForFile(tiled.resolve("World.java")));
    onFx(() -> assertThat(window.get().getScene().getRoot().lookupAll(".designer")).isEmpty());
    onFx(() -> app.get().openVisualForFile(tiled.resolve("World.java")));
    onFx(() -> window.get().setHeight(840));
    settle();
    onFx(() -> {
      UiSmokeIT.snapshot(window.get().getScene().getRoot(), Path.of("target/layout-large.png"));
      assertThat(window.get().getScene().getRoot().lookup(".compact-menu").isVisible())
          .as("menus spelled out again").isFalse();
    });
    // a hand-written sprite in a stage: the light bulb in its code makes it the designer's
    java.nio.file.Files.writeString(tiled.resolve("Pond.java"), """
        import org.openpatch.scratch.*;

        public class Pond extends Stage {
          // scratch4j:begin fields (managed by the stage designer)
          // scratch4j:end fields

          public Pond() {
            super(480, 360);
            // scratch4j:begin setup (managed by the stage designer)
            this.addBackdrop("background");
            // scratch4j:end setup
            var host = new Sprite();
            host.addCostume("alienGreen_stand");
            host.setY(-20);
            this.add(host);
          }
        }
        """);
    onFx(() -> {
      app.get().openVisualForFile(tiled.resolve("Pond.java"));
      app.get().recheckForTests();
    });
    CodeEditor[] pondCode = {null};
    for (int i = 0; i < 40; i++) {
      Thread.sleep(250);
      onFx(() -> {
        for (var n : window.get().getScene().getRoot().lookupAll(".code-editor")) {
          for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
            if (p instanceof CodeEditor c && c.file().getFileName().toString().equals("Pond.java")) {
              pondCode[0] = c;
            }
          }
        }
      });
      if (pondCode[0] != null && !pondCode[0].fixes().isEmpty()) break;
    }
    assertThat(pondCode[0].fixes()).containsKey(12);
    onFx(() -> app.get().promoteSprite(tiled.resolve("Pond.java"), 12));
    assertThat(java.nio.file.Files.readString(tiled.resolve("Pond.java")))
        .contains("  private Sprite host;").contains("    host.setPosition(0, -20);");

    // a version after a "run", then a change: the versions dialog shows the diff
    org.openpatch.scratch4j.core.io.ProjectSnapshots.keep(tiled,
        org.openpatch.scratch4j.core.io.ProjectSnapshots.Kind.RAN);
    java.nio.file.Files.writeString(tiled.resolve("World.java"),
        java.nio.file.Files.readString(tiled.resolve("World.java"))
            .replace("this.setColor(0, 0, 0);", "this.setColor(255, 0, 0);"));
    AtomicReference<javafx.scene.control.Dialog<?>> versions = new AtomicReference<>();
    onFx(() -> {
      var dialog = VersionsDialog.build(tiled, () -> { });
      dialog.show();
      versions.set(dialog);
    });
    settle();
    onFx(() -> {
      UiSmokeIT.snapshot(versions.get().getDialogPane(), Path.of("target/versions.png"));
      versions.get().close();
    });
    // the class diagram of the tiled demo, and an object diagram
    onFx(() -> {
      DiagramView diagrams = new DiagramView(
          org.openpatch.scratch4j.core.project.ScratchProject.open(tiled), () -> null, f -> { });
      javafx.scene.Scene scene = new javafx.scene.Scene(diagrams, 1300, 800);
      Theme.apply(scene);
      diagrams.applyCss();
      diagrams.layout();
      assertThat(diagrams.canvas().placed()).containsKeys("World", "Player", "Stage");
      // Stage above World (inheritance goes up)
      assertThat(diagrams.canvas().placed().get("Stage")[1])
          .isLessThan(diagrams.canvas().placed().get("World")[1]);
      UiSmokeIT.snapshot(diagrams.canvas(), Path.of("target/class-diagram.png"));
      diagrams.showObjects(List.of(
          new org.openpatch.scratch4j.runner.Debugger.ObjectNode(1, "World",
              List.of(new org.openpatch.scratch4j.runner.Debugger.Variable("items", "List",
                  "[2]")),
              List.of(new org.openpatch.scratch4j.runner.Debugger.Reference("player", 2),
                  new org.openpatch.scratch4j.runner.Debugger.Reference("walls[0]", 3))),
          new org.openpatch.scratch4j.runner.Debugger.ObjectNode(2, "Player",
              List.of(new org.openpatch.scratch4j.runner.Debugger.Variable("speed", "int",
                  "3")), List.of()),
          new org.openpatch.scratch4j.runner.Debugger.ObjectNode(3, "Wall", List.of(),
              List.of())), null);
      UiSmokeIT.snapshot(diagrams.canvas(), Path.of("target/object-diagram.png"));
      diagrams.writePng(tmp.resolve("objects.png"));
      assertThat(tmp.resolve("objects.png")).exists();
    });
    // the sprite editor: previews of animations (sheet in columns), costumes and sounds
    String sound = org.openpatch.scratch4j.core.assets.BuiltinAssetIndex.get().sounds().get(0);
    java.nio.file.Files.writeString(tiled.resolve("Cat.java"), """
        import org.openpatch.scratch.*;

        public class Cat extends Sprite {
          public Cat() {
            // scratch4j:begin setup
            this.addCostume("bunny1_stand");
            this.addSound("%s");
            // scratch4j:end setup
          }
        }
        """.formatted(sound));
    onFx(() -> {
      var project = org.openpatch.scratch4j.core.project.ScratchProject.open(tiled);
      SpriteAssetsView player = new SpriteAssetsView(project, "Player", () -> { });
      javafx.scene.Scene scene = new javafx.scene.Scene(player, 1100, 640);
      Theme.apply(scene);
      var animations = player.list(org.openpatch.scratch4j.core.region.SpriteAssets.Kind.ANIMATION);
      assertThat(animations.getItems()).hasSize(4);
      animations.getSelectionModel().select(2);
      assertThat(player.preview(org.openpatch.scratch4j.core.region.SpriteAssets.Kind.ANIMATION)
          .frameCount()).isEqualTo(4);
      ((javafx.scene.control.TabPane) player.lookup(".tab-pane")).getSelectionModel().select(2);
      player.applyCss();
      player.layout();
      UiSmokeIT.snapshot(player, Path.of("target/sprite-animations.png"));
      // the hitbox is drawn over the costume the sprite starts with, not the whole sheet
      assertThat(player.hitboxCostume()).isEqualTo("assets/Skeleton.png#0,0,32,32");
      ((javafx.scene.control.TabPane) player.lookup(".tab-pane")).getSelectionModel().select(3);
      player.applyCss();
      player.layout();
      UiSmokeIT.snapshot(player, Path.of("target/sprite-hitbox.png"));

      // the sprite-sheet grid: walk-left is column 2, four frames
      var walkLeft = animations.getItems().get(2);
      var sheet = CostumeView.costume("assets/Skeleton.png", tiled);
      SheetGridDialogAccess access = new SheetGridDialogAccess(sheet, walkLeft);
      assertThat(access.statements()).containsExactly(
          "this.addAnimation(\"walk-left\", \"assets/Skeleton.png\", 4, 32, 32, 2, true);");
      access.clickCell(1, 2);
      assertThat(access.statements()).containsExactly(
          "this.addAnimation(\"walk-left\", \"assets/Skeleton.png\", 3, 32, 32, 1, true);");
      access.snapshot(Path.of("target/sheet-grid.png"));
      SheetGridDialogAccess costumes = new SheetGridDialogAccess(sheet, null);
      costumes.clickCell(0, 0);
      costumes.clickCell(3, 1);
      assertThat(costumes.statements()).containsExactly(
          "this.addCostume(\"Skeleton\", \"assets/Skeleton.png\", 0, 0, 32, 32);",
          "this.addCostume(\"Skeleton2\", \"assets/Skeleton.png\", 96, 32, 32, 32);");

      // Bamboo extends Enemy extends AnimatedSprite: a sprite with its own visual editor
      assertThat(VisualMode.isSpriteSource(tiled.resolve("Bamboo.java"))).isTrue();
      SpriteAssetsView bamboo = new SpriteAssetsView(project, "Bamboo", () -> { });
      assertThat(bamboo.list(org.openpatch.scratch4j.core.region.SpriteAssets.Kind.ANIMATION)
          .getItems()).hasSize(4).allMatch(e -> e.managed());
      SpriteAssetsView cat = new SpriteAssetsView(project, "Cat", () -> { });
      javafx.scene.Scene catScene = new javafx.scene.Scene(cat, 1100, 640);
      Theme.apply(catScene);
      var sounds = cat.list(org.openpatch.scratch4j.core.region.SpriteAssets.Kind.SOUND);
      sounds.getSelectionModel().select(0);
      assertThat(cat.preview(org.openpatch.scratch4j.core.region.SpriteAssets.Kind.SOUND).clip())
          .isNotNull();
      ((javafx.scene.control.TabPane) cat.lookup(".tab-pane")).getSelectionModel().select(1);
      cat.applyCss();
      cat.layout();
      UiSmokeIT.snapshot(cat, Path.of("target/sprite-sounds.png"));
      cat.list(org.openpatch.scratch4j.core.region.SpriteAssets.Kind.COSTUME)
          .getSelectionModel().select(0);
      ((javafx.scene.control.TabPane) cat.lookup(".tab-pane")).getSelectionModel().select(0);
      cat.applyCss();
      cat.layout();
      UiSmokeIT.snapshot(cat, Path.of("target/sprite-costumes.png"));
    });
    onFx(() -> window.get().hide());
  }
}
