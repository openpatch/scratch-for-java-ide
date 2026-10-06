package org.openpatch.scratch4j.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Crash-safe file writes: never lose user code. */
public final class AtomicFiles {

  private AtomicFiles() {}

  /** Writes to a sibling temp file, then atomically replaces the target. */
  public static void writeString(Path file, String content) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
    try {
      Files.writeString(tmp, content, StandardCharsets.UTF_8);
      replace(tmp, file);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** Replace a target with a complete sibling temp file. */
  public static void replace(Path tmp, Path file) throws IOException {
    try {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
