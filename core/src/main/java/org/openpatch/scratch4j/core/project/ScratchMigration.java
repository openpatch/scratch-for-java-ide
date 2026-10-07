package org.openpatch.scratch4j.core.project;

import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import tools.jackson.databind.json.JsonMapper;

/** Reviewable tasks retain the original block and a marker that survives source edits. */
public final class ScratchMigration {
  private ScratchMigration() {}
  public record Task(String id, String markerId, String blockId, String opcode, String target,
      String javaFile, int line, String message, String lessonUrl, String originalBlock) {}
  public record Manifest(int schemaVersion, List<Task> tasks) {}

  public static void save(Path root, List<Task> tasks) throws IOException {
    Path file = root.resolve(".scratch4j/migration.json");
    Files.createDirectories(file.getParent());
    Path temporary = Files.createTempFile(file.getParent(), "migration-", ".tmp");
    try {
      Files.writeString(temporary, JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
          .writeValueAsString(new Manifest(1, List.copyOf(tasks))));
      try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temporary); }
  }

  public static List<Task> load(Path root) throws IOException {
    Path file = root.resolve(".scratch4j/migration.json");
    if (!Files.isRegularFile(file)) return List.of();
    Manifest manifest;
    try { manifest = JsonMapper.builder().build().readValue(file.toFile(), Manifest.class); }
    catch (RuntimeException e) { throw new IOException("Invalid Scratch migration tasks", e); }
    if (manifest.schemaVersion() != 1 || manifest.tasks() == null) throw new IOException("Unsupported migration schema");
    List<Task> tasks = new ArrayList<>();
    for (Task task : manifest.tasks()) {
      Path source = root.resolve(task.javaFile()).normalize();
      if (!source.startsWith(root.normalize()) || !task.javaFile().endsWith(".java"))
        throw new IOException("Invalid migration source: " + task.javaFile());
      int line = task.line();
      if (Files.isRegularFile(source)) {
        String text = Files.readString(source);
        int offset = text.indexOf("scratch4j:migration " + task.markerId());
        if (offset >= 0) line = (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
      }
      tasks.add(new Task(task.id(), task.markerId(), task.blockId(), task.opcode(), task.target(),
          task.javaFile(), line, task.message(), task.lessonUrl(), task.originalBlock()));
    }
    return List.copyOf(tasks);
  }
}
