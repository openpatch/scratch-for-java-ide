package org.openpatch.scratch4j.core.tiled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Which maps a stage shows: {@code new TiledMap("assets/maps/a.tmx", this)}
 * names one; a computed path ({@code "./" + mapFile + ".tmx"}, as in the
 * library's tiled demo) could be any map of the project, so every map is a
 * candidate, those whose name the code mentions ({@code "Level1"}) first.
 */
public final class MapReferences {

  private static final Pattern LITERAL =
      Pattern.compile("new\\s+TiledMap\\(\\s*\"([^\"]+\\.tmx)\"\\s*,");
  private static final Pattern ANY = Pattern.compile("new\\s+TiledMap\\(");
  private static final Pattern STRING = Pattern.compile("\"([^\"\\\\\\n]*)\"");

  private MapReferences() {}

  /** The candidate maps for a stage's source, most likely first (empty: no map). */
  public static List<Path> forStage(Path root, String stageSource) {
    List<Path> out = new ArrayList<>();
    Matcher literal = LITERAL.matcher(stageSource);
    while (literal.find()) {
      Path map = root.resolve(literal.group(1)).normalize();
      if (Files.isRegularFile(map) && !out.contains(map)) out.add(map);
    }
    int literals = 0;
    for (Matcher any = ANY.matcher(stageSource); any.find(); ) literals++;
    Matcher count = LITERAL.matcher(stageSource);
    int named = 0;
    while (count.find()) named++;
    if (literals == named) {
      return out;
    }
    List<String> mentioned = mentionedStrings(root);
    List<Path> all = allMaps(root);
    all.sort(Comparator.comparing((Path map) -> {
      String base = map.getFileName().toString().replaceFirst("\\.tmx$", "");
      return mentioned.contains(base) || mentioned.contains(map.getFileName().toString())
          ? 0 : 1;
    }).thenComparing(Path::toString));
    for (Path map : all) {
      if (!out.contains(map)) out.add(map);
    }
    return out;
  }

  private static List<Path> allMaps(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return new ArrayList<>(files.filter(p -> p.toString().endsWith(".tmx"))
          .filter(p -> !root.relativize(p).toString().startsWith(".scratch4j")).toList());
    } catch (IOException e) {
      return new ArrayList<>();
    }
  }

  private static List<String> mentionedStrings(Path root) {
    List<String> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(root)) {
      for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        Matcher m = STRING.matcher(Files.readString(source));
        while (m.find()) out.add(m.group(1));
      }
    } catch (IOException e) {
      // no hints
    }
    return out;
  }
}
