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
 * map.stampLayerToBackground("Ground");
 * </pre>
 *
 * written after the stage's managed setup region (or after {@code super(...)}),
 * with the import it needs.
 */
public final class MapCode {

  private static final String IMPORT = "import org.openpatch.scratch.extensions.tiled.TiledMap;";

  private MapCode() {}

  public static String insert(String source, String mapPath, List<String> backgroundLayers) {
    if (source.contains("new TiledMap(\"" + mapPath + "\"")) {
      throw new IllegalArgumentException("This stage already loads " + mapPath);
    }
    Matcher anchor = Pattern.compile("(?m)^([ \\t]*)// scratch4j:end setup[^\\n]*\\n")
        .matcher(source);
    String indent;
    int at;
    if (anchor.find()) {
      indent = anchor.group(1);
      at = anchor.end();
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
        + "(?:[ \\t]*\\w+\\.stampLayerTo\\w+\\([^\\n]*\\n)*").matcher(source);
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
      code.append(indent).append(variable).append(".stampLayerToBackground(\"")
          .append(layer.replace("\\", "\\\\").replace("\"", "\\\"")).append("\");\n");
    }
    String result = source.substring(0, at) + code + source.substring(at);
    return withImport(result);
  }

  private static String withImport(String source) {
    if (source.contains(IMPORT)
        || source.contains("import org.openpatch.scratch.extensions.tiled.*;")) {
      return source;
    }
    Matcher imports = Pattern.compile("(?m)^import\\s+[^;]+;[ \\t]*\\n").matcher(source);
    int at = 0;
    while (imports.find()) {
      at = imports.end();
    }
    return source.substring(0, at) + IMPORT + "\n" + (at == 0 ? "\n" : "")
        + source.substring(at);
  }
}
