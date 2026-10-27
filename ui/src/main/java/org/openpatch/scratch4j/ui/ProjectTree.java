package org.openpatch.scratch4j.ui;

import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the Files tree shows of a project folder. Generated and build folders
 * are hidden. In {@code assets}, a folder with nothing to show is hidden, and
 * so is {@code assets} itself when all of its folders are empty: a new
 * project shows only its code. Dot files there (.gitkeep) do not count.
 * Folders the student just made stay visible while still empty. Folders
 * start closed and stay as the student left them across reloads.
 */
final class ProjectTree {

  static final Set<String> HIDDEN = Set.of(".scratch4j", "target", ".git", "build", "export");

  private ProjectTree() {}

  /** The tree for the project at {@code root}; folders in {@code expanded} start open. */
  static TreeItem<Path> build(Path root, Set<Path> expanded, Set<Path> keepVisible) {
    TreeItem<Path> item = build(root, expanded, true, false, keepVisible);
    return item != null ? item : new TreeItem<>(root);
  }

  /** The item for {@code dir}, or null for an asset folder with nothing to show. */
  private static TreeItem<Path> build(Path dir, Set<Path> expanded, boolean isRoot,
      boolean inAssets, Set<Path> keepVisible) {
    TreeItem<Path> item = new TreeItem<>(dir);
    // folders start closed; the root (not shown) always lists its children
    item.setExpanded(isRoot || expanded.contains(dir));
    try (Stream<Path> children = Files.list(dir)) {
      children.filter(p -> !HIDDEN.contains(p.getFileName().toString()))
          .filter(p -> !inAssets || !p.getFileName().toString().startsWith("."))
          .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
              .thenComparing(p -> p.getFileName().toString().toLowerCase()))
          .forEach(p -> {
            TreeItem<Path> child = !Files.isDirectory(p) ? new TreeItem<>(p)
                : build(p, expanded, false,
                    inAssets || isRoot && p.getFileName().toString().equals("assets"),
                    keepVisible);
            if (child != null) {
              item.getChildren().add(child);
            }
          });
    } catch (IOException e) {
      // unreadable folder: show it empty
    }
    if (!isRoot && inAssets && item.getChildren().isEmpty()
        && !keepVisible.contains(dir.toAbsolutePath().normalize())) {
      return null;
    }
    return item;
  }

  /**
   * The index of the first name from {@code start} on (wrapping around) that
   * starts with {@code query}, ignoring case, else of the first that contains
   * it; -1 when none does.
   */
  static int match(List<String> names, int start, String query) {
    String q = query.toLowerCase(java.util.Locale.ROOT);
    for (int contains = 0; contains < 2; contains++) {
      for (int i = 0; i < names.size(); i++) {
        int at = Math.floorMod(start + i, names.size());
        String name = names.get(at).toLowerCase(java.util.Locale.ROOT);
        if (contains == 0 ? name.startsWith(q) : name.contains(q)) return at;
      }
    }
    return -1;
  }
}
