package org.openpatch.scratch4j.core.assets;

/**
 * One built-in image: a crop of a Kenney atlas sheet inside the Scratch for
 * Java jar (CC0, kenney.nl). Atlas entries that were drawn facing another way
 * carry their drawn {@code direction}; the library rotates them to face right
 * (direction 90) on load.
 */
public record BuiltinImage(
    String name,
    String sheet,
    String sheetPath,
    int x,
    int y,
    int width,
    int height,
    double direction,
    String referenceName) {

  /** The unambiguous {@code sheet/name} reference usable in {@code addCostume}. */
  public String qualifiedName() {
    return sheet + "/" + name;
  }

  @Override
  public String toString() {
    return qualifiedName();
  }
}
