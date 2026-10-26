package org.openpatch.scratch4j.core.tiled;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code that puts a map into a stage:
 *
 * <pre>
 * // the map from the map editor
 * TiledMap map = new TiledMap("assets/maps/level.tmx", this);
 * map.showLayer("Ground", Layer.BACKGROUND);
 * </pre>
 *
 * written after the stage designer's block in the constructor (or after {@code super(...)}),
 * with the import it needs.
 */
public final class MapCode {

  private static final String IMPORT = "import org.openpatch.scratch.tiled.TiledMap;";

  private MapCode() {}

  public static String insert(String source, String mapPath, List<String> backgroundLayers) {
    if (source.contains("new TiledMap(\"" + mapPath + "\"")) {
      throw new IllegalArgumentException("This stage already loads " + mapPath);
    }
    var setup = org.openpatch.scratch4j.core.region.RegionParser.parse(source).stream()
        .filter(r -> r.id().equals("setup")).findFirst().orElse(null);
    String indent;
    int at;
    if (setup != null) {
      // after the line that ends the stage designer's block in the constructor
      indent = setup.indent();
      int lineEnd = source.indexOf('\n', setup.endOffset());
      at = lineEnd < 0 ? source.length() : lineEnd + 1;
    } else {
      Matcher superCall = Pattern.compile("(?m)^([ \\t]*)super\\([^;]*\\);[^\\n]*\\n")
          .matcher(source);
      if (!superCall.find()) {
        throw new IllegalArgumentException(
            "The stage has no setup region or super(...) call to put the map after");
      }
      indent = superCall.group(1);
      at = superCall.end();
    }
    // a second map goes after the first one's lines, not above them
    Matcher earlier = Pattern.compile("(?m)^[ \\t]*// the map from the map editor\\n"
        + "[ \\t]*TiledMap \\w+ = new TiledMap\\([^\\n]*\\n"
        + "(?:[ \\t]*\\w+\\.showLayer\\([^\\n]*\\n)*").matcher(source);
    while (earlier.find()) {
      at = Math.max(at, earlier.end());
    }
    String variable = "map";
    for (int i = 2; Pattern.compile("\\bTiledMap\\s+" + variable + "\\b").matcher(source).find();
        i++) {
      variable = "map" + i;
    }
    StringBuilder code = new StringBuilder("\n").append(indent)
        .append("// the map from the map editor\n").append(indent)
        .append("TiledMap ").append(variable).append(" = new TiledMap(\"").append(mapPath)
        .append("\", this);\n");
    for (String layer : backgroundLayers) {
      // Scratch for Java 7: shown once, drawn by the map every frame
      code.append(indent).append(variable).append(".showLayer(\"")
          .append(layer.replace("\\", "\\\\").replace("\"", "\\\"")).append("\", Layer.BACKGROUND);\n");
    }
    String result = source.substring(0, at) + code + source.substring(at);
    return withImport(result);
  }

  private static String withImport(String source) {
    // showLayer(..., Layer.BACKGROUND) needs Layer, unless the whole package is imported
    if (!source.contains("import org.openpatch.scratch.*;")
        && !source.contains("import org.openpatch.scratch.Layer;")) {
      source = addImport(source, "import org.openpatch.scratch.Layer;");
    }
    if (source.contains(IMPORT)
        || source.contains("import org.openpatch.scratch.tiled.*;")) {
      return source;
    }
    return addImport(source, IMPORT);
  }

  private static String addImport(String source, String line) {
    Matcher imports = Pattern.compile("(?m)^import\\s+[^;]+;[ \\t]*\\n").matcher(source);
    int at = 0;
    while (imports.find()) {
      at = imports.end();
    }
    return source.substring(0, at) + line + "\n" + (at == 0 ? "\n" : "")
        + source.substring(at);
  }
}
