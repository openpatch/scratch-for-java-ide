package org.openpatch.scratch4j.core.assets;

import org.openpatch.scratch.internal.BuiltinAssets;
import org.openpatch.scratch.internal.BuiltinSounds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Index over the assets that ship inside the Scratch for Java jar: the four
 * Kenney atlases and the built-in sounds. Reads the library's own registries
 * ({@code internal.BuiltinAssets} / {@code internal.BuiltinSounds}) — they are
 * pure XML/name parsing and do not start Processing; a contract test pins the
 * library version this relies on.
 */
public final class BuiltinAssetIndex {

  private static final BuiltinAssetIndex INSTANCE = new BuiltinAssetIndex();

  private final List<BuiltinImage> images;
  private final List<String> sounds;

  private BuiltinAssetIndex() {
    try (var in = BuiltinAssetIndex.class.getResourceAsStream("/catalogs/assets.json")) {
      if (in != null) {
        var catalog = tools.jackson.databind.json.JsonMapper.builder().build().readTree(in);
        if (catalog.path("schemaVersion").asInt() != 1) throw new IllegalStateException("Unsupported asset catalog");
        List<BuiltinImage> loaded = new ArrayList<>();
        for (var image : catalog.path("images")) {
          // A development catalog may describe assets newer than Studio's bundled library.
          if (!BuiltinAssets.contains(image.path("id").asText())) continue;
          loaded.add(new BuiltinImage(image.path("name").asText(), image.path("sheet").asText(),
              image.path("sheetPath").asText(), image.path("x").asInt(), image.path("y").asInt(),
              image.path("width").asInt(), image.path("height").asInt(), image.path("direction").asDouble(),
              image.path("referenceName").asText()));
        }
        images = List.copyOf(loaded);
        List<String> names = new ArrayList<>();
        catalog.path("sounds").forEach(sound -> names.add(sound.path("id").asText()));
        sounds = List.copyOf(names);
        return;
      }
    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    List<BuiltinImage> loaded = new ArrayList<>();
    for (BuiltinAssets.Entry e : BuiltinAssets.getEntries()) {
      loaded.add(new BuiltinImage(e.name, e.sheet, e.sheetPath, e.x, e.y,
          e.width, e.height, e.direction, BuiltinAssets.getReferenceName(e)));
    }
    this.images = List.copyOf(loaded);
    this.sounds = List.copyOf(BuiltinSounds.getNames());
  }

  public static BuiltinAssetIndex get() {
    return INSTANCE;
  }

  public List<BuiltinImage> images() {
    return images;
  }

  public List<String> imageNames() {
    return images.stream().map(BuiltinImage::referenceName).toList();
  }

  public List<String> sounds() {
    return sounds;
  }

  /** The image for a bare or qualified name, case-insensitive, as in the library. */
  public Optional<BuiltinImage> image(String name) {
    if (name == null) {
      return Optional.empty();
    }
    return images.stream()
        .filter(i -> i.name().equalsIgnoreCase(name) || i.qualifiedName().equalsIgnoreCase(name))
        .findFirst();
  }

  public boolean hasSound(String name) {
    return name != null && sounds.stream().anyMatch(s -> s.equalsIgnoreCase(name));
  }

  /** "Did you mean" suggestions for image names, via the library's suggest(). */
  public List<String> suggestImages(String input, int limit) {
    return BuiltinAssets.suggest(input == null ? "" : input, limit);
  }

  /** "Did you mean" suggestions for sound names, via the library's suggest(). */
  public List<String> suggestSounds(String input, int limit) {
    return BuiltinSounds.suggest(input == null ? "" : input, limit);
  }
}
