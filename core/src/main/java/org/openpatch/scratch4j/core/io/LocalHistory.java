package org.openpatch.scratch4j.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Recoverable pre-save versions stored inside a project's IDE metadata. */
public final class LocalHistory {

  private static final int MAX_REVISIONS = 50;

  private LocalHistory() {}

  /** Save a text file, retaining its previous contents when they differ. */
  public static void writeString(Path root, Path file, String content) throws IOException {
    byte[] next = content.getBytes(StandardCharsets.UTF_8);
    if (Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), next)) {
      return;
    }
    snapshot(root, file);
    AtomicFiles.writeString(file, content);
  }

  /** Retain the current version before a binary editor replaces it. */
  public static void snapshot(Path root, Path file) throws IOException {
    Path dir = historyDir(root, file);
    if (!Files.isRegularFile(file)) {
      return;
    }
    Files.createDirectories(dir);
    Path revision = dir.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".bak");
    Path temp = Files.createTempFile(dir, ".snapshot", ".tmp");
    try {
      Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
      AtomicFiles.replace(temp, revision);
    } finally {
      Files.deleteIfExists(temp);
    }
    prune(dir);
  }

  /** Newest first; revisions are opaque files whose contents are exact copies. */
  public static List<Path> revisions(Path root, Path file) throws IOException {
    Path dir = historyDir(root, file);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".bak"))
          .sorted(Comparator.comparing(LocalHistory::timestamp).reversed())
          .toList();
    }
  }

  /** Restore a revision and retain the version being replaced. */
  public static void restore(Path root, Path file, Path revision) throws IOException {
    if (!revisions(root, file).contains(revision) || !Files.isRegularFile(revision)) {
      throw new IOException("Unknown history revision: " + revision);
    }
    Path dir = file.toAbsolutePath().getParent();
    Path temp = Files.createTempFile(dir, file.getFileName().toString(), ".restore");
    try {
      Files.copy(revision, temp, StandardCopyOption.REPLACE_EXISTING);
      snapshot(root, file);
      AtomicFiles.replace(temp, file);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public static Instant timestamp(Path revision) {
    String name = revision.getFileName().toString();
    return Instant.ofEpochMilli(Long.parseLong(name.substring(0, name.indexOf('-'))));
  }

  private static Path historyDir(Path root, Path file) throws IOException {
    Path base = root.toAbsolutePath().normalize();
    Path target = file.toAbsolutePath().normalize();
    if (!target.startsWith(base) || target.equals(base) ||
        target.startsWith(base.resolve(".scratch4j"))) {
      throw new IOException("File is outside project history: " + file);
    }
    return base.resolve(".scratch4j/history").resolve(base.relativize(target));
  }

  private static void prune(Path dir) throws IOException {
    List<Path> all;
    try (Stream<Path> files = Files.list(dir)) {
      all = files.filter(p -> p.getFileName().toString().endsWith(".bak"))
          .sorted(Comparator.comparing(LocalHistory::timestamp).reversed()).toList();
    }
    for (int i = MAX_REVISIONS; i < all.size(); i++) {
      Files.delete(all.get(i));
    }
  }
}
