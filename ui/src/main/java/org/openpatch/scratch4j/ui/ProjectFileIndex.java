package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The project's files as paths from the project folder ("assets/cat.png"),
 * for the suggestions inside asset strings. Walked once, then again in the
 * background when the list is a few seconds old: typing never waits for the
 * disk, even with thousands of files. A file added a moment ago shows up on
 * the next key after the walk.
 */
final class ProjectFileIndex {

  static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "tga", "svg");
  static final Set<String> SOUNDS = Set.of("wav", "mp3", "ogg", "aif", "aiff", "flac");
  static final Set<String> MAPS = Set.of("tmx");

  /** Folders that hold no assets of the program (IDE data, builds, libraries). */
  private static final Set<String> SKIPPED =
      Set.of(".scratch4j", "+libs", "build", "target", ".git", ".vscode", "export");
  private static final long STALE_MILLIS = 2000;
  /** A runaway folder (a copied home directory) does not walk forever. */
  private static final int MAX_FILES = 20_000;

  private static final Map<Path, ProjectFileIndex> BY_ROOT = new ConcurrentHashMap<>();

  private final Path root;
  private volatile List<String> paths;
  private volatile long walked;
  private final AtomicBoolean walking = new AtomicBoolean();

  private ProjectFileIndex(Path root) {
    this.root = root;
  }

  /** The files of {@code root} whose extension is one of {@code extensions}. */
  static List<String> files(Path root, Set<String> extensions) {
    ProjectFileIndex index = BY_ROOT.computeIfAbsent(root.toAbsolutePath().normalize(),
        ProjectFileIndex::new);
    List<String> all = index.paths();
    List<String> found = new ArrayList<>();
    for (String path : all) {
      int dot = path.lastIndexOf('.');
      if (dot > 0 && extensions.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT))) {
        found.add(path);
      }
    }
    return found;
  }

  /** A map's layers as read, with the file's state then (read again once it changed). */
  private record MapLayers(java.nio.file.attribute.FileTime modified, long size,
      org.openpatch.scratch4j.core.tiled.MapLinter.Layers layers) {}

  private final Map<Path, MapLayers> layers = new ConcurrentHashMap<>();

  /**
   * The layers of every map of {@code root}. A map is parsed when it is new or
   * changed on disk; otherwise its layers come from memory, so the suggestions
   * in {@code getObjectsFromLayer("...")} do not parse every map on every key.
   */
  static List<org.openpatch.scratch4j.core.tiled.MapLinter.Layers> mapLayers(Path root) {
    ProjectFileIndex index = BY_ROOT.computeIfAbsent(root.toAbsolutePath().normalize(),
        ProjectFileIndex::new);
    List<String> maps = files(root, MAPS);
    List<org.openpatch.scratch4j.core.tiled.MapLinter.Layers> found = new ArrayList<>();
    for (String relative : maps) {
      Path map = index.root.resolve(relative);
      try {
        var attributes = Files.readAttributes(map, BasicFileAttributes.class);
        MapLayers cached = index.layers.get(map);
        if (cached == null || !cached.modified().equals(attributes.lastModifiedTime())
            || cached.size() != attributes.size()) {
          cached = new MapLayers(attributes.lastModifiedTime(), attributes.size(),
              org.openpatch.scratch4j.core.tiled.MapLinter.readLayers(map));
          index.layers.put(map, cached);
        }
        if (cached.layers() != null) found.add(cached.layers());
      } catch (IOException gone) {
        index.layers.remove(map); // deleted since the last walk
      }
    }
    index.layers.keySet().retainAll(maps.stream().map(index.root::resolve).toList());
    return found;
  }

  private List<String> paths() {
    if (paths == null) {
      // the first suggestions of a session wait for one walk
      paths = walk();
      walked = System.currentTimeMillis();
    } else if (System.currentTimeMillis() - walked > STALE_MILLIS
        && walking.compareAndSet(false, true)) {
      Thread worker = new Thread(() -> {
        try {
          paths = walk();
          walked = System.currentTimeMillis();
        } finally {
          walking.set(false);
        }
      }, "project-file-index");
      worker.setDaemon(true);
      worker.start();
    }
    return paths;
  }

  private List<String> walk() {
    List<String> found = new ArrayList<>();
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
          return !dir.equals(root) && (SKIPPED.contains(name) || name.startsWith("."))
              ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile()) {
            found.add(root.relativize(file).toString().replace('\\', '/'));
          }
          return found.size() >= MAX_FILES ? FileVisitResult.TERMINATE
              : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      // an unreadable project folder: no project files
    }
    found.sort(String.CASE_INSENSITIVE_ORDER);
    return List.copyOf(found);
  }
}
