package org.openpatch.scratch4j.core.uml;

import org.openpatch.scratch4j.core.io.AtomicFiles;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the student moved the classes of the class diagram, kept in
 * {@code .scratch4j/diagram.json}. Without the file the diagram arranges
 * itself.
 *
 * <pre>
 * { "version": 1, "classes": { "Player": [20, 140], "World": [260, 140] } }
 * </pre>
 */
public final class DiagramPositions {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  record Stored(int version, Map<String, double[]> classes) {}

  private DiagramPositions() {}

  /** The top-left corner of each class box the student placed; empty when none. */
  public static Map<String, double[]> load(Path projectRoot) {
    Path file = file(projectRoot);
    if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
    try {
      Stored stored = MAPPER.readValue(file.toFile(), Stored.class);
      Map<String, double[]> positions = new LinkedHashMap<>();
      if (stored != null && stored.classes() != null) {
        stored.classes().forEach((name, at) -> {
          if (at != null && at.length == 2) positions.put(name, at.clone());
        });
      }
      return positions;
    } catch (RuntimeException e) {
      // a broken file only costs the arrangement
      return new LinkedHashMap<>();
    }
  }

  /** Keeps the positions; none removes the file, so the diagram arranges itself again. */
  public static void save(Path projectRoot, Map<String, double[]> positions) throws IOException {
    Path file = file(projectRoot);
    if (positions.isEmpty()) {
      Files.deleteIfExists(file);
      return;
    }
    Map<String, double[]> rounded = new LinkedHashMap<>();
    positions.forEach((name, at) -> rounded.put(name,
        new double[] {Math.round(at[0]), Math.round(at[1])}));
    AtomicFiles.writeString(file, MAPPER.writerWithDefaultPrettyPrinter()
        .writeValueAsString(new Stored(1, rounded)));
  }

  private static Path file(Path projectRoot) {
    return projectRoot.resolve(".scratch4j/diagram.json");
  }
}
