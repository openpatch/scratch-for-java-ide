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

  /** Starts watching {@code root}; {@code onChange} runs on the FX thread. Call on the FX thread. */
  static ProjectWatcher start(Path root, Runnable onChange) throws IOException {
    return new ProjectWatcher(root, onChange);
  }

  private ProjectWatcher(Path root, Runnable onChange) throws IOException {
    service = root.getFileSystem().newWatchService();
    settle.setOnFinished(e -> onChange.run());
    register(root);
    Thread.ofPlatform().daemon().name("scratch4j-project-watcher").start(this::watch);
  }

  private void watch() {
    try {
      while (true) {
        WatchKey key = service.take();
        boolean changed = false;
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            changed = true;
            continue;
          }
          Path name = (Path) event.context();
          if (ProjectTree.HIDDEN.contains(name.toString())) continue;
          changed = true;
          Path path = ((Path) key.watchable()).resolve(name);
          if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
            try {
              register(path);
            } catch (IOException gone) {
              // went away again before it was watched: the reload reads the disk anyway
            }
          }
        }
        key.reset();
        if (changed) Platform.runLater(settle::playFromStart);
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
