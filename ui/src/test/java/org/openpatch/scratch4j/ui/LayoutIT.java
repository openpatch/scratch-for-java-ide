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
import org.openpatch.scratch4j.core.project.LibraryJarSource;

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
    StudioApp.prepareGraphics();
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

  /** Presses the primary button at one point of a node, drags to another and lets go. */
  static void drag(Node node, double x1, double y1, double x2, double y2) {
    drag(node, x1, y1, x2, y2, false);
  }

  /** {@link #drag} with Shift held. */
  static void shiftDrag(Node node, double x1, double y1, double x2, double y2) {
    drag(node, x1, y1, x2, y2, true);
  }

  private static void drag(Node node, double x1, double y1, double x2, double y2,
      boolean shift) {
    mouse(node, javafx.scene.input.MouseEvent.MOUSE_PRESSED, x1, y1, true, shift);
    mouse(node, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, (x1 + x2) / 2, (y1 + y2) / 2, true,
        shift);
    mouse(node, javafx.scene.input.MouseEvent.MOUSE_DRAGGED, x2, y2, true, shift);
    mouse(node, javafx.scene.input.MouseEvent.MOUSE_RELEASED, x2, y2, false, shift);
  }

  /** A primary-button click in the middle of a node. */
  static void click(Node node) {
    var bounds = node.getLayoutBounds();
    mouse(node, javafx.scene.input.MouseEvent.MOUSE_CLICKED, bounds.getCenterX(),
        bounds.getCenterY(), false, false);
  }

  private static void mouse(Node node,
      javafx.event.EventType<javafx.scene.input.MouseEvent> type, double x, double y,
      boolean down, boolean shift) {
    var scene = node.localToScene(x, y);
    javafx.event.Event.fireEvent(node, new javafx.scene.input.MouseEvent(type, scene.getX(),
        scene.getY(), 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1, shift, false, false,
        false, down, false, false, false, false,
        type == javafx.scene.input.MouseEvent.MOUSE_PRESSED, null));
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
        LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled")));
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
      assertThat(window.get().getStyle()).isEqualTo(
          javafx.application.Platform.isSupported(javafx.application.ConditionalFeature.EXTENDED_WINDOW)
              ? javafx.stage.StageStyle.EXTENDED : javafx.stage.StageStyle.UNDECORATED);
      for (String id : List.of("window-minimize", "window-maximize", "window-close")) {
        Node control = header.lookup("#" + id);
        assertThat(control).as("%s remains available in the small window", id).isNotNull();
        var bounds = control.localToScene(control.getBoundsInLocal());
        assertThat(bounds.getMinX()).isGreaterThanOrEqualTo(0);
        assertThat(bounds.getMaxX()).isLessThanOrEqualTo(width + 1);
      }
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
      designer.addInstance("Player", List.of("0", "0"));
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

    // the view switch at the end of the tab strip: the code with its blocks, both, the stage
    onFx(() -> {
      Path world = tiled.resolve("World.java");
      CodeEditor code = worldCode.get();
      var root = window.get().getScene().getRoot();
      ViewSwitch views = (ViewSwitch) root.lookup(".view-switch-bar");
      assertThat(views.file()).isEqualTo(world);
      assertThat(views.view()).isEqualTo(ViewSwitch.View.SPLIT);
      // beside the stage the blocks are folded to their categories
      assertThat(code.dockedPalette()).isInstanceOf(BlockPalette.class);
      assertThat(((BlockPalette) code.dockedPalette()).isOpen()).isFalse();
      views.choose(ViewSwitch.View.CODE);
      assertThat(code.sidePanel()).isNull();
      assertThat(root.lookupAll(".designer")).isEmpty();
      BlockPalette blocks = (BlockPalette) code.dockedPalette();
      assertThat(blocks).as("the blocks left of the code").isNotNull();
      assertThat(blocks.isOpen()).as("folded to the categories at first").isFalse();
      assertThat(blocks.shownBlocks()).noneMatch(m -> m.className().equals("Sprite"));
      // a category on the rail opens the blocks there: one list under category headings
      assertThat(blocks.categoryItems()).contains("Looks");
      blocks.lookupAll(".category-button").stream()
          .map(n -> (javafx.scene.control.ToggleButton) n)
          .filter(b -> "Looks".equals(b.getUserData())).findFirst().orElseThrow().fire();
      assertThat(blocks.isOpen()).isTrue();
      assertThat(blocks.currentCategory()).isEqualTo("Looks");
      assertThat(Prefs.blocksOpen(false)).as("open stays the choice").isTrue();
      // only the view shown is named, the others are icons
      assertThat(views.lookupAll(".view-segment").stream()
          .map(n -> ((javafx.scene.control.ToggleButton) n).getText())
          .filter(t -> t != null)).containsExactly(I18n.t("mode.code"));
      views.choose(ViewSwitch.View.VISUAL);
      assertThat(code.visualOnly()).isTrue();
      assertThat(views.view()).isEqualTo(ViewSwitch.View.VISUAL);
      assertThat(root.lookupAll(".designer")).hasSize(1);
      assertThat(code.dockedPalette()).as("no code to drop blocks into").isNull();
      // going to a line (a problem, a search hit) shows the code again
      ((EditorTabs) root.lookup(".editor-tabs")).openAt(world, 3);
      assertThat(code.visualOnly()).isFalse();
      assertThat(views.view()).isEqualTo(ViewSwitch.View.SPLIT);
      assertThat(code.sidePanel()).isInstanceOf(StageDesignerView.class);
      // the visual editor alone stays the choice for the next one opened: back to both
      assertThat(Prefs.visualOnly()).isTrue();
      app.get().showView(world, ViewSwitch.View.SPLIT);
      assertThat(Prefs.visualOnly()).isFalse();
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
          // begin stage designer
          // end stage designer

          public Pond() {
            super(480, 360);
            // begin stage designer
            this.addBackdrop("background");
            // end stage designer
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

      // Player dragged aside stays there, also in a new view, until arranged again;
      // the view itself does not scroll along
      UmlCanvas classes = diagrams.canvas();
      var scroll = (javafx.scene.control.ScrollPane) diagrams.lookup(".paint-scroll");
      // dragging the free space above the boxes pans
      drag(classes, 400, 5, 300, 5);
      assertThat(scroll.getHvalue()).isGreaterThan(0);
      assertThat(classes.arrangedByHand()).isFalse();
      // from the middle, so a pan either way would show
      scroll.setHvalue(0.5);
      double scrolled = scroll.getHvalue();
      double[] player = classes.placed().get("Player").clone();
      drag(classes, player[0] + 20, player[1] + 10, player[0] + 170, player[1] + 50);
      assertThat(classes.placed().get("Player")[0]).isEqualTo(player[0] + 150);
      assertThat(classes.placed().get("Player")[1]).isEqualTo(player[1] + 40);
      assertThat(scroll.getHvalue()).isEqualTo(scrolled);
      assertThat(classes.placed().get("World")).startsWith(classes.pinned().get("World"));
      assertThat(tiled.resolve(".scratch4j/diagram.json")).exists();
      DiagramView reopened = new DiagramView(
          org.openpatch.scratch4j.core.project.ScratchProject.open(tiled), () -> null, f -> { });
      assertThat(reopened.canvas().placed().get("Player")[0])
          .isEqualTo((double) Math.round(player[0] + 150));
      UiSmokeIT.snapshot(classes, Path.of("target/class-diagram-moved.png"));
      classes.arrangeAutomatically();
      assertThat(classes.placed().get("Player")).startsWith(player[0], player[1]);
      assertThat(tiled.resolve(".scratch4j/diagram.json")).doesNotExist();
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

      // forty coins nobody references: one layer, wrapped at the visible width
      List<org.openpatch.scratch4j.runner.Debugger.ObjectNode> coins =
          new java.util.ArrayList<>();
      for (long id = 10; id < 50; id++) {
        coins.add(new org.openpatch.scratch4j.runner.Debugger.ObjectNode(id, "Coin",
            List.of(new org.openpatch.scratch4j.runner.Debugger.Variable("row", "int",
                String.valueOf(id))), List.of()));
      }
      diagrams.canvas().setWrapWidth(1000);
      diagrams.showObjects(coins, null);
      double right = diagrams.canvas().placed().values().stream()
          .mapToDouble(r -> r[0] + r[2]).max().orElse(0);
      long lines = diagrams.canvas().placed().values().stream().mapToDouble(r -> r[1])
          .distinct().count();
      assertThat(right).isLessThanOrEqualTo(1000);
      assertThat(lines).isGreaterThan(1);

      // a world holding one of them comes first in its layer, before the loose coins
      coins.add(new org.openpatch.scratch4j.runner.Debugger.ObjectNode(99, "World", List.of(),
          List.of(new org.openpatch.scratch4j.runner.Debugger.Reference("grid[1][7]", 10))));
      diagrams.showObjects(coins, null);
      double[] world = diagrams.canvas().placed().get("99");
      assertThat(world[0]).isEqualTo(20);
      assertThat(world[1]).isEqualTo(20);
      UiSmokeIT.snapshot(diagrams.canvas(), Path.of("target/object-diagram-wrapped.png"));
    });
    // the sprite editor: previews of animations (sheet in columns), costumes and sounds
    String sound = org.openpatch.scratch4j.core.assets.BuiltinAssetIndex.get().sounds().get(0);
    java.nio.file.Files.writeString(tiled.resolve("Cat.java"), """
        import org.openpatch.scratch.*;

        public class Cat extends Sprite {
          public Cat() {
            // begin sprite editor
            this.addCostume("bunny1_stand");
            this.addSound("%s");
            // end sprite editor
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
