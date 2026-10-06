package org.openpatch.scratch4j.core.assets;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads a Tiled {@code .tmx} map (what the library's {@code tiled} extension
 * loads) for the IDE's map preview: orthogonal maps, tile layers in CSV or
 * base64 (plain, zlib or gzip), embedded or external ({@code .tsx}) tilesets,
 * and object groups. No JavaFX — the UI draws the model.
 */
public final class TiledMapReader {

  /** Tiled stores flips in the top bits of a gid. */
  public static final long FLIPPED_HORIZONTALLY = 0x80000000L;
  public static final long FLIPPED_VERTICALLY = 0x40000000L;
  public static final long FLIPPED_DIAGONALLY = 0x20000000L;
  private static final long GID_MASK = 0x1FFFFFFFL;

  public record Tileset(int firstGid, String name, int tileWidth, int tileHeight, int columns,
      int tileCount, Path image, int imageWidth, int imageHeight) {}

  /** {@code gids} row by row; 0 is empty. Raw values keep the flip bits. */
  public record Layer(String name, boolean visible, double opacity, int width, int height,
      long[] gids) {}

  public record MapObject(String name, String type, double x, double y, double width,
      double height, long gid) {}

  public record ObjectGroup(String name, boolean visible, List<MapObject> objects) {}

  public record TiledMap(String orientation, int width, int height, int tileWidth,
      int tileHeight, List<Tileset> tilesets, List<Layer> layers, List<ObjectGroup> objectGroups) {

    /** The tileset a (masked) gid belongs to, or null. */
    public Tileset tilesetOf(long gid) {
      long id = gid & GID_MASK;
      Tileset found = null;
      for (Tileset tileset : tilesets) {
        if (tileset.firstGid() <= id && (found == null || tileset.firstGid() > found.firstGid())) {
          found = tileset;
        }
      }
      return found;
    }
  }

  private TiledMapReader() {}

  /** The tile id without its flip bits. */
  public static long id(long gid) {
    return gid & GID_MASK;
  }

  public static TiledMap read(Path tmx) throws IOException {
    Element map = parse(tmx).getDocumentElement();
    if (!"map".equals(map.getTagName())) {
      throw new IOException("Not a Tiled map: " + tmx.getFileName());
    }
    List<Tileset> tilesets = new ArrayList<>();
    List<Layer> layers = new ArrayList<>();
    List<ObjectGroup> groups = new ArrayList<>();
    NodeList children = map.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element child)) {
        continue;
      }
      switch (child.getTagName()) {
        case "tileset" -> tilesets.add(tileset(child, tmx.getParent()));
        case "layer" -> layers.add(layer(child));
        case "objectgroup" -> groups.add(objectGroup(child));
        case "group" -> readGroup(child, layers, groups);
        default -> { }
      }
    }
    return new TiledMap(map.getAttribute("orientation"), integer(map, "width", 0),
        integer(map, "height", 0), integer(map, "tilewidth", 0), integer(map, "tileheight", 0),
        tilesets, layers, groups);
  }

  /** Group layers (Tiled 1.2+): their layers are part of the map. */
  private static void readGroup(Element group, List<Layer> layers, List<ObjectGroup> groups)
      throws IOException {
    NodeList children = group.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element child)) {
        continue;
      }
      switch (child.getTagName()) {
        case "layer" -> layers.add(layer(child));
        case "objectgroup" -> groups.add(objectGroup(child));
        case "group" -> readGroup(child, layers, groups);
        default -> { }
      }
    }
  }

  private static Document parse(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(in);
    } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
      throw new IOException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
    }
  }

  private static Tileset tileset(Element element, Path base) throws IOException {
    int firstGid = integer(element, "firstgid", 1);
    Element source = element;
    Path folder = base;
    if (element.hasAttribute("source")) {
      Path tsx = base.resolve(element.getAttribute("source")).normalize();
      source = parse(tsx).getDocumentElement();
      folder = tsx.getParent();
    }
    Element image = first(source, "image");
    Path imagePath = image == null ? null
        : folder.resolve(image.getAttribute("source")).normalize();
    return new Tileset(firstGid, source.getAttribute("name"), integer(source, "tilewidth", 0),
        integer(source, "tileheight", 0), integer(source, "columns", 1),
        integer(source, "tilecount", 0), imagePath,
        image == null ? 0 : integer(image, "width", 0),
        image == null ? 0 : integer(image, "height", 0));
  }

  private static Layer layer(Element element) throws IOException {
    int width = integer(element, "width", 0);
    int height = integer(element, "height", 0);
    Element data = first(element, "data");
    long[] gids = new long[width * height];
    if (data != null) {
      String encoding = data.getAttribute("encoding");
      String compression = data.getAttribute("compression");
      String text = data.getTextContent().trim();
      if ("csv".equals(encoding) || "base64".equals(encoding)) {
        long[] decoded = decode(encoding, compression, text);
        System.arraycopy(decoded, 0, gids, 0, Math.min(decoded.length, gids.length));
      } else {
        // the old XML form: <tile gid="..."/>
        NodeList tiles = data.getElementsByTagName("tile");
        for (int i = 0; i < Math.min(tiles.getLength(), gids.length); i++) {
          gids[i] = Long.parseLong(((Element) tiles.item(i)).getAttribute("gid").isEmpty()
              ? "0" : ((Element) tiles.item(i)).getAttribute("gid"));
        }
      }
    }
    return new Layer(element.getAttribute("name"), !"0".equals(element.getAttribute("visible")),
        element.hasAttribute("opacity") ? Double.parseDouble(element.getAttribute("opacity")) : 1,
        width, height, gids);
  }

  /** Tile ids of a {@code <data>} text in CSV or base64 (plain, zlib, gzip). */
  public static long[] decode(String encoding, String compression, String text)
      throws IOException {
    if ("csv".equals(encoding)) {
      return java.util.Arrays.stream(text.trim().split("[,\\s]+"))
          .filter(v -> !v.isEmpty()).mapToLong(Long::parseLong).toArray();
    }
    if (!"base64".equals(encoding)) {
      throw new IOException("Unsupported layer encoding: " + encoding);
    }
    byte[] bytes = Base64.getMimeDecoder().decode(text.trim());
    if ("zlib".equals(compression)) {
      bytes = new InflaterInputStream(new ByteArrayInputStream(bytes)).readAllBytes();
    } else if ("gzip".equals(compression)) {
      bytes = new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes();
    } else if (compression != null && !compression.isEmpty()) {
      throw new IOException("Unsupported layer compression: " + compression);
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    long[] gids = new long[bytes.length / 4];
    for (int i = 0; i < gids.length; i++) {
      gids[i] = Integer.toUnsignedLong(buffer.getInt());
    }
    return gids;
  }

  private static ObjectGroup objectGroup(Element element) {
    List<MapObject> objects = new ArrayList<>();
    NodeList nodes = element.getElementsByTagName("object");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element object = (Element) nodes.item(i);
      objects.add(new MapObject(object.getAttribute("name"),
          object.hasAttribute("type") ? object.getAttribute("type") : object.getAttribute("class"),
          decimal(object, "x"), decimal(object, "y"), decimal(object, "width"),
          decimal(object, "height"),
          object.hasAttribute("gid") ? Long.parseLong(object.getAttribute("gid")) : 0));
    }
    return new ObjectGroup(element.getAttribute("name"),
        !"0".equals(element.getAttribute("visible")), objects);
  }

  private static Element first(Element parent, String tag) {
    NodeList list = parent.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      Node node = list.item(i);
      if (node instanceof Element e && e.getTagName().equals(tag)) {
        return e;
      }
    }
    return null;
  }

  private static int integer(Element element, String attribute, int fallback) {
    String value = element.getAttribute(attribute);
    return value.isEmpty() ? fallback : (int) Double.parseDouble(value);
  }

  private static double decimal(Element element, String attribute) {
    String value = element.getAttribute(attribute);
    return value.isEmpty() ? 0 : Double.parseDouble(value);
  }
}
