package org.openpatch.scratch4j.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Watches a project folder for files and folders that appear or go away
 * (from the system file manager, git, another editor) and tells the Files
 * tree, a moment after the last change. Edits to a file's contents do not
 * count, and neither do the folders the tree hides.
 */
final class ProjectWatcher implements AutoCloseable {

  private final WatchService service;
  private final PauseTransition settle = new PauseTransition(Duration.millis(300));
  /** The paths that came or went since the last report (FX thread); null: anything may have. */
  private java.util.Set<Path> changed = new java.util.HashSet<>();

  /**
   * Starts watching {@code root}; {@code onChange} runs on the FX thread with
   * the paths that were created or deleted, or null when the system lost
   * track (read the whole folder). Call on the FX thread.
   */
  static ProjectWatcher start(Path root, java.util.function.Consumer<java.util.Set<Path>> onChange)
      throws IOException {
    return new ProjectWatcher(root, onChange);
  }

  private ProjectWatcher(Path root, java.util.function.Consumer<java.util.Set<Path>> onChange)
      throws IOException {
    service = root.getFileSystem().newWatchService();
    settle.setOnFinished(e -> {
      java.util.Set<Path> paths = changed;
      changed = new java.util.HashSet<>();
      onChange.accept(paths);
    });
    // walking all folders to watch them reads the disk: on the watcher's thread
    Thread.ofPlatform().daemon().name("scratch4j-project-watcher").start(() -> {
      try {
        register(root);
      } catch (IOException | java.nio.file.ClosedWatchServiceException e) {
        return; // the Refresh item still reads the disk
      }
      watch();
    });
  }

  private void watch() {
    try {
      while (true) {
        WatchKey key = service.take();
        boolean overflow = false;
        java.util.Set<Path> paths = new java.util.HashSet<>();
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            overflow = true;
            continue;
          }
          Path name = (Path) event.context();
          if (ProjectTree.HIDDEN.contains(name.toString())) continue;
          Path path = ((Path) key.watchable()).resolve(name);
          paths.add(path);
          if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
            try {
              register(path);
            } catch (IOException gone) {
              // went away again before it was watched: the reload reads the disk anyway
            }
          }
        }
        key.reset();
        if (overflow || !paths.isEmpty()) {
          boolean lost = overflow;
          Platform.runLater(() -> {
            if (lost || changed == null) {
              changed = null;
            } else {
              changed.addAll(paths);
            }
            settle.playFromStart();
          });
        }
      }
    } catch (InterruptedException | ClosedWatchServiceException stopped) {
      // closed with its project
    }
  }

  /** {@code dir} and its folders, except those the tree hides. */
  private void register(Path dir) throws IOException {
    Files.walkFileTree(dir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs)
          throws IOException {
        if (!folder.equals(dir) && ProjectTree.HIDDEN.contains(folder.getFileName().toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        folder.register(service, StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        return FileVisitResult.CONTINUE;
      }
    });
  }

  @Override
  public void close() {
    settle.stop();
    try {
      service.close();
    } catch (IOException ignored) {
      // nothing left to watch
    }
  }
}
