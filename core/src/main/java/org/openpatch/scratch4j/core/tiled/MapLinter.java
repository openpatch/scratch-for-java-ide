package org.openpatch.scratch4j.core.tiled;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.openpatch.scratch4j.core.lint.DidYouMean;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks the code that uses Tiled maps against the maps themselves:
 *
 * <pre>
 * new TiledMap("assets/maps/level.tmx", this)   // exists? loads in Scratch for Java?
 * map.stampLayerToBackground("Ground")          // a tile layer of the map?
 * map.getObjectsFromLayer("Objects")            // an object layer of the map?
 * </pre>
 *
 * Layer names are checked against the maps the file loads by name, or against
 * every map of the project when the path is computed ({@code "./" + level + ".tmx"}).
 */
public final class MapLinter {

  /** One problem in a source file (1-based line). */
  public record Finding(Path file, long line, String message, String explanation,
      List<String> suggestions) {}

  /** The layers of one map: names of tile layers and of object layers. */
  public record Layers(Path map, List<String> tiles, List<String> objects) {}

  private final Language language;
  private final Map<Path, Layers> cache = new LinkedHashMap<>();

  private String libraryVersion;

  public MapLinter(Language language) {
    this.language = language;
  }

  /** Only what the project's library version cannot load counts (null: any version). */
  public MapLinter withLibraryVersion(String version) {
    this.libraryVersion = version;
    return this;
  }

  public List<Finding> lint(Path projectRoot, Path file, String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null || !source.contains("TiledMap")) {
      return List.of();
    }
    JavacTask task = (JavacTask) compiler.getTask(null, null, null, List.of(), null,
        List.of(new Source(file, source)));
    List<Finding> findings = new ArrayList<>();
    try {
      for (CompilationUnitTree unit : task.parse()) {
        var positions = Trees.instance(task).getSourcePositions();
        List<long[]> lines = new ArrayList<>();
        List<String> literalMaps = new ArrayList<>();
        boolean[] computedPath = {false};
        List<Object[]> layerCalls = new ArrayList<>();
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitNewClass(NewClassTree node, Void p) {
            if (node.getIdentifier().toString().endsWith("TiledMap")
                && !node.getArguments().isEmpty()) {
              if (node.getArguments().get(0) instanceof LiteralTree lit
                  && lit.getValue() instanceof String path) {
                long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit,
                    lit));
                literalMaps.add(path);
                checkMap(projectRoot, file, path, line, findings);
              } else {
                computedPath[0] = true;
              }
            }
            return super.visitNewClass(node, p);
          }

          @Override
          public Void visitMethodInvocation(MethodInvocationTree call, Void p) {
            String name = methodName(call);
            if (("getObjectsFromLayer".equals(name) || "stampLayerToBackground".equals(name)
                || "stampLayerToForeground".equals(name)) && call.getArguments().size() == 1
                && call.getArguments().get(0) instanceof LiteralTree lit
                && lit.getValue() instanceof String layer) {
              layerCalls.add(new Object[] {name, layer,
                  unit.getLineMap().getLineNumber(positions.getStartPosition(unit, lit))});
            }
            return super.visitMethodInvocation(call, p);
          }
        }.scan(unit, null);
        if (layerCalls.isEmpty()) {
          continue;
        }
        List<Layers> maps = new ArrayList<>();
        if (computedPath[0] || literalMaps.isEmpty()) {
          maps.addAll(allMaps(projectRoot));
        }
        for (String path : literalMaps) {
          Layers layers = layers(projectRoot.resolve(path).normalize());
          if (layers != null) maps.add(layers);
        }
        if (maps.isEmpty()) {
          continue;
        }
        for (Object[] call : layerCalls) {
          checkLayer(file, (String) call[0], (String) call[1], (long) call[2], maps, findings);
        }
      }
    } catch (IOException e) {
      return List.of();
    }
    return findings;
  }

  private void checkMap(Path root, Path file, String path, long line, List<Finding> findings) {
    Path map = root.resolve(path).normalize();
    if (!Files.isRegularFile(map)) {
      findings.add(new Finding(file, line, de()
          ? "Die Karte \"" + path + "\" gibt es nicht."
          : "The map \"" + path + "\" does not exist.",
          de() ? "Pfade beginnen beim Projektordner, z. B. assets/maps/level1.tmx."
              : "Paths start at the project folder, e.g. assets/maps/level1.tmx.",
          List.of()));
      return;
    }
    try {
      for (TmxCompatibility.Issue issue : TmxCompatibility.check(TmxDocument.open(map),
          libraryVersion)) {
        findings.add(new Finding(file, line, map.getFileName() + ": "
            + TmxCompatibility.describe(issue, language),
            issue.fixable()
                ? (de() ? "Öffne die Karte in der IDE und klicke auf „Für Scratch for Java "
                    + "passend machen“."
                    : "Open the map in the IDE and click “Make it work with Scratch for "
                    + "Java”.")
                : (de() ? "Das muss in Tiled geändert werden." : "Change this in Tiled."),
            List.of()));
      }
    } catch (IOException | RuntimeException e) {
      findings.add(new Finding(file, line, (de() ? "Die Karte kann nicht gelesen werden: "
          : "The map cannot be read: ") + e.getMessage(), null, List.of()));
    }
  }

  private void checkLayer(Path file, String method, String layer, long line, List<Layers> maps,
      List<Finding> findings) {
    boolean objects = method.equals("getObjectsFromLayer");
    Set<String> wanted = new LinkedHashSet<>();
    Set<String> other = new LinkedHashSet<>();
    for (Layers map : maps) {
      wanted.addAll(objects ? map.objects() : map.tiles());
      other.addAll(objects ? map.tiles() : map.objects());
    }
    if (wanted.contains(layer)) {
      return;
    }
    String mapNames = String.join(", ", maps.stream()
        .map(m -> m.map().getFileName().toString()).toList());
    if (other.contains(layer)) {
      findings.add(new Finding(file, line, objects
          ? (de() ? "\"" + layer + "\" ist eine Kachelebene, keine Objektebene."
              : "\"" + layer + "\" is a tile layer, not an object layer.")
          : (de() ? "\"" + layer + "\" ist eine Objektebene: lies sie mit getObjectsFromLayer."
              : "\"" + layer + "\" is an object layer: read it with getObjectsFromLayer."),
          null, List.copyOf(wanted)));
      return;
    }
    String kind = objects ? (de() ? "Objektebene" : "object layer")
        : (de() ? "Kachelebene" : "tile layer");
    findings.add(new Finding(file, line, de()
        ? "Keine " + kind + " \"" + layer + "\" in " + mapNames + "."
        : "No " + kind + " \"" + layer + "\" in " + mapNames + ".",
        de() ? "Ebenennamen müssen genau so geschrieben werden wie in der Karte "
            + "(Groß-/Kleinschreibung zählt)."
            : "Layer names must be written exactly as in the map (upper/lower case counts).",
        DidYouMean.suggest(layer, wanted, 3)));
  }

  /** The layers of every map in the project (for completion too). */
  public List<Layers> allMaps(Path root) {
    List<Layers> out = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path map : files.filter(p -> p.toString().endsWith(".tmx"))
          .filter(p -> !root.relativize(p).toString().startsWith(".scratch4j"))
          .sorted().toList()) {
        Layers layers = layers(map);
        if (layers != null) out.add(layers);
      }
    } catch (IOException e) {
      // no maps
    }
    return out;
  }

  private Layers layers(Path map) {
    return cache.computeIfAbsent(map, m -> {
      try {
        TmxDocument doc = TmxDocument.open(m);
        List<String> tiles = new ArrayList<>();
        List<String> objects = new ArrayList<>();
        for (TmxDocument.Layer layer : doc.layers) {
          (layer instanceof TmxDocument.TileLayer ? tiles : objects).add(layer.name);
        }
        return new Layers(m, tiles, objects);
      } catch (IOException | RuntimeException e) {
        return null;
      }
    });
  }

  private boolean de() {
    return language == Language.DE;
  }

  private static String methodName(MethodInvocationTree call) {
    ExpressionTree select = call.getMethodSelect();
    if (select instanceof IdentifierTree id) return id.getName().toString();
    if (select instanceof MemberSelectTree member) return member.getIdentifier().toString();
    return null;
  }

  private static final class Source extends SimpleJavaFileObject {
    private final String text;

    Source(Path file, String text) {
      super(URI.create("string:///" + file.getFileName()), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }
}
