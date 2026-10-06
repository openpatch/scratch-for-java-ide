package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.tiled.MapCode;
import org.openpatch.scratch4j.core.tiled.TmxCompatibility;
import org.openpatch.scratch4j.core.tiled.TmxDocument;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maps the editor saves, and maps from Tiled 1.10 after "make it work",
 * load and draw in the released library: stamped as background, objects read
 * with their names, types and stage coordinates. Needs a display - run under
 * {@code xvfb-run -a} with {@code -Dscratch4j.gltest=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class TiledMapRunIT {

  @TempDir
  Path tmp;

  @Test
  void editorAndConvertedMapsRunInTheLibrary() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "maps", allJar);
    Path root = tmp.resolve("maps");
    Path tiles = root.resolve("assets/images/tiles.png");
    Files.createDirectories(tiles.getParent());
    ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", tiles.toFile());

    // a map made in the editor: tiles, an animation, objects
    TmxDocument made = TmxDocument.create(root.resolve("assets/maps/made.tmx"), 6, 4, 16, 16);
    var tileset = made.addTileset(tiles, 32, 32, 16, 16);
    var ground = (TmxDocument.TileLayer) made.layers.get(0);
    made.fill(ground, 0, 0, 1);
    made.setTile(ground, 2, 1, 4);
    tileset.animations.put(1, new java.util.ArrayList<>(List.of(new int[] {1, 100},
        new int[] {2, 100})));
    var objects = (TmxDocument.ObjectLayer) made.layers.get(1);
    var start = made.addObject(objects, TmxDocument.Shape.POINT, 24, 40, 0, 0);
    start.name = "start";
    start.type = "spawn";
    var lake = made.addObject(objects, TmxDocument.Shape.ELLIPSE, 32, 16, 16, 16);
    lake.type = "water";
    made.save();

    // a map as Tiled 1.10 writes it, converted by the editor
    Path dir = root.resolve("assets/maps");
    Files.writeString(dir.resolve("terrain.tsx"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <tileset version="1.10" name="terrain" tilewidth="16" tileheight="16" tilecount="4" columns="2">
          <image source="../images/tiles.png" width="32" height="32"/>
        </tileset>
        """);
    ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(1).putInt((int) (0x80000000L | 2)).putInt(3).putInt(4);
    ByteArrayOutputStream zipped = new ByteArrayOutputStream();
    try (var out = new DeflaterOutputStream(zipped)) {
      out.write(buffer.array());
    }
    Path tiled = dir.resolve("tiled.tmx");
    Files.writeString(tiled, """
        <?xml version="1.0" encoding="UTF-8"?>
        <map version="1.10" tiledversion="1.11.0" orientation="orthogonal" width="2" height="2"
             tilewidth="16" tileheight="16" infinite="0" nextlayerid="4" nextobjectid="2">
          <tileset firstgid="1" source="terrain.tsx"/>
          <group id="3" name="world">
            <layer id="1" name="Ground" width="2" height="2">
              <data encoding="base64" compression="zlib">%s</data>
            </layer>
            <objectgroup id="2" name="Things">
              <object id="1" name="door" class="exit" x="8" y="8"><point/></object>
            </objectgroup>
          </group>
        </map>
        """.formatted(Base64.getEncoder().encodeToString(zipped.toByteArray())));
    TmxDocument converted = TmxDocument.open(tiled);
    TmxCompatibility.convert(converted);
    converted.save();

    // the stage loads both ("Use in stage") and prints the objects
    Path stage = root.resolve("MyStage.java");
    String source = Files.readString(stage);
    source = MapCode.insert(source, "assets/maps/made.tmx", List.of("Ground"));
    source = MapCode.insert(source, "assets/maps/tiled.tmx", List.of("Ground"));
    source = source.replace("    map2.stampLayerToBackground(\"Ground\");\n", """
            map2.stampLayerToBackground("Ground");
            for (var o : map.getObjectsFromLayer("Objects")) {
              System.out.println("OBJ " + o.name + "|" + o.type + "|" + o.x + "|" + o.y);
            }
            for (var o : map2.getObjectsFromLayer("Things")) {
              System.out.println("OBJ " + o.name + "|" + o.type + "|" + o.x + "|" + o.y);
            }
        """);
    Files.writeString(stage, source);

    List<String> out = new CopyOnWriteArrayList<>();
    List<String> err = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(ScratchProject.open(root),
        RunConfig.of("MyStage").withExitAfter(3), new RunListener() {
          @Override public void onStdout(String line) { out.add(line); }
          @Override public void onStderr(String line) { err.add(line); }
        });
    int code = handle.exitFuture().get(60, TimeUnit.SECONDS);
    assertThat(code).as("exit code; stderr: %s", err).isZero();
    assertThat(err).noneMatch(line -> line.contains("Exception"));
    assertThat(out).contains("OBJ start|spawn|24.0|-40.0", "OBJ null|water|32.0|-16.0",
        "OBJ door|exit|8.0|-8.0");
  }
}
