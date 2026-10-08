package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime smoke test of the shell's building blocks (needs a display —
 * {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class UiSmokeIT {

  @TempDir
  Path tmp;

  @Test
  void editorTreeAndWizardFilesBuildAndEditOnTheFxThread() throws Exception {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "uismoke",
        NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class));
    Path stage = tmp.resolve("uismoke/MyStage.java");
    // the Files tree hides empty asset folders: give images something to show
    java.nio.file.Files.writeString(tmp.resolve("uismoke/assets/images/cat.png"), "");

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    LayoutIT.startFx();
    javafx.application.Platform.runLater(() -> {
      try {
        FileTreeView tree = new FileTreeView();
        tree.setRoot(tmp.resolve("uismoke"));
        tree.setOnRenameAsset(file -> {});
        AtomicReference<Path> deletion = new AtomicReference<>();
        tree.setOnDeleteFile(deletion::set);
        tree.setOnEditSprite(file -> {});
        AtomicReference<Path> folderDeletion = new AtomicReference<>();
        tree.setOnDeleteFolder(folderDeletion::set);
        tree.setOnNewFolder(file -> {});
        tree.setOnRenameFolder(file -> {});
        assertThat(VisualMode.isSpriteSource(tmp.resolve("uismoke/Player.java"))).isTrue();
        tree.getSelectionModel().select(tree.getRoot().getChildren().stream()
            .filter(item -> item.getValue().getFileName().toString().equals("Player.java"))
            .findFirst().orElseThrow());
        tree.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", javafx.scene.input.KeyCode.DELETE, false, false, false, false));
        assertThat(deletion.get()).isEqualTo(tmp.resolve("uismoke/Player.java"));
        Path images = tmp.resolve("uismoke/assets/images");
        tree.selectPath(images);
        assertThat(tree.folderForCreation()).isEqualTo(images);
        tree.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
            "", "", javafx.scene.input.KeyCode.DELETE, false, false, false, false));
        assertThat(folderDeletion.get()).isEqualTo(images);

        var apiIndex = org.openpatch.scratch4j.core.api.ApiIndex.load();
        EditorTabs editor = new EditorTabs(apiIndex, () -> tmp.resolve("uismoke"));
        AtomicReference<Path> visualMode = new AtomicReference<>();
        editor.setOnVisualMode(visualMode::set);
        editor.open(stage);
        assertThat(editor.tabs()).hasSize(1);

        CodeEditor area = (CodeEditor) editor.tabs().get(0).getContent();
        // code -> visual: a stage's code tab carries the "Visual" button
        // it floats over the code (no extra row above it)
        assertThat(area.getTop()).isInstanceOf(CodeEditor.FindBar.class);
        area.visualModeButton().fire();
        area.breakpoints().add(5);
        assertThat(editor.breakpoints().get(stage)).containsExactly(5);
        javafx.scene.Scene codeScene = new javafx.scene.Scene(area, 760, 300);
        Theme.apply(codeScene);
        area.applyCss();
        area.layout();
        snapshot(area, Path.of("target/code-floating-visual.png"));
        assertThat(visualMode.get()).isEqualTo(stage);
        area.area().appendText("  // edited by smoke test\n  this.addCo");
        area.area().moveTo(area.area().getLength());
        editor.saveAll();
        assertThat(java.nio.file.Files.readString(stage)).contains("edited by smoke test");

        // completion over the identifier prefix "addCo"
        assertThat(area.completionCandidates())
            .contains("addCostume", "addCostumes");

        // block palette: categories populated, first entry carries a scratch block
        var inserted = new java.util.ArrayList<org.openpatch.scratch4j.core.api.ApiMethod>();
        BlockPalette palette = new BlockPalette(apiIndex, inserted::add);
        assertThat(palette.categoryItems()).isNotEmpty();
        palette.selectFirst();
        org.openpatch.scratch4j.core.api.ApiMethod first = palette.selectedItem();
        assertThat(first).isNotNull();
        area.insertBlock(BlockPalette.snippet(first));
        assertThat(area.content()).contains(BlockPalette.snippet(first).strip());

        // type-aware completion: Stage members after "this.", with javadoc details
        int runBody = area.content().indexOf("public void run() {") + "public void run() {".length();
        area.area().insertText(runBody, "\n    this.addBa");
        area.area().moveTo(runBody + "\n    this.addBa".length());
        var semantic = area.semanticCompletionsNow();
        assertThat(semantic).extracting(CodeEditor.Completion::text).contains("addBackdrop");
        assertThat(semantic).extracting(CodeEditor.Completion::text).doesNotContain("addCostume");
        var addBackdrop = semantic.stream().filter(c -> c.text().equals("addBackdrop"))
            .findFirst().orElseThrow();
        assertThat(addBackdrop.detail()).contains("Stage").contains("\u2022 ");
        assertThat(addBackdrop.docsUrl()).contains("scratch4j.openpatch.org/reference/Stage");
        area.area().replaceText(runBody, runBody + "\n    this.addBa".length(), "");
        area.area().moveTo(area.area().getLength());

        // asset-string completion inside addCostume("...")
        area.area().appendText("\n    this.addCostume(\"bunny1_");
        area.area().moveTo(area.area().getLength());
        assertThat(area.completionCandidates()).contains("bunny1_stand");

        // stage designer: model round-trips through the regions
        var project = org.openpatch.scratch4j.core.project.ScratchProject.open(
            tmp.resolve("uismoke"));
        StageDesignerView designer = new StageDesignerView(project, "MyStage", f -> {});
        assertThat(designer.isReadOnly()).isFalse();
        int spritesBefore = designer.model().sprites().size();
        designer.addInstance("Player");
        assertThat(designer.model().sprites()).hasSize(spritesBefore + 1);
        String stageNow = Files.readString(stage);
        assertThat(stageNow).contains("player2 = new Player();");
        assertThat(stageNow).contains("Player player2;");
        // round-trip: reload from the written file keeps the model
        designer.reload();
        assertThat(designer.model().sprites()).hasSize(spritesBefore + 1);

        // UISprite with nine-slice and pixel size, and a Text box on the stage
        Files.writeString(tmp.resolve("uismoke/Button.java"), """
            import org.openpatch.scratch.UISprite;

            public class Button extends UISprite {
              public Button() {
                this.addCostume("buttonLarge");
                this.setNineSlice(12, 12, 12, 12);
              }
            }
            """);
        designer.addInstance("Button");
        var stageDoc = org.openpatch.scratch4j.core.region.StageDocument.read(
            Files.readString(stage));
        var button = stageDoc.model().sprites().byName("button");
        button.setPosition(0, -120);
        button.width(260);
        button.height(60);
        String withText = stageDoc.write().replace("import org.openpatch.scratch.Stage;",
            "import org.openpatch.scratch.Stage;\nimport org.openpatch.scratch.Text;");
        Files.writeString(stage, withText);
        designer.reload();
        designer.addText();
        var text = designer.model().sprites().byName("text");
        assertThat(text).isNotNull();
        assertThat(Files.readString(stage)).containsPattern("text = new Text\\(\"[^\"]+\", -100, 0, 200\\);");
        designer.reload();
        button = designer.model().sprites().byName("button");
        assertThat(button.width()).isEqualTo(260);
        // a plain text starts at its x: a point right of x hits it, a point left of it does not
        double scale = designer.canvas().getWidth() / designer.model().width();
        double cx = designer.canvas().getWidth() / 2;
        double cy = designer.canvas().getHeight() / 2;
        assertThat(designer.objectAt(cx + (-100 + 20) * scale, cy)).isSameAs(
            designer.model().sprites().byName("text"));
        assertThat(designer.objectAt(cx + (-100 - 20) * scale, cy)).isNotSameAs(
            designer.model().sprites().byName("text"));
        snapshot(designer.canvas(), Path.of("target/designer-uisprite-text.png"));
        // code -> visual keeps what the caret was on
        assertThat(designer.selectFromCodeLine("    button.setWidth(260);")).isTrue();
        assertThat(designer.selectedName()).isEqualTo("button");
        assertThat(designer.selectFromCodeLine("    Text text;")).isTrue();
        assertThat(designer.selectedName()).isEqualTo("text");
        assertThat(designer.selectFromCodeLine("  public void run() {")).isFalse();

        // costume rendering: atlas crop for a normal and a pre-rotated entry
        assertThat(CostumeView.costume("bunny1_stand")).isNotNull();
        var rotated = org.openpatch.scratch4j.core.assets.BuiltinAssetIndex.get()
            .images().stream()
            .filter(m -> m.direction() != 90)
            .findFirst().orElseThrow();
        assertThat(CostumeView.costume(rotated.qualifiedName())).isNotNull();

        // asset library populates from the built-in registries
        AssetLibraryView library = new AssetLibraryView(project,
            (kind, ref) -> { }, file -> { });

        // sound editor opens a generated WAV
        Path wav = tmp.resolve("uismoke/assets/sounds/test.wav");
        float[][] channel = new float[1][4410];
        for (int i = 0; i < channel[0].length; i++) {
          channel[0][i] = (float) Math.sin(2 * Math.PI * 440 * i / 44100f);
        }
        org.openpatch.scratch4j.sound.SoundIO.writeWav(
            new org.openpatch.scratch4j.sound.SoundClip(channel, 44100), wav);
        SoundEditorView soundEditor = new SoundEditorView(project.root(), wav);
        assertThat(soundEditor.file()).isEqualTo(wav);

        // paint editor: select a red pixel, move it by (+4, +4), scale it 2x, crop
        Path dot = tmp.resolve("uismoke/assets/images/dot.png");
        var dotImage = new java.awt.image.BufferedImage(8, 8,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        dotImage.setRGB(1, 1, 0xffff0000);
        javax.imageio.ImageIO.write(dotImage, "png", dot.toFile());
        ImageEditorView paint = new ImageEditorView(project, dot);
        paint.useSelectTool();
        paint.selectPress(1.2, 1.2);
        paint.selectDrag(1.6, 1.6, false);
        paint.selectRelease();
        paint.selectPress(1.5, 1.5); // inside: lift and move
        paint.selectDrag(5.5, 5.5, false);
        paint.selectRelease();
        paint.selectPress(6.0, 6.0); // the bottom-right handle: scale to 2x2
        paint.selectDrag(7.0, 7.0, false);
        paint.selectRelease();
        paint.commitFloating();
        var pixels = paint.image().getPixelReader();
        assertThat(pixels.getArgb(1, 1) >>> 24).as("the old place is cleared").isZero();
        assertThat(pixels.getArgb(5, 5)).isEqualTo(0xffff0000);
        assertThat(pixels.getArgb(6, 6)).as("scaled to 2x2").isEqualTo(0xffff0000);
        paint.cropToSelection();
        assertThat(paint.image().getWidth()).isEqualTo(2);
        // layers: draw on a new layer, the flattened picture has both, merging keeps it
        paint.addLayer();
        assertThat(paint.layerCount()).isEqualTo(2);
        paint.image().getPixelWriter().setArgb(1, 1, 0xff0000ff);
        assertThat(paint.flattened().getPixelReader().getArgb(1, 1)).isEqualTo(0xff0000ff);
        assertThat(paint.flattened().getPixelReader().getArgb(0, 0)).isEqualTo(0xffff0000);
        paint.mergeDown();
        assertThat(paint.layerCount()).isEqualTo(1);
        assertThat(paint.image().getPixelReader().getArgb(1, 1)).isEqualTo(0xff0000ff);
        var tall = new javafx.scene.image.WritableImage(40, 40);
        for (int y = 0; y < 40; y++) {
          for (int x = 15; x < 25; x++) {
            tall.getPixelWriter().setArgb(x, y, 0xff000000);
          }
        }
        assertThat(ImageEditorView.looksFacingUp(tall)).isTrue();
        assertThat(ImageEditorView.looksFacingUp(paint.image())).isFalse();
        ScratchColorPicker picker = new ScratchColorPicker(javafx.scene.paint.Color.BLACK);
        javafx.scene.paint.Color orange = javafx.scene.paint.Color.hsb(36, 0.9, 1.0, 0.5);
        picker.setValue(orange);
        assertThat(picker.getValue().getHue()).isCloseTo(36, org.assertj.core.data.Offset.offset(0.5));
        assertThat(picker.getValue().getOpacity()).isCloseTo(0.5,
            org.assertj.core.data.Offset.offset(0.01));
        assertThat(SheetSlicerDialog.guessTile(128, 32)).containsExactly(32, 32);
        assertThat(SheetSlicerDialog.guessTile(96, 64)).containsExactly(32, 32);

        // animation editor: frames in assets/images, apply writes addAnimation + interval
        Path hero = tmp.resolve("uismoke/Hero.java");
        Files.writeString(hero, """
            import org.openpatch.scratch.AnimatedSprite;

            public class Hero extends AnimatedSprite {
              public Hero() {
                // scratch4j:begin setup
                // scratch4j:end setup
              }
            }
            """);
        AtomicReference<Path> written = new AtomicReference<>();
        AnimationEditorView animation = new AnimationEditorView(project, hero, "walk",
            "assets/images/walk%d.png", f -> { }, written::set);
        animation.addBlank();
        animation.addBlank();
        animation.apply();
        assertThat(written.get()).isEqualTo(hero);
        assertThat(Files.readString(hero))
            .contains("this.addAnimation(\"walk\", \"assets/images/walk%d.png\", 2);")
            .contains("this.setAnimationInterval(120);");
        animation.stop();

        // map and font previews from the bundled demos
        Path tiled = org.openpatch.scratch4j.core.project.BundledTemplates.create("demo-tiled",
            tmp, "tiled", null);
        MapEditorView mapView = new MapEditorView(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled),
            tiled.resolve("Level1.tmx"), saved -> { }, () -> { });
        assertThat(mapView.canvas().getWidth()).isEqualTo(30 * 2 * 32);
        javafx.scene.Scene mapScene = new javafx.scene.Scene(mapView, 1300, 760);
        Theme.apply(mapScene);
        mapView.applyCss();
        mapView.layout();
        snapshot(mapView, Path.of("target/map-editor-view.png"));
        snapshot(mapView.canvas(), Path.of("target/map-editor.png"));
        // paint with the mouse: the picked tile from (0,0) goes to (2,0), undo, redo, save
        var mapDoc = mapView.document();
        var ground = (org.openpatch.scratch4j.core.tiled.TmxDocument.TileLayer) mapDoc.layers.get(0);
        long picked = mapDoc.tile(ground, 0, 0);
        mapDoc.setTile(ground, 2, 0, 0);
        double cell = 32 * 2;
        java.util.function.BiConsumer<javafx.event.EventType<javafx.scene.input.MouseEvent>,
            Double> mouse = (type, x) -> {
              var at = mapView.canvas().localToScene(x, 10);
              mapView.canvas().fireEvent(new javafx.scene.input.MouseEvent(type, at.getX(),
                  at.getY(), at.getX(), at.getY(),
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, false, false, false, null));
            };
        // opened at an object from the designer's double click
        var someWall = mapView.objectLayer("Objects").objects.stream()
            .filter(o -> "wall".equals(o.type)).findFirst().orElseThrow();
        mapView.selectObjectById(someWall.id);
        assertThat(mapView.selectedObject()).isSameAs(someWall);
        mapView.selectObject(null);
        mapView.chooseLayer("Floor");
        // the select tool picks a wall although the Floor tile layer is the active one
        assertThat(mapView.activeLayer().name).isEqualTo("Floor");
        mapView.useSelectTool();
        var wallAt = mapView.canvas().localToScene(30 * 2, 100 * 2);
        mapView.canvas().fireEvent(new javafx.scene.input.MouseEvent(
            javafx.scene.input.MouseEvent.MOUSE_PRESSED, wallAt.getX(), wallAt.getY(),
            wallAt.getX(), wallAt.getY(), javafx.scene.input.MouseButton.PRIMARY, 1, false,
            false, false, false, true, false, false, false, false, false, null));
        assertThat(mapView.selectedObject()).isNotNull();
        assertThat(mapView.selectedObject().type).isEqualTo("wall");
        assertThat(mapView.activeLayer().name).isEqualTo("Objects");
        // shortcuts from anywhere in the editor (here: the layer list has the key event)
        var layersNode = mapView.lookup(".list-view");
        java.util.function.BiConsumer<javafx.scene.input.KeyCode, Boolean[]> key = (code, mods) ->
            layersNode.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, mods[1], mods[0], false,
                false));
        Boolean[] ctrl = {true, false};
        Boolean[] ctrlShift = {true, true};
        Boolean[] plain = {false, false};
        var objectsLayer = mapView.objectLayer("Objects");
        int before = objectsLayer.objects.size();
        double wallX = mapView.selectedObject().x;
        key.accept(javafx.scene.input.KeyCode.C, ctrl);
        key.accept(javafx.scene.input.KeyCode.V, ctrl);
        assertThat(objectsLayer.objects).hasSize(before + 1);
        assertThat(mapView.selectedObject().type).isEqualTo("wall");
        key.accept(javafx.scene.input.KeyCode.Z, ctrl);
        assertThat(mapView.objectLayer("Objects").objects).as("undo").hasSize(before);
        key.accept(javafx.scene.input.KeyCode.Z, ctrlShift);
        assertThat(mapView.objectLayer("Objects").objects).as("redo").hasSize(before + 1);
        key.accept(javafx.scene.input.KeyCode.Z, ctrl);
        // select the wall again: arrows move it, Delete removes it
        mapView.canvas().fireEvent(new javafx.scene.input.MouseEvent(
            javafx.scene.input.MouseEvent.MOUSE_PRESSED, wallAt.getX(), wallAt.getY(),
            wallAt.getX(), wallAt.getY(), javafx.scene.input.MouseButton.PRIMARY, 1, false,
            false, false, false, true, false, false, false, false, false, null));
        // (in the layer list the arrows choose layers; on the map they move the object)
        for (var code : java.util.List.of(javafx.scene.input.KeyCode.RIGHT,
            javafx.scene.input.KeyCode.DELETE)) {
          if (code == javafx.scene.input.KeyCode.DELETE) {
            assertThat(mapView.selectedObject().x).isEqualTo(wallX + 1);
          }
          mapView.canvas().fireEvent(new javafx.scene.input.KeyEvent(
              javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        }
        assertThat(mapView.objectLayer("Objects").objects).hasSize(before - 1);
        key.accept(javafx.scene.input.KeyCode.Z, ctrl);
        key.accept(javafx.scene.input.KeyCode.Z, ctrl);
        assertThat(mapView.objectLayer("Objects").objects).hasSize(before);
        // a tile layer again: back to painting
        mapView.chooseLayer("Floor");
        assertThat(mapView.selectedObject()).isNull();
        mapView.useBrush(picked);
        mouse.accept(javafx.scene.input.MouseEvent.MOUSE_PRESSED, 2 * cell + 5);
        mouse.accept(javafx.scene.input.MouseEvent.MOUSE_RELEASED, 2 * cell + 5);
        assertThat(mapView.isDirty()).isTrue();
        mapView.undo();
        mapView.redo();
        mapView.save();
        var savedMap = org.openpatch.scratch4j.core.assets.TiledMapReader.read(
            tiled.resolve("Level1.tmx"));
        assertThat(picked).isNotZero();
        assertThat(savedMap.layers().get(0).gids()[2]).as("painted tile saved").isEqualTo(picked);
        // copy a 2x2 region into the brush, cut it, animate a tile, pan/zoom/minimap
        mapView.selectTiles(0, 0, 1, 1);
        mapView.copySelection(false);
        assertThat(mapView.brush()).hasDimensions(2, 2);
        assertThat(mapView.brush()[0][0]).isEqualTo(picked);
        mapView.copySelection(true);
        var afterCut = (org.openpatch.scratch4j.core.tiled.TmxDocument.TileLayer)
            mapView.document().layers.get(0);
        assertThat(mapView.document().tile(afterCut, 1, 1)).isZero();
        mapView.undo();
        var tileset = mapView.document().tilesets.get(0);
        mapView.useBrush(tileset.firstGid);
        javafx.scene.control.Button animate = (javafx.scene.control.Button) mapView.lookupAll(
            ".button").stream().filter(n -> n instanceof javafx.scene.control.Button b
                && I18n.t("map.animation.create").equals(b.getText())).findFirst().orElseThrow();
        animate.fire();
        assertThat(mapView.document().tilesets.get(0).animations).containsKey(0);
        mapView.drawMinimap();
        mapView.applyCss();
        mapView.layout();
        snapshot(mapView, Path.of("target/map-editor-animation.png"));
        mapView.save();
        assertThat(Files.readString(tiled.resolve("Level1.tmx"))).contains("<tile id=\"0\">");
        // completion of map paths and layer names
        CodeEditor worldCode = new CodeEditor(tiled.resolve("World.java"),
            "map.stampLayerToBackground(\"Fl", apiIndex, () -> tiled);
        worldCode.area().moveTo(worldCode.area().getLength());
        assertThat(worldCode.completionCandidates()).contains("Floor", "FloorObjects")
            .doesNotContain("Objects");
        worldCode.area().replaceText("var objects = map.getObjectsFromLayer(\"");
        worldCode.area().moveTo(worldCode.area().getLength());
        assertThat(worldCode.completionCandidates()).containsExactly("Objects");
        worldCode.area().replaceText("new TiledMap(\"Lev");
        worldCode.area().moveTo(worldCode.area().getLength());
        assertThat(worldCode.completionCandidates()).contains("Level1.tmx", "Level2.tmx");
        // the designer shows a stage's Tiled map through its camera
        Files.writeString(tiled.resolve("MapStage.java"), """
            import org.openpatch.scratch.*;
            import org.openpatch.scratch.extensions.tiled.TiledMap;

            public class MapStage extends Stage {
              // scratch4j:begin fields
              // scratch4j:end fields
              public MapStage() {
                super(480, 360);
                new TiledMap("Level1.tmx", this);
                this.getCamera().setPosition(240, -180);
                // scratch4j:begin setup
                // scratch4j:end setup
              }
            }
            """);
        StageDesignerView mapDesigner = new StageDesignerView(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled), "MapStage", f -> { });
        snapshot(mapDesigner.canvas(), Path.of("target/designer-map.png"));
        // the library's demo computes its map path: the designer still shows Level1
        StageDesignerView worldDesigner = new StageDesignerView(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled), "World", f -> { });
        assertThat(worldDesigner.isReadOnly()).isFalse();
        assertThat(worldDesigner.shownMap()).isEqualTo(tiled.resolve("Level1.tmx"));
        // the inventory is a UI sprite (setUI(true)): fixed to the screen, at its centre
        var inventoryRef = worldDesigner.model().sprites().byName("inventory");
        assertThat(inventoryRef).isNotNull();
        var tiledRendererForUi = new StageRenderer(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled));
        assertThat(tiledRendererForUi.isScreenFixed("UIItem")).isTrue();
        assertThat(tiledRendererForUi.isScreenFixed("Player")).isFalse();
        // as big as the Tiled window (640x360); the map's top left in the screen's top left
        assertThat(worldDesigner.model().width()).isEqualTo(640);
        var worldImage = worldDesigner.canvas().snapshot(null, null);
        var corner = worldImage.getPixelReader().getColor(3, 3);
        assertThat(corner.getRed()).as("a sand tile, not the empty stage").isGreaterThan(0.8);
        assertThat(corner.getBlue()).isLessThan(0.6);
        // the map's objects show in the designer: the spawn point, walls, warps...
        assertThat(worldDesigner.mapObjects()).extracting(o -> o.type)
            .contains("spawn-point", "wall", "warp");
        snapshot(worldDesigner.canvas(), Path.of("target/designer-world.png"));
        // a crash in the running program: explained once, at the student's line
        var crashesSeen = new java.util.ArrayList<org.openpatch.scratch4j.core.lint.RuntimeErrors.Explanation>();
        CrashWatcher watcher = new CrashWatcher(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled),
            org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.EN,
            crashesSeen::add);
        for (int frame = 0; frame < 2; frame++) {
          watcher.feed("java.lang.NullPointerException: Cannot invoke \"Player.getX()\" "
              + "because \"this.player\" is null");
          watcher.feed("\tat World.run(World.java:74)");
          watcher.feed("\tat org.openpatch.scratch.Stage.pre(Stage.java:1804)");
        }
        watcher.flush();
        assertThat(crashesSeen).hasSize(1);
        assertThat(crashesSeen.get(0).file()).isEqualTo(tiled.resolve("World.java"));
        assertThat(crashesSeen.get(0).title()).isEqualTo("player is null");
        // a version kept after a run that worked shows in the versions dialog's data
        org.openpatch.scratch4j.core.io.ProjectSnapshots.keep(tiled,
            org.openpatch.scratch4j.core.io.ProjectSnapshots.Kind.RAN);
        assertThat(org.openpatch.scratch4j.core.io.ProjectSnapshots.lastWorking(tiled))
            .isNotNull();
        // an AnimatedSprite with sheet animations in columns looks like its first frame
        StageRenderer tiledRenderer = new StageRenderer(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled));
        var playerLook = tiledRenderer.classCostume("Player");
        assertThat(playerLook).isNotNull();
        assertThat(playerLook.getWidth()).isEqualTo(32);
        assertThat(playerLook.getHeight()).isEqualTo(32);
        // a foreign line in the designer's region: read-only, with the line and the fix
        Files.writeString(tiled.resolve("Pond.java"), """
            import org.openpatch.scratch.*;

            public class Pond extends Stage {
              // scratch4j:begin fields (managed by the stage designer)
              // scratch4j:end fields

              public Pond() {
                super(480, 360);
                // scratch4j:begin setup (managed by the stage designer)
                this.addBackdrop("background");
                this.setTint(60);
                // scratch4j:end setup
              }
            }
            """);
        StageDesignerView pond = new StageDesignerView(
            org.openpatch.scratch4j.core.project.ScratchProject.open(tiled), "Pond", f -> { });
        assertThat(pond.isReadOnly()).isTrue();
        assertThat(pond.regionIssue().line()).isEqualTo(11);
        javafx.scene.Scene pondScene = new javafx.scene.Scene(pond, 900, 520);
        Theme.apply(pondScene);
        pond.applyCss();
        pond.layout();
        snapshot(pond, Path.of("target/designer-readonly.png"));
        // previews in the code's gutter: the frame an animation starts with, a sound, a map
        assertThat(CodePreviews.forLine(
            "    this.addAnimation(\"walk-down\", \"assets/Skeleton.png\", 4, 32, 32, 0, true);",
            tiled, f -> { })).isNotNull();
        assertThat(CodePreviews.forLine("    this.addSound(\"" + org.openpatch.scratch4j.core.assets
            .BuiltinAssetIndex.get().sounds().get(0) + "\");", tiled, f -> { })).isNotNull();
        assertThat(CodePreviews.forLine("map = new TiledMap(\"Level1.tmx\", this);", tiled,
            f -> { })).isNotNull();
        assertThat(CodePreviews.forLine("    int x = 3;", tiled, f -> { })).isNull();
        CodeEditor playerCode = new CodeEditor(tiled.resolve("Player.java"),
            Files.readString(tiled.resolve("Player.java")), apiIndex, () -> tiled);
        javafx.scene.Scene playerScene = new javafx.scene.Scene(playerCode, 900, 420);
        Theme.apply(playerScene);
        playerCode.applyCss();
        playerCode.layout();
        snapshot(playerCode, Path.of("target/code-previews.png"));
        // sprites the stage's code adds (loops over ground tiles and coins) as ghosts
        Path coins = org.openpatch.scratch4j.core.project.BundledTemplates.create(
            "demo-coinCollector", tmp, "coins", null);
        StageDesignerView coinDesigner = new StageDesignerView(
            org.openpatch.scratch4j.core.project.ScratchProject.open(coins), "CoinCollector",
            f -> { });
        assertThat(coinDesigner.ghosts()).extracting(g -> g.type()).contains("Ground", "Coin");
        javafx.scene.Scene coinScene = new javafx.scene.Scene(coinDesigner, 1000, 560);
        Theme.apply(coinScene);
        coinDesigner.applyCss();
        coinDesigner.layout();
        snapshot(coinDesigner.canvas(), Path.of("target/designer-ghosts.png"));
        // a sprite made in code: double click and right click lead to its class
        double[] coinAt = null;
        var coinCanvas = coinDesigner.canvas();
        for (double y = 2; y < coinCanvas.getHeight() && coinAt == null; y += 3) {
          for (double x = 2; x < coinCanvas.getWidth(); x += 3) {
            if ("Coin".equals(coinDesigner.classAt(x, y))) {
              coinAt = new double[] {x, y};
              break;
            }
          }
        }
        assertThat(coinAt).as("a coin on the canvas").isNotNull();
        List<String> opened = new ArrayList<>();
        coinDesigner.setOnOpenClass(opened::add);
        // an event's x/y are scene coordinates (the canvas sits in a padded holder)
        var inScene = coinCanvas.localToScene(coinAt[0], coinAt[1]);
        coinCanvas.fireEvent(new javafx.scene.input.MouseEvent(
            javafx.scene.input.MouseEvent.MOUSE_CLICKED, inScene.getX(), inScene.getY(), 0, 0,
            javafx.scene.input.MouseButton.PRIMARY, 2, false, false, false, false, true,
            false, false, true, false, false, null));
        assertThat(opened).containsExactly("Coin");
        var coinMenu = coinDesigner.contextMenuAt(coinAt[0], coinAt[1]);
        assertThat(coinMenu.getItems()).extracting(javafx.scene.control.MenuItem::getText)
            .anyMatch(t -> t != null && t.contains("Coin"));
        coinMenu.getItems().get(0).fire();
        assertThat(opened).containsExactly("Coin", "Coin");
        // the caret on the line that makes the coins outlines them
        int coinLine = coinDesigner.ghosts().stream().filter(g -> g.type().equals("Coin"))
            .findFirst().orElseThrow().line();
        String coinSource = Files.readString(coins.resolve("CoinCollector.java"));
        assertThat(coinDesigner.selectFromCode(coinSource.lines().toList().get(coinLine - 1),
            coinLine)).isTrue();
        assertThat(coinDesigner.highlightedGhostLine()).isEqualTo(coinLine);
        snapshot(coinDesigner.canvas(), Path.of("target/designer-ghost-line.png"));
        coinDesigner.selectFromCode("  public void run() {", coinLine + 1000);
        assertThat(coinDesigner.highlightedGhostLine()).isEqualTo(-1);
        // the code: Ctrl+hover underlines a name, the right-click menu goes to it
        String playerText = playerCode.content();
        int sprite = playerText.indexOf("extends ") + "extends ".length() + 2;
        String superclass = playerText.substring(playerText.indexOf("extends ") + 8)
            .split("[^\\w]")[0];
        playerCode.showLinkAtIndex(sprite);
        assertThat(playerCode.linkText()).isEqualTo(superclass);
        playerCode.showLinkAtIndex(playerText.indexOf("extends"));
        assertThat(playerCode.linkText()).as("no link on a keyword").isNull();
        playerCode.clearLink();
        playerCode.area().moveTo(sprite);
        assertThat(playerCode.contextMenuItems()).extracting(
            javafx.scene.control.MenuItem::getText).first().asString().contains(superclass);
        // colour swatches: hue, rgb and hex; a picked colour is written in the same form
        assertThat(CodePreviews.forLine("    this.setTint(60);", tiled, f -> { })).isNotNull();
        java.util.function.BiFunction<String, javafx.scene.paint.Color, String> pick =
            (line, color) -> {
              var m = CodePreviews.COLOR.matcher(line);
              assertThat(m.find()).isTrue();
              return CodePreviews.withColor(line, m, color);
            };
        assertThat(pick.apply("    this.setTint(0);", javafx.scene.paint.Color.hsb(120, 1, 1)))
            .isEqualTo("    this.setTint(85);");
        assertThat(pick.apply("var c = new Color(255, 0, 0);", javafx.scene.paint.Color.BLUE))
            .isEqualTo("var c = new Color(0, 0, 255);");
        assertThat(pick.apply("text.setTextColor(new Color(\"#ff0000\"));",
            javafx.scene.paint.Color.LIME)).isEqualTo("text.setTextColor(new Color(\"#00ff00\"));");
        var red = CodePreviews.COLOR.matcher("this.setColor(255, 0, 0);");
        assertThat(red.find()).isTrue();
        assertThat(CodePreviews.colorOf(red)).isEqualTo(javafx.scene.paint.Color.rgb(255, 0, 0));
        CodeEditor colours = new CodeEditor(tiled.resolve("Colours.java"), """
            this.setTint(0);
            this.setTint(85);
            this.setColor(255, 171, 25);
            this.setTextColor(new Color("#4c97ff"));
            """, apiIndex, () -> tiled);
        javafx.scene.Scene coloursScene = new javafx.scene.Scene(colours, 600, 140);
        Theme.apply(coloursScene);
        colours.applyCss();
        colours.layout();
        snapshot(colours, Path.of("target/code-colours.png"));
        // the block palette follows the class: sprite blocks, stage blocks, or hidden
        assertThat(VisualMode.paletteContext(tiled.resolve("Bamboo.java")))
            .isEqualTo(VisualMode.PaletteContext.SPRITE); // Bamboo -> Enemy -> AnimatedSprite
        assertThat(VisualMode.paletteContext(tiled.resolve("World.java")))
            .isEqualTo(VisualMode.PaletteContext.STAGE);
        assertThat(VisualMode.paletteContext(tiled.resolve("GameState.java")))
            .isEqualTo(VisualMode.PaletteContext.NONE);
        assertThat(VisualMode.paletteContext(tiled.resolve("Tiled.java")))
            .isEqualTo(VisualMode.PaletteContext.NONE);
        BlockPalette contextPalette = new BlockPalette(apiIndex, m -> { });
        contextPalette.setContext(VisualMode.PaletteContext.SPRITE);
        assertThat(contextPalette.shownBlocks()).isNotEmpty()
            .noneMatch(m -> m.className().equals("Stage"));
        contextPalette.setContext(VisualMode.PaletteContext.STAGE);
        assertThat(contextPalette.shownBlocks()).isNotEmpty()
            .noneMatch(m -> m.className().equals("Sprite"));
        Path shakespeare = org.openpatch.scratch4j.core.project.BundledTemplates.create(
            "demo-shakespeare", tmp, "shakespeare", null);
        AtomicReference<String> fontLines = new AtomicReference<>();
        FontPreviewView fontView = new FontPreviewView(shakespeare,
            shakespeare.resolve("assets/Singkong.ttf"), fontLines::set);
        assertThat(fontView.fontFamily()).isNotBlank();

        SpriteAssetsView spriteAssets = new SpriteAssetsView(project, "Player", () -> {});
        assertThat(spriteAssets.file()).isEqualTo(project.root().resolve("Player.java"));
        // visual -> code: the asset editor's "Code" button
        AtomicReference<Boolean> codeMode = new AtomicReference<>(false);
        spriteAssets.setOnOpenCode(() -> codeMode.set(true));
        assertThat(spriteAssets.getTop()).isNull();
        spriteAssets.codeButton().fire();
        assertThat(codeMode.get()).isTrue();
        javafx.scene.control.TabPane spriteTabs = (javafx.scene.control.TabPane)
            ((javafx.scene.layout.StackPane) spriteAssets.getCenter()).getChildren().get(0);
        assertThat(spriteTabs.getTabs()).hasSize(4);
        javafx.scene.layout.VBox hitboxPane = (javafx.scene.layout.VBox)
            spriteTabs.getTabs().get(3).getContent();
        javafx.scene.layout.HBox hitboxActions =
            (javafx.scene.layout.HBox) hitboxPane.getChildren().get(3);
        ((javafx.scene.control.Button) hitboxActions.getChildren().get(0)).fire();
        javafx.scene.layout.VBox pointsPanel = (javafx.scene.layout.VBox)
            ((javafx.scene.layout.HBox) hitboxPane.getChildren().get(2)).getChildren().get(1);
        @SuppressWarnings("unchecked")
        javafx.scene.control.ListView<String> pointsList =
            (javafx.scene.control.ListView<String>) pointsPanel.getChildren().get(1);
        pointsList.getSelectionModel().selectFirst();
        javafx.scene.control.TextField xCoordinate = (javafx.scene.control.TextField)
            ((javafx.scene.layout.HBox) pointsPanel.getChildren().get(2)).getChildren().get(1);
        xCoordinate.setText("3");
        xCoordinate.fireEvent(new javafx.event.ActionEvent());
        assertThat(pointsList.getItems().get(0)).contains("3, 0");
        ((javafx.scene.control.Button) hitboxActions.getChildren().get(1)).fire();
        assertThat(pointsList.getItems().get(0)).contains("0, 0");
        ((javafx.scene.control.Button) hitboxActions.getChildren().get(2)).fire();
        assertThat(pointsList.getItems().get(0)).contains("3, 0");
        assertThat(((javafx.scene.control.Label) hitboxPane.getChildren().get(4)).getText())
            .isEqualTo(I18n.t("spriteassets.hitbox.unsaved"));
        ((javafx.scene.control.Button) hitboxActions.getChildren().get(3)).fire();
        assertThat(Files.readString(project.root().resolve("Player.java")))
            .contains("this.setHitbox(3, 0,");
        assertThat(spriteAssets.confirmClose()).isTrue();

        // image editor opens a costume copied out of the atlas
        Path png = org.openpatch.scratch4j.core.assets.AssetCopier.copyBuiltinImage(
            org.openpatch.scratch4j.core.assets.BuiltinAssetIndex.get()
                .image("bunny1_stand").orElseThrow(),
            tmp.resolve("uismoke"));
        ImageEditorView imageEditor = new ImageEditorView(project, png);
        assertThat(imageEditor.file()).isEqualTo(png);
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        done.countDown();
      }
    });
    assertThat(done.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    javafx.application.Platform.exit();
    assertThat(failure.get()).as("no exception on the FX thread").isNull();
  }

  /** Writes a node snapshot as PNG (for eyeballing renderer changes). */
  static void snapshot(javafx.scene.Node node, Path file) throws Exception {
    javafx.scene.image.WritableImage image = node.snapshot(null, null);
    int w = (int) image.getWidth();
    int h = (int) image.getHeight();
    var buffered = new java.awt.image.BufferedImage(w, h,
        java.awt.image.BufferedImage.TYPE_INT_ARGB);
    var reader = image.getPixelReader();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        buffered.setRGB(x, y, reader.getArgb(x, y));
      }
    }
    Files.createDirectories(file.toAbsolutePath().getParent());
    javax.imageio.ImageIO.write(buffered, "png", file.toFile());
  }
}
