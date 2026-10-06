package org.openpatch.scratch4j.core.tiled;

import org.openpatch.scratch4j.core.assets.TiledMapReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An editable Tiled map. The parsed XML is kept, and saving writes back only
 * what the editor manages (map size, tilesets, tile layers, object layers and
 * their objects), so everything else in the file — editor settings, tile
 * animations, Wang sets, custom properties of the map — survives.
 *
 * <p>It saves the form every Scratch for Java version loads: CSV layer data,
 * embedded tilesets, objects with a {@code type} attribute (which Tiled 1.9+
 * reads as their class).
 */
public final class TmxDocument {

  /** One tileset: an image cut into tiles. {@code image} is absolute. */
  public static final class Tileset {
    public int firstGid;
    public String name;
    public int tileWidth;
    public int tileHeight;
    public int columns;
    public int tileCount;
    public int margin;
    public int spacing;
    public Path image;
    /** The image path as written in the file (relative to the map or .tsx). */
    public String imageSource;
    /** The .tsx file for an external tileset, or null when embedded. */
    public String externalSource;
    /**
     * Tile animations: local tile id -> frames as {tile id, duration in ms}.
     * Tiled plays them; the editor previews and edits them.
     */
    public Map<Integer, List<int[]>> animations = new java.util.TreeMap<>();
    Element element;
    /** The root of the .tsx file of an external tileset (its tiles, Wang sets...). */
    Element externalElement;

    Tileset copy() {
      Tileset t = new Tileset();
      t.firstGid = firstGid;
      t.name = name;
      t.tileWidth = tileWidth;
      t.tileHeight = tileHeight;
      t.columns = columns;
      t.tileCount = tileCount;
      t.margin = margin;
      t.spacing = spacing;
      t.image = image;
      t.imageSource = imageSource;
      t.externalSource = externalSource;
      t.element = element;
      t.externalElement = externalElement;
      for (var entry : animations.entrySet()) {
        List<int[]> frames = new ArrayList<>();
        for (int[] f : entry.getValue()) frames.add(f.clone());
        t.animations.put(entry.getKey(), frames);
      }
      return t;
    }

    /** Whether a raw gid (flip bits ignored) belongs to this tileset. */
    public boolean contains(long gid) {
      long id = gid & 0x1FFFFFFFL;
      return id >= firstGid && id < (long) firstGid + tileCount;
    }
  }

  /** A tile layer, an object layer, or a group's layer; {@code group} is the group path. */
  public abstract static sealed class Layer permits TileLayer, ObjectLayer {
    public String name;
    public boolean visible = true;
    public double opacity = 1;
    /** "world/houses" for layers inside group layers, "" at the top. */
    public String group = "";
    /** The layer's class (Tiled 1.9+) or type. */
    public String type = "";
    Element element;

    abstract Layer copy();
  }

  /** Tiles row by row; 0 = empty; Tiled's flip bits are kept. */
  public static final class TileLayer extends Layer {
    public long[] gids;

    @Override
    TileLayer copy() {
      TileLayer l = new TileLayer();
      TmxDocument.copyInto(l, this);
      l.gids = gids.clone();
      return l;
    }
  }

  public static final class ObjectLayer extends Layer {
    public final List<MapObject> objects = new ArrayList<>();

    @Override
    ObjectLayer copy() {
      ObjectLayer l = new ObjectLayer();
      TmxDocument.copyInto(l, this);
      for (MapObject o : objects) {
        l.objects.add(o.copy());
      }
      return l;
    }
  }

  /** What kind of shape an object is. */
  public enum Shape { RECTANGLE, ELLIPSE, POINT, POLYGON, POLYLINE, TILE }

  /** One object: position in map pixels (y down, like Tiled), its shape and properties. */
  public static final class MapObject {
    public int id;
    public String name = "";
    public String type = "";
    public double x;
    public double y;
    public double width;
    public double height;
    public Shape shape = Shape.RECTANGLE;
    /** Polygon/polyline points relative to (x, y). */
    public List<double[]> points = new ArrayList<>();
    /** Custom properties: name -> [type, value]. */
    public final Map<String, String[]> properties = new LinkedHashMap<>();
    public long gid;
    Element element;

    MapObject copy() {
      MapObject o = new MapObject();
      o.id = id;
      o.name = name;
      o.type = type;
      o.x = x;
      o.y = y;
      o.width = width;
      o.height = height;
      o.shape = shape;
      for (double[] p : points) {
        o.points.add(p.clone());
      }
      properties.forEach((k, v) -> o.properties.put(k, v.clone()));
      o.gid = gid;
      o.element = element;
      return o;
    }
  }

  private final Path file;
  private Document dom;
  private Element mapElement;
  public int width;
  public int height;
  public int tileWidth;
  public int tileHeight;
  public String orientation = "orthogonal";
  public boolean infinite;
  public final List<Tileset> tilesets = new ArrayList<>();
  /** Bottom first, in document order (layers of groups at the group's place). */
  public final List<Layer> layers = new ArrayList<>();
  private int nextObjectId = 1;

  private TmxDocument(Path file) {
    this.file = file;
  }

  public Path file() {
    return file;
  }

  // --- create / open ------------------------------------------------------------

  /** A new empty map with one tile layer and one object layer. */
  public static TmxDocument create(Path file, int width, int height, int tileWidth,
      int tileHeight) {
    TmxDocument doc = new TmxDocument(file);
    try {
      doc.dom = builder().newDocument();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    doc.mapElement = doc.dom.createElement("map");
    doc.dom.appendChild(doc.mapElement);
    doc.mapElement.setAttribute("version", "1.10");
    doc.mapElement.setAttribute("tiledversion", "1.10.2");
    doc.mapElement.setAttribute("orientation", "orthogonal");
    doc.mapElement.setAttribute("renderorder", "right-down");
    doc.mapElement.setAttribute("infinite", "0");
    doc.width = width;
    doc.height = height;
    doc.tileWidth = tileWidth;
    doc.tileHeight = tileHeight;
    TileLayer ground = doc.addTileLayer("Ground");
    ground.gids = new long[width * height];
    doc.addObjectLayer("Objects");
    return doc;
  }

  public static TmxDocument open(Path file) throws IOException {
    TmxDocument doc = new TmxDocument(file);
    try {
      doc.dom = builder().parse(file.toFile());
    } catch (Exception e) {
      throw new IOException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
    }
    doc.mapElement = doc.dom.getDocumentElement();
    if (!"map".equals(doc.mapElement.getTagName())) {
      throw new IOException(file.getFileName() + " is not a Tiled map");
    }
    doc.width = integer(doc.mapElement, "width", 0);
    doc.height = integer(doc.mapElement, "height", 0);
    doc.tileWidth = integer(doc.mapElement, "tilewidth", 0);
    doc.tileHeight = integer(doc.mapElement, "tileheight", 0);
    doc.orientation = attribute(doc.mapElement, "orientation", "orthogonal");
    doc.infinite = "1".equals(doc.mapElement.getAttribute("infinite"));
    doc.nextObjectId = integer(doc.mapElement, "nextobjectid", 1);
    for (Element tileset : children(doc.mapElement, "tileset")) {
      doc.tilesets.add(doc.readTileset(tileset));
    }
    doc.readLayers(doc.mapElement, "");
    for (Layer layer : doc.layers) {
      if (layer instanceof ObjectLayer objects) {
        for (MapObject o : objects.objects) {
          doc.nextObjectId = Math.max(doc.nextObjectId, o.id + 1);
        }
      }
    }
    return doc;
  }

  private Tileset readTileset(Element element) throws IOException {
    Tileset t = new Tileset();
    t.element = element;
    t.firstGid = integer(element, "firstgid", 1);
    Element source = element;
    Path base = file.toAbsolutePath().getParent();
    if (element.hasAttribute("source")) {
      t.externalSource = element.getAttribute("source");
      Path tsx = base.resolve(t.externalSource).normalize();
      try {
        source = builder().parse(tsx.toFile()).getDocumentElement();
      } catch (Exception e) {
        throw new IOException("Cannot read the tileset " + t.externalSource + ": "
            + e.getMessage(), e);
      }
      base = tsx.getParent();
      t.externalElement = source;
    }
    for (Element tile : children(source, "tile")) {
      Element animation = first(tile, "animation");
      if (animation == null) continue;
      List<int[]> frames = new ArrayList<>();
      for (Element frame : children(animation, "frame")) {
        frames.add(new int[] {integer(frame, "tileid", 0), integer(frame, "duration", 100)});
      }
      t.animations.put(integer(tile, "id", 0), frames);
    }
    t.name = attribute(source, "name", "tiles");
    t.tileWidth = integer(source, "tilewidth", tileWidth);
    t.tileHeight = integer(source, "tileheight", tileHeight);
    t.columns = integer(source, "columns", 1);
    t.tileCount = integer(source, "tilecount", 0);
    t.margin = integer(source, "margin", 0);
    t.spacing = integer(source, "spacing", 0);
    Element image = first(source, "image");
    if (image != null) {
      t.imageSource = image.getAttribute("source");
      t.image = base.resolve(t.imageSource).normalize();
    }
    return t;
  }

  private void readLayers(Element parent, String group) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element element)) {
        continue;
      }
      switch (element.getTagName()) {
        case "layer" -> {
          TileLayer layer = new TileLayer();
          readCommon(layer, element, group);
          int count = integer(element, "width", width) * integer(element, "height", height);
          try {
            layer.gids = readData(first(element, "data"), count);
          } catch (IOException e) {
            layer.gids = new long[count];
          }
          layers.add(layer);
        }
        case "objectgroup" -> {
          ObjectLayer layer = new ObjectLayer();
          readCommon(layer, element, group);
          for (Element object : children(element, "object")) {
            layer.objects.add(readObject(object));
          }
          layers.add(layer);
        }
        case "group" -> readLayers(element,
            (group.isEmpty() ? "" : group + "/") + attribute(element, "name", "group"));
        default -> { }
      }
    }
  }

  private void readCommon(Layer layer, Element element, String group) {
    layer.element = element;
    layer.name = attribute(element, "name", "");
    layer.visible = !"0".equals(element.getAttribute("visible"));
    layer.opacity = element.hasAttribute("opacity")
        ? Double.parseDouble(element.getAttribute("opacity")) : 1;
    layer.group = group;
    layer.type = element.hasAttribute("class") ? element.getAttribute("class")
        : attribute(element, "type", "");
  }

  private static long[] readData(Element data, int count) throws IOException {
    long[] gids = new long[count];
    if (data == null) {
      return gids;
    }
    // reuse the reader's decoding (CSV, base64, zlib, gzip)
    String encoding = data.getAttribute("encoding");
    String text = data.getTextContent().trim();
    if (encoding.isEmpty()) {
      List<Element> tiles = children(data, "tile");
      for (int i = 0; i < Math.min(count, tiles.size()); i++) {
        gids[i] = Long.parseLong(attribute(tiles.get(i), "gid", "0"));
      }
      return gids;
    }
    long[] decoded = TiledMapReader.decode(encoding, data.getAttribute("compression"), text);
    System.arraycopy(decoded, 0, gids, 0, Math.min(count, decoded.length));
    return gids;
  }

  private MapObject readObject(Element element) {
    MapObject o = new MapObject();
    o.element = element;
    o.id = integer(element, "id", 0);
    o.name = attribute(element, "name", "");
    o.type = element.hasAttribute("type") ? element.getAttribute("type")
        : attribute(element, "class", "");
    o.x = decimal(element, "x");
    o.y = decimal(element, "y");
    o.width = decimal(element, "width");
    o.height = decimal(element, "height");
    if (element.hasAttribute("gid")) {
      o.shape = Shape.TILE;
      o.gid = Long.parseLong(element.getAttribute("gid"));
    } else if (first(element, "ellipse") != null) {
      o.shape = Shape.ELLIPSE;
    } else if (first(element, "point") != null) {
      o.shape = Shape.POINT;
    } else if (first(element, "polygon") != null || first(element, "polyline") != null) {
      Element poly = first(element, "polygon");
      o.shape = poly != null ? Shape.POLYGON : Shape.POLYLINE;
      if (poly == null) {
        poly = first(element, "polyline");
      }
      for (String pair : poly.getAttribute("points").trim().split("\\s+")) {
        String[] xy = pair.split(",");
        if (xy.length == 2) {
          o.points.add(new double[] {Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
        }
      }
    }
    Element properties = first(element, "properties");
    if (properties != null) {
      for (Element property : children(properties, "property")) {
        String value = property.hasAttribute("value") ? property.getAttribute("value")
            : property.getTextContent();
        o.properties.put(property.getAttribute("name"),
            new String[] {attribute(property, "type", "string"), value});
      }
    }
    return o;
  }

  // --- editing -------------------------------------------------------------------

  public TileLayer addTileLayer(String name) {
    TileLayer layer = new TileLayer();
    layer.name = name;
    layer.gids = new long[width * height];
    layers.add(layer);
    return layer;
  }

  public ObjectLayer addObjectLayer(String name) {
    ObjectLayer layer = new ObjectLayer();
    layer.name = name;
    layers.add(layer);
    return layer;
  }

  public long tile(TileLayer layer, int x, int y) {
    return inside(x, y) ? layer.gids[y * width + x] : 0;
  }

  public void setTile(TileLayer layer, int x, int y, long gid) {
    if (inside(x, y)) {
      layer.gids[y * width + x] = gid;
    }
  }

  public boolean inside(int x, int y) {
    return x >= 0 && y >= 0 && x < width && y < height;
  }

  /** Flood fill of the area with the same tile as (x, y), 4-connected. */
  public void fill(TileLayer layer, int x, int y, long gid) {
    if (!inside(x, y)) {
      return;
    }
    long target = tile(layer, x, y);
    if (target == gid) {
      return;
    }
    java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
    queue.add(new int[] {x, y});
    while (!queue.isEmpty()) {
      int[] p = queue.poll();
      if (!inside(p[0], p[1]) || tile(layer, p[0], p[1]) != target) {
        continue;
      }
      setTile(layer, p[0], p[1], gid);
      queue.add(new int[] {p[0] + 1, p[1]});
      queue.add(new int[] {p[0] - 1, p[1]});
      queue.add(new int[] {p[0], p[1] + 1});
      queue.add(new int[] {p[0], p[1] - 1});
    }
  }

  /**
   * New size in tiles; {@code offsetX/offsetY} moves the old content (positive:
   * the map grows to the left/top). Objects move with the tiles.
   */
  public void resize(int newWidth, int newHeight, int offsetX, int offsetY) {
    for (Layer layer : layers) {
      if (layer instanceof TileLayer tiles) {
        long[] resized = new long[newWidth * newHeight];
        for (int y = 0; y < height; y++) {
          for (int x = 0; x < width; x++) {
            int nx = x + offsetX;
            int ny = y + offsetY;
            if (nx >= 0 && ny >= 0 && nx < newWidth && ny < newHeight) {
              resized[ny * newWidth + nx] = tiles.gids[y * width + x];
            }
          }
        }
        tiles.gids = resized;
      } else if (layer instanceof ObjectLayer objects) {
        for (MapObject o : objects.objects) {
          o.x += offsetX * tileWidth;
          o.y += offsetY * tileHeight;
        }
      }
    }
    width = newWidth;
    height = newHeight;
  }

  /**
   * Adds an embedded tileset cut from {@code image} (inside the project) into
   * {@code tileWidth x tileHeight} tiles; returns it.
   */
  public Tileset addTileset(Path image, int imageWidth, int imageHeight, int tileW, int tileH) {
    Tileset t = new Tileset();
    t.firstGid = nextFirstGid();
    t.name = image.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    t.tileWidth = tileW;
    t.tileHeight = tileH;
    t.columns = Math.max(1, imageWidth / tileW);
    t.tileCount = t.columns * Math.max(1, imageHeight / tileH);
    t.image = image.toAbsolutePath().normalize();
    t.imageSource = relative(t.image);
    tilesets.add(t);
    return t;
  }

  private int nextFirstGid() {
    int next = 1;
    for (Tileset t : tilesets) {
      next = Math.max(next, t.firstGid + t.tileCount);
    }
    return next;
  }

  public MapObject addObject(ObjectLayer layer, Shape shape, double x, double y, double w,
      double h) {
    MapObject o = new MapObject();
    o.id = nextObjectId++;
    o.shape = shape;
    o.x = x;
    o.y = y;
    o.width = w;
    o.height = h;
    layer.objects.add(o);
    return o;
  }

  /**
   * A copy of an object (from this or another map) in {@code layer}, moved by
   * dx, dy, with a new id and its own XML element.
   */
  public MapObject duplicate(ObjectLayer layer, MapObject source, double dx, double dy) {
    MapObject o = source.copy();
    o.id = nextObjectId++;
    o.element = null;
    o.x += dx;
    o.y += dy;
    layer.objects.add(o);
    return o;
  }

  /** The tileset of a gid, or null for 0 / unknown. */
  public Tileset tilesetOf(long gid) {
    long id = gid & 0x1FFFFFFFL;
    Tileset found = null;
    for (Tileset t : tilesets) {
      if (t.firstGid <= id && (found == null || t.firstGid > found.firstGid)) {
        found = t;
      }
    }
    return found;
  }

  // --- undo snapshots ---------------------------------------------------------------

  /** Everything the editor changes, for undo/redo. */
  public record State(int width, int height, List<Tileset> tilesets, List<Layer> layers,
      int nextObjectId) {}

  public State snapshot() {
    List<Tileset> ts = new ArrayList<>();
    for (Tileset t : tilesets) {
      ts.add(t.copy());
    }
    List<Layer> ls = new ArrayList<>();
    for (Layer l : layers) {
      ls.add(l.copy());
    }
    return new State(width, height, ts, ls, nextObjectId);
  }

  public void restore(State state) {
    width = state.width();
    height = state.height();
    tilesets.clear();
    for (Tileset t : state.tilesets()) {
      tilesets.add(t.copy());
    }
    layers.clear();
    for (Layer l : state.layers()) {
      layers.add(l.copy());
    }
    nextObjectId = state.nextObjectId();
  }

  // --- saving ------------------------------------------------------------------------

  /** The map as XML in the form every Scratch for Java version loads. */
  public String toXml() {
    mapElement.setAttribute("width", String.valueOf(width));
    mapElement.setAttribute("height", String.valueOf(height));
    mapElement.setAttribute("tilewidth", String.valueOf(tileWidth));
    mapElement.setAttribute("tileheight", String.valueOf(tileHeight));
    mapElement.setAttribute("infinite", "0");
    // tilesets: embedded, in firstgid order, before the layers
    List<Element> keepTilesets = new ArrayList<>();
    for (Tileset t : tilesets) {
      keepTilesets.add(writeTileset(t));
    }
    for (Element old : children(mapElement, "tileset")) {
      if (!keepTilesets.contains(old)) {
        mapElement.removeChild(old);
      }
    }
    Node firstLayer = firstLayerNode();
    for (Element element : keepTilesets) {
      mapElement.insertBefore(element, firstLayer);
    }
    // layers in model order; layers of groups stay in their group element
    List<Element> written = new ArrayList<>();
    int layerId = 1;
    for (Layer layer : layers) {
      layerId = Math.max(layerId, integer(layer.element, "id", 0) + 1);
    }
    for (Layer layer : layers) {
      Element element = writeLayer(layer, layerId);
      if (!element.hasAttribute("id")) {
        element.setAttribute("id", String.valueOf(layerId++));
      }
      written.add(element);
    }
    // remove deleted layers, then append each layer to its parent in order
    removeStaleLayers(mapElement, written);
    for (Element element : written) {
      Node parent = element.getParentNode() == null ? mapElement : element.getParentNode();
      parent.appendChild(element);
    }
    // keep elements that must come after layers (e.g. editorsettings stays before)
    mapElement.setAttribute("nextlayerid", String.valueOf(layerId));
    mapElement.setAttribute("nextobjectid", String.valueOf(nextObjectId));
    return serialize();
  }

  public void save() throws IOException {
    String xml = toXml();
    Files.createDirectories(file.toAbsolutePath().getParent());
    org.openpatch.scratch4j.core.io.AtomicFiles.writeString(file, xml);
  }

  private Node firstLayerNode() {
    for (Node node = mapElement.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element e && (e.getTagName().equals("layer")
          || e.getTagName().equals("objectgroup") || e.getTagName().equals("group")
          || e.getTagName().equals("imagelayer"))) {
        return node;
      }
    }
    return null;
  }

  private Element writeTileset(Tileset t) {
    Element element = t.element != null && t.externalSource == null ? t.element
        : dom.createElement("tileset");
    // an external .tsx becomes embedded: older libraries cannot load .tsx files;
    // what the .tsx holds besides the image (tiles, animations, Wang sets) moves along
    if (t.externalSource != null) {
      t.externalSource = null;
      t.imageSource = relative(t.image);
      if (t.externalElement != null) {
        for (Node child = t.externalElement.getFirstChild(); child != null;
            child = child.getNextSibling()) {
          if (!(child instanceof Element e && e.getTagName().equals("image"))) {
            element.appendChild(dom.importNode(child, true));
          }
        }
        t.externalElement = null;
      }
    }
    writeAnimations(t, element);
    element.removeAttribute("source");
    element.setAttribute("firstgid", String.valueOf(t.firstGid));
    element.setAttribute("name", t.name);
    element.setAttribute("tilewidth", String.valueOf(t.tileWidth));
    element.setAttribute("tileheight", String.valueOf(t.tileHeight));
    element.setAttribute("tilecount", String.valueOf(t.tileCount));
    element.setAttribute("columns", String.valueOf(t.columns));
    if (t.margin != 0) {
      element.setAttribute("margin", String.valueOf(t.margin));
    }
    if (t.spacing != 0) {
      element.setAttribute("spacing", String.valueOf(t.spacing));
    }
    Element image = first(element, "image");
    if (image == null) {
      image = dom.createElement("image");
      element.insertBefore(image, element.getFirstChild());
    }
    image.setAttribute("source", t.imageSource);
    try {
      var read = javax.imageio.ImageIO.read(t.image.toFile());
      if (read != null) {
        image.setAttribute("width", String.valueOf(read.getWidth()));
        image.setAttribute("height", String.valueOf(read.getHeight()));
      }
    } catch (IOException ignored) {
      // keep whatever size the file had
    }
    t.element = element;
    return element;
  }

  /** The model's animations into the tileset's tile elements (others stay as they are). */
  private void writeAnimations(Tileset t, Element element) {
    for (Element tile : children(element, "tile")) {
      Element animation = first(tile, "animation");
      if (animation != null && !t.animations.containsKey(integer(tile, "id", -1))) {
        tile.removeChild(animation);
        if (children(tile).isEmpty() && tile.getAttributes().getLength() == 1) {
          element.removeChild(tile);
        }
      }
    }
    for (var entry : t.animations.entrySet()) {
      if (entry.getValue().isEmpty()) continue;
      Element tile = null;
      for (Element candidate : children(element, "tile")) {
        if (integer(candidate, "id", -1) == entry.getKey()) tile = candidate;
      }
      if (tile == null) {
        tile = dom.createElement("tile");
        tile.setAttribute("id", String.valueOf(entry.getKey()));
        element.appendChild(tile);
      }
      Element old = first(tile, "animation");
      Element animation = dom.createElement("animation");
      for (int[] frame : entry.getValue()) {
        Element f = dom.createElement("frame");
        f.setAttribute("tileid", String.valueOf(frame[0]));
        f.setAttribute("duration", String.valueOf(frame[1]));
        animation.appendChild(f);
      }
      if (old != null) {
        tile.replaceChild(animation, old);
      } else {
        tile.appendChild(animation);
      }
    }
  }

  private Element writeLayer(Layer layer, int fallbackId) {
    String tag = layer instanceof TileLayer ? "layer" : "objectgroup";
    Element element = layer.element;
    if (element == null) {
      element = dom.createElement(tag);
      layer.element = element;
    }
    element.setAttribute("name", layer.name);
    if (layer.visible) {
      element.removeAttribute("visible");
    } else {
      element.setAttribute("visible", "0");
    }
    if (layer.opacity < 1) {
      element.setAttribute("opacity", String.valueOf(layer.opacity));
    } else {
      element.removeAttribute("opacity");
    }
    // the library reads "type"; Tiled 1.9+ reads both
    element.removeAttribute("class");
    if (layer.type != null && !layer.type.isEmpty()) {
      element.setAttribute("type", layer.type);
    }
    if (layer instanceof TileLayer tiles) {
      element.setAttribute("width", String.valueOf(width));
      element.setAttribute("height", String.valueOf(height));
      Element data = first(element, "data");
      if (data == null) {
        data = dom.createElement("data");
        element.appendChild(data);
      }
      data.setAttribute("encoding", "csv");
      data.removeAttribute("compression");
      while (data.getFirstChild() != null) {
        data.removeChild(data.getFirstChild());
      }
      data.setTextContent(csv(tiles.gids));
    } else if (layer instanceof ObjectLayer objects) {
      List<Element> keep = new ArrayList<>();
      for (MapObject o : objects.objects) {
        keep.add(writeObject(o));
      }
      for (Element old : children(element, "object")) {
        if (!keep.contains(old)) {
          element.removeChild(old);
        }
      }
      for (Element e : keep) {
        element.appendChild(e);
      }
    }
    return element;
  }

  private Element writeObject(MapObject o) {
    Element element = o.element == null ? dom.createElement("object") : o.element;
    o.element = element;
    element.setAttribute("id", String.valueOf(o.id));
    setOrRemove(element, "name", o.name);
    element.removeAttribute("class");
    setOrRemove(element, "type", o.type);
    element.setAttribute("x", number(o.x));
    element.setAttribute("y", number(o.y));
    if (o.shape == Shape.POINT || (o.width == 0 && o.height == 0 && o.shape != Shape.TILE)) {
      element.removeAttribute("width");
      element.removeAttribute("height");
    } else {
      element.setAttribute("width", number(o.width));
      element.setAttribute("height", number(o.height));
    }
    for (String child : new String[] {"ellipse", "point", "polygon", "polyline"}) {
      Element old = first(element, child);
      if (old != null) {
        element.removeChild(old);
      }
    }
    switch (o.shape) {
      case ELLIPSE -> element.appendChild(dom.createElement("ellipse"));
      case POINT -> element.appendChild(dom.createElement("point"));
      case POLYGON, POLYLINE -> {
        Element poly = dom.createElement(o.shape == Shape.POLYGON ? "polygon" : "polyline");
        StringBuilder sb = new StringBuilder();
        for (double[] p : o.points) {
          sb.append(sb.length() == 0 ? "" : " ").append(number(p[0])).append(',')
              .append(number(p[1]));
        }
        poly.setAttribute("points", sb.toString());
        element.appendChild(poly);
      }
      case TILE -> element.setAttribute("gid", String.valueOf(o.gid));
      default -> { }
    }
    Element properties = first(element, "properties");
    if (properties != null) {
      element.removeChild(properties);
    }
    if (!o.properties.isEmpty()) {
      properties = dom.createElement("properties");
      for (Map.Entry<String, String[]> p : o.properties.entrySet()) {
        Element property = dom.createElement("property");
        property.setAttribute("name", p.getKey());
        if (!"string".equals(p.getValue()[0])) {
          property.setAttribute("type", p.getValue()[0]);
        }
        property.setAttribute("value", p.getValue()[1]);
        properties.appendChild(property);
      }
      element.insertBefore(properties, element.getFirstChild());
    }
    return element;
  }

  private void removeStaleLayers(Element parent, List<Element> keep) {
    for (Node node = parent.getFirstChild(); node != null; ) {
      Node next = node.getNextSibling();
      if (node instanceof Element e) {
        if ((e.getTagName().equals("layer") || e.getTagName().equals("objectgroup"))
            && !keep.contains(e)) {
          parent.removeChild(e);
        } else if (e.getTagName().equals("group")) {
          removeStaleLayers(e, keep);
        }
      }
      node = next;
    }
  }

  /** Tiled's attribute order per element, so saved files diff cleanly against Tiled's. */
  private static final Map<String, List<String>> ORDER = Map.ofEntries(
      Map.entry("frame", List.of("tileid", "duration")),
      Map.entry("tile", List.of("id", "type", "class", "probability")),
      Map.entry("chunk", List.of("x", "y", "width", "height")),
      Map.entry("export", List.of("target", "format")),
      Map.entry("text", List.of("fontfamily", "pixelsize", "wrap", "color", "bold", "italic",
          "halign", "valign")),
      Map.entry("imagelayer", List.of("id", "name", "offsetx", "offsety")),
      Map.entry("map", List.of("version", "tiledversion", "class", "orientation",
          "renderorder", "width", "height", "tilewidth", "tileheight", "infinite",
          "nextlayerid", "nextobjectid")),
      Map.entry("tileset", List.of("firstgid", "source", "name", "class", "tilewidth",
          "tileheight", "spacing", "margin", "tilecount", "columns")),
      Map.entry("image", List.of("source", "width", "height")),
      Map.entry("layer", List.of("id", "name", "class", "type", "width", "height", "visible",
          "opacity")),
      Map.entry("objectgroup", List.of("id", "name", "class", "type", "visible", "opacity")),
      Map.entry("group", List.of("id", "name")),
      Map.entry("object", List.of("id", "name", "type", "gid", "x", "y", "width", "height")),
      Map.entry("property", List.of("name", "type", "propertytype", "value")),
      Map.entry("data", List.of("encoding", "compression")));

  /** XML like Tiled writes it: 1-space indent, Tiled's attribute order. */
  private String serialize() {
    StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    write(mapElement, 0, out);
    return out.toString();
  }

  private static void write(Element element, int depth, StringBuilder out) {
    String indent = " ".repeat(depth);
    out.append(indent).append('<').append(element.getTagName());
    var attributes = element.getAttributes();
    List<String> names = new ArrayList<>();
    for (int i = 0; i < attributes.getLength(); i++) {
      names.add(attributes.item(i).getNodeName());
    }
    List<String> order = ORDER.getOrDefault(element.getTagName(), List.of());
    names.sort(java.util.Comparator.comparingInt((String n) -> {
      int i = order.indexOf(n);
      return i < 0 ? Integer.MAX_VALUE : i;
    }));
    for (String name : names) {
      out.append(' ').append(name).append("=\"").append(escape(element.getAttribute(name)))
          .append('"');
    }
    List<Element> elements = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element e) {
        elements.add(e);
      } else if (child.getNodeType() == Node.TEXT_NODE
          || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        text.append(child.getTextContent());
      }
    }
    if (elements.isEmpty() && text.toString().isBlank()) {
      out.append("/>\n");
      return;
    }
    out.append('>');
    if (elements.isEmpty()) {
      // layer data and multi-line property values keep their text exactly
      out.append(escape(text.toString())).append("</").append(element.getTagName())
          .append(">\n");
      return;
    }
    out.append('\n');
    for (Element child : elements) {
      write(child, depth + 1, out);
    }
    out.append(indent).append("</").append(element.getTagName()).append(">\n");
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private String csv(long[] gids) {
    StringBuilder sb = new StringBuilder("\n");
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        sb.append(gids[y * width + x]);
        if (y < height - 1 || x < width - 1) {
          sb.append(',');
        }
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  /** A path relative to the map's folder, with forward slashes. */
  String relative(Path absolute) {
    Path base = file.toAbsolutePath().getParent();
    return base.relativize(absolute.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  // --- small XML helpers ---------------------------------------------------------------

  private static javax.xml.parsers.DocumentBuilder builder() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder();
  }

  static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) out.add(e);
    }
    return out;
  }

  static List<Element> children(Element parent, String tag) {
    List<Element> out = new ArrayList<>();
    NodeList list = parent.getChildNodes();
    for (int i = 0; i < list.getLength(); i++) {
      if (list.item(i) instanceof Element e && e.getTagName().equals(tag)) {
        out.add(e);
      }
    }
    return out;
  }

  static Element first(Element parent, String tag) {
    List<Element> list = children(parent, tag);
    return list.isEmpty() ? null : list.get(0);
  }

  private static String attribute(Element e, String name, String fallback) {
    return e.hasAttribute(name) ? e.getAttribute(name) : fallback;
  }

  private static int integer(Element e, String name, int fallback) {
    if (e == null || !e.hasAttribute(name)) {
      return fallback;
    }
    try {
      return (int) Double.parseDouble(e.getAttribute(name));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static double decimal(Element e, String name) {
    return e.hasAttribute(name) ? Double.parseDouble(e.getAttribute(name)) : 0;
  }

  private static void setOrRemove(Element e, String name, String value) {
    if (value == null || value.isEmpty()) {
      e.removeAttribute(name);
    } else {
      e.setAttribute(name, value);
    }
  }

  static String number(double value) {
    return value == Math.rint(value) && !Double.isInfinite(value)
        ? String.valueOf((long) value) : String.format(Locale.ROOT, "%s", value);
  }

  @Override
  public String toString() {
    return "TmxDocument[" + file + ", " + width + "x" + height + "]";
  }

  /** Reads a whole file as text (tests and the compatibility check). */
  static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  /** Visible for {@link TmxCompatibility}: the map element. */
  Element mapElement() {
    return mapElement;
  }

  /** Visible for subclass copies. */
  static void copyInto(Layer target, Layer source) {
    target.name = source.name;
    target.visible = source.visible;
    target.opacity = source.opacity;
    target.group = source.group;
    target.type = source.type;
    target.element = source.element;
  }
}
