package org.openpatch.scratch4j.core.tiled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.assets.TiledMapReader;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TmxDocumentTest {

  @TempDir
  Path tmp;

  @Test
  void demoMapRoundTripsAndKeepsWhatTheEditorDoesNotManage() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    Path map = root.resolve("Level1.tmx");
    String before = Files.readString(map);
    var original = TiledMapReader.read(map);

    TmxDocument doc = TmxDocument.open(map);
    doc.save();
    String after = Files.readString(map);
    var reread = TiledMapReader.read(map);

    assertThat(reread.width()).isEqualTo(original.width());
    assertThat(reread.layers()).hasSameSizeAs(original.layers());
    for (int i = 0; i < original.layers().size(); i++) {
      assertThat(reread.layers().get(i).gids()).isEqualTo(original.layers().get(i).gids());
    }
    assertThat(reread.objectGroups().get(0).objects()).hasSameSizeAs(
        original.objectGroups().get(0).objects());
    // untouched parts survive: editor settings and tile animations
    assertThat(before).contains("<editorsettings>");
    assertThat(after).contains("<editorsettings>").contains("<animation>")
        .contains("encoding=\"csv\"");
    assertThat(TmxCompatibility.check(TmxDocument.open(map))).isEmpty();
  }

  @Test
  void paintFillResizeTilesetsAndObjectsSave() throws Exception {
    Path image = tmp.resolve("assets/images/tiles.png");
    Files.createDirectories(image.getParent());
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32, 16,
        java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
    Path mapFile = tmp.resolve("assets/maps/level.tmx");
    TmxDocument doc = TmxDocument.create(mapFile, 4, 3, 16, 16);
    var tileset = doc.addTileset(image, 32, 16, 16, 16);
    assertThat(tileset.firstGid).isEqualTo(1);
    assertThat(tileset.tileCount).isEqualTo(2);
    var ground = (TmxDocument.TileLayer) doc.layers.get(0);
    doc.fill(ground, 0, 0, 1);
    doc.setTile(ground, 3, 2, 2);
    var objects = (TmxDocument.ObjectLayer) doc.layers.get(1);
    var spawn = doc.addObject(objects, TmxDocument.Shape.POINT, 24, 8, 0, 0);
    spawn.name = "start";
    spawn.type = "spawn-point";
    var wall = doc.addObject(objects, TmxDocument.Shape.POLYGON, 0, 0, 0, 0);
    wall.points.add(new double[] {0, 0});
    wall.points.add(new double[] {16, 0});
    wall.points.add(new double[] {16, 16});
    wall.type = "wall";
    wall.properties.put("solid", new String[] {"bool", "true"});
    doc.resize(5, 3, 1, 0); // one column more on the left
    doc.save();

    String xml = Files.readString(mapFile);
    assertThat(xml).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<map version=")
        .contains("<image source=\"../images/tiles.png\" width=\"32\" height=\"16\"/>")
        .contains(" <layer id=\"1\" name=\"Ground\" width=\"5\" height=\"3\">")
        .contains("type=\"spawn-point\"").doesNotContain("class=")
        .contains("<polygon points=\"0,0 16,0 16,16\"/>")
        .contains("<property name=\"solid\" type=\"bool\" value=\"true\"/>");
    var read = TiledMapReader.read(mapFile);
    assertThat(read.width()).isEqualTo(5);
    long[] gids = read.layers().get(0).gids();
    assertThat(gids[0]).isZero();          // the new column
    assertThat(gids[1]).isEqualTo(1);       // filled
    assertThat(gids[2 * 5 + 4]).isEqualTo(2);
    var objectsRead = read.objectGroups().get(0).objects();
    assertThat(objectsRead.get(0).x()).isEqualTo(24 + 16); // moved with the tiles
    assertThat(objectsRead.get(0).type()).isEqualTo("spawn-point");

    // undo snapshots restore everything
    var state = doc.snapshot();
    doc.setTile(ground, 0, 0, 2);
    doc.restore(state);
    assertThat(doc.tile((TmxDocument.TileLayer) doc.layers.get(0), 0, 0)).isZero();
  }

  @Test
  void tiled110MapsAreReportedAndConverted() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("maps"));
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16, 16,
        java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", dir.resolve("tiles.png").toFile());
    Files.writeString(dir.resolve("terrain.tsx"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <tileset version="1.10" name="terrain" tilewidth="8" tileheight="8" tilecount="4" columns="2">
          <image source="tiles.png" width="16" height="16"/>
          <tile id="0"><properties><property name="solid" type="bool" value="true"/></properties></tile>
          <tile id="2"><animation><frame tileid="2" duration="200"/><frame tileid="3" duration="200"/></animation></tile>
        </tileset>
        """);
    ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(1).putInt((int) (0x80000000L | 2)).putInt(3).putInt(4);
    ByteArrayOutputStream zipped = new ByteArrayOutputStream();
    try (var out = new DeflaterOutputStream(zipped)) {
      out.write(buffer.array());
    }
    Path map = dir.resolve("new.tmx");
    Files.writeString(map, """
        <?xml version="1.0" encoding="UTF-8"?>
        <map version="1.10" tiledversion="1.11.0" orientation="orthogonal" width="2" height="2"
             tilewidth="8" tileheight="8" infinite="0" nextlayerid="4" nextobjectid="2">
          <tileset firstgid="1" source="terrain.tsx"/>
          <group id="3" name="world">
            <layer id="1" name="ground" width="2" height="2">
              <data encoding="base64" compression="zlib">%s</data>
            </layer>
            <objectgroup id="2" name="Objects" class="markers">
              <object id="1" name="start" class="spawn-point" x="4" y="4"><point/></object>
            </objectgroup>
          </group>
        </map>
        """.formatted(Base64.getEncoder().encodeToString(zipped.toByteArray())));

    TmxDocument doc = TmxDocument.open(map);
    assertThat(TmxCompatibility.check(doc)).extracting(TmxCompatibility.Issue::key)
        .contains("tiled.encoding", "tiled.external", "tiled.group", "tiled.flipped",
            "tiled.class");
    // the library loads all of it from 5.6.0 on: no problems for such projects
    assertThat(TmxCompatibility.check(doc, "5.6.0")).isEmpty();
    assertThat(TmxCompatibility.check(doc, "5.5.0")).isNotEmpty();
    TmxCompatibility.convert(doc);
    doc.save();

    TmxDocument converted = TmxDocument.open(map);
    assertThat(TmxCompatibility.check(converted)).isEmpty();
    String xml = Files.readString(map);
    assertThat(xml).doesNotContain("<group").doesNotContain("source=\"terrain.tsx\"")
        .contains("<image source=\"tiles.png\"").contains("type=\"spawn-point\"")
        .contains("name=\"ground\"").contains("name=\"Objects\"")
        .contains("<property name=\"solid\" type=\"bool\" value=\"true\"/>")
        .contains("<frame tileid=\"3\" duration=\"200\"/>");
    assertThat(converted.tilesets.get(0).animations.get(2)).hasSize(2);
    var read = TiledMapReader.read(map);
    assertThat(read.layers().get(0).gids()).containsExactly(1, 2, 3, 4);
    assertThat(read.objectGroups().get(0).objects().get(0).type()).isEqualTo("spawn-point");
  }

  @Test
  void animationsAreEditedAndUndone() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    Path map = root.resolve("Level1.tmx");
    TmxDocument doc = TmxDocument.open(map);
    var tileset = doc.tilesets.stream().filter(t -> !t.animations.isEmpty()).findFirst()
        .orElseThrow();
    int animated = tileset.animations.keySet().iterator().next();
    var before = doc.snapshot();
    tileset.animations.get(animated).get(0)[1] = 250;
    tileset.animations.put(0, new java.util.ArrayList<>(java.util.List.of(
        new int[] {0, 100}, new int[] {1, 100})));
    doc.save();
    String xml = Files.readString(map);
    assertThat(xml).contains("duration=\"250\"")
        .contains("<tile id=\"0\">\n   <animation>\n    <frame tileid=\"0\" duration=\"100\"/>");
    TmxDocument reopened = TmxDocument.open(map);
    reopened.tilesets.stream().filter(t -> t.name.equals(tileset.name)).findFirst()
        .orElseThrow().animations.remove(0);
    reopened.save();
    assertThat(Files.readString(map)).doesNotContain("<tile id=\"0\">");
    doc.restore(before);
    assertThat(doc.tilesets.stream().filter(t -> t.name.equals(tileset.name)).findFirst()
        .orElseThrow().animations).doesNotContainKey(0);
  }
}
