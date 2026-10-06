package org.openpatch.scratch4j.core.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TiledMapReaderTest {

  @TempDir
  Path tmp;

  @Test
  void readsTheBundledTiledDemoMap() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    var map = TiledMapReader.read(root.resolve("Level1.tmx"));
    assertThat(map.orientation()).isEqualTo("orthogonal");
    assertThat(map.width()).isEqualTo(30);
    assertThat(map.height()).isEqualTo(20);
    assertThat(map.tileWidth()).isEqualTo(32);
    assertThat(map.tilesets()).hasSize(3);
    assertThat(map.tilesets().get(0).image()).isEqualTo(root.resolve("assets/TilesetField.png"));
    assertThat(map.tilesets().get(0).image()).isRegularFile();
    assertThat(map.layers()).isNotEmpty();
    assertThat(map.layers().get(0).gids()).hasSize(600);
    assertThat(map.layers().get(0).gids()[0]).isEqualTo(54);
    assertThat(map.tilesetOf(54).firstGid()).isEqualTo(31);
    assertThat(map.objectGroups()).isNotEmpty();
    assertThat(map.objectGroups().get(0).objects()).isNotEmpty();
  }

  @Test
  void readsBase64ZlibLayersAndExternalTilesets() throws Exception {
    ByteBuffer buffer = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(1).putInt(2).putInt((int) (TiledMapReader.FLIPPED_HORIZONTALLY | 3)).putInt(0);
    ByteArrayOutputStream zipped = new ByteArrayOutputStream();
    try (DeflaterOutputStream out = new DeflaterOutputStream(zipped)) {
      out.write(buffer.array());
    }
    Files.writeString(tmp.resolve("tiles.tsx"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <tileset name="t" tilewidth="8" tileheight="8" tilecount="4" columns="2">
          <image source="img/tiles.png" width="16" height="16"/>
        </tileset>
        """);
    Files.writeString(tmp.resolve("m.tmx"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <map orientation="orthogonal" width="2" height="2" tilewidth="8" tileheight="8">
          <tileset firstgid="1" source="tiles.tsx"/>
          <layer name="ground" width="2" height="2" visible="0">
            <data encoding="base64" compression="zlib">%s</data>
          </layer>
        </map>
        """.formatted(Base64.getEncoder().encodeToString(zipped.toByteArray())));
    var map = TiledMapReader.read(tmp.resolve("m.tmx"));
    assertThat(map.tilesets().get(0).image()).isEqualTo(tmp.resolve("img/tiles.png"));
    long[] gids = map.layers().get(0).gids();
    assertThat(gids[0]).isEqualTo(1);
    assertThat(TiledMapReader.id(gids[2])).isEqualTo(3);
    assertThat(gids[2] & TiledMapReader.FLIPPED_HORIZONTALLY).isNotZero();
    assertThat(map.layers().get(0).visible()).isFalse();
  }
}
