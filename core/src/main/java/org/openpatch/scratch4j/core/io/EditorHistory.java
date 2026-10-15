package org.openpatch.scratch4j.core.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * An editor's undo and redo steps for one project file, kept in the
 * project's hidden metadata ({@code .scratch4j/editor/<path>.history}) so
 * they survive closing the IDE. Each step is the editor's own bytes (a
 * picture's layers, a sound's samples, a map's XML); {@code current} is what
 * the file alone can't hold (a picture's layers before they are flattened).
 * The steps belong to the file as it was saved with them: once the file
 * changes elsewhere, {@link #read} finds none.
 */
public final class EditorHistory {

  /** Undo steps newest first, redo steps next first, and the editor's own state (or null). */
  public record Steps(List<byte[]> undo, List<byte[]> redo, byte[] current) {

    public static final Steps NONE = new Steps(List.of(), List.of(), null);

    boolean empty() {
      return undo.isEmpty() && redo.isEmpty() && current == null;
    }
  }

  private static final int MAGIC = 0x53344548; // "S4EH"
  private static final int VERSION = 1;
  /** At most this much is kept per file; the oldest undo steps go first. */
  static final long MAX_BYTES = 48L * 1024 * 1024;

  private EditorHistory() {}

  /**
   * Keeps {@code steps} of the editor {@code kind} for {@code file} as it is
   * now on disk (call it right after saving the file). No steps: none kept.
   */
  public static void write(Path root, Path file, String kind, Steps steps) throws IOException {
    Path history = historyFile(root, file);
    if (steps.empty() || !Files.isRegularFile(file)) {
      Files.deleteIfExists(history);
      return;
    }
    // the newest steps first: what doesn't fit is the oldest undo steps, then the redo steps
    long budget = MAX_BYTES - (steps.current() == null ? 0 : steps.current().length);
    List<byte[]> undo = fit(steps.undo(), budget);
    budget -= size(undo);
    List<byte[]> redo = fit(steps.redo(), budget);

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeUTF(kind);
      out.writeUTF(hash(file));
      writeBlob(out, steps.current());
      out.writeInt(undo.size());
      for (byte[] step : undo) writeBlob(out, step);
      out.writeInt(redo.size());
      for (byte[] step : redo) writeBlob(out, step);
    }
    Files.createDirectories(history.getParent());
    Path temp = Files.createTempFile(history.getParent(), ".history", ".tmp");
    try {
      Files.write(temp, bytes.toByteArray());
      AtomicFiles.replace(temp, history);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /**
   * The steps kept for {@code file}: none when there are none, they are of
   * another editor, or the file changed since they were kept.
   */
  public static Steps read(Path root, Path file, String kind) {
    try {
      Path history = historyFile(root, file);
      if (!Files.isRegularFile(history) || !Files.isRegularFile(file)) return Steps.NONE;
      try (DataInputStream in = new DataInputStream(
          new InflaterInputStream(Files.newInputStream(history)))) {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) return Steps.NONE;
        if (!in.readUTF().equals(kind) || !in.readUTF().equals(hash(file))) return Steps.NONE;
        byte[] current = readBlob(in);
        List<byte[]> undo = readBlobs(in);
        List<byte[]> redo = readBlobs(in);
        return new Steps(undo, redo, current);
      }
    } catch (IOException | RuntimeException e) {
      return Steps.NONE; // a damaged history is no history
    }
  }

  /** The steps of a file or folder follow it when it is moved or renamed (best effort). */
  public static void move(Path root, Path from, Path to) {
    try {
      for (String suffix : new String[] {".history", ""}) {
        Path source = location(root, from, suffix);
        Path target = location(root, to, suffix);
        if (Files.exists(source) && !Files.exists(target)) {
          Files.createDirectories(target.getParent());
          Files.move(source, target);
        }
      }
    } catch (IOException | RuntimeException ignored) {
      // the steps stay behind; read() never hands them to another file's contents
    }
  }

  private static List<byte[]> fit(List<byte[]> steps, long budget) {
    List<byte[]> kept = new ArrayList<>();
    long used = 0;
    for (byte[] step : steps) {
      if (used + step.length > budget) break;
      used += step.length;
      kept.add(step);
    }
    return kept;
  }

  private static long size(List<byte[]> steps) {
    long size = 0;
    for (byte[] step : steps) size += step.length;
    return size;
  }

  private static void writeBlob(DataOutputStream out, byte[] blob) throws IOException {
    out.writeInt(blob == null ? -1 : blob.length);
    if (blob != null) out.write(blob);
  }

  private static byte[] readBlob(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0) return null;
    if (length > MAX_BYTES) throw new IOException("step too large");
    return in.readNBytes(length);
  }

  private static List<byte[]> readBlobs(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > 10_000) throw new IOException("bad step count");
    List<byte[]> blobs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      byte[] blob = readBlob(in);
      if (blob == null) throw new IOException("missing step");
      blobs.add(blob);
    }
    return List.copyOf(blobs);
  }

  private static String hash(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static Path historyFile(Path root, Path file) throws IOException {
    return location(root, file, ".history");
  }

  private static Path location(Path root, Path file, String suffix) throws IOException {
    Path base = root.toAbsolutePath().normalize();
    Path target = file.toAbsolutePath().normalize();
    if (!target.startsWith(base) || target.equals(base)
        || target.startsWith(base.resolve(".scratch4j"))) {
      throw new IOException("File is outside the project: " + file);
    }
    Path relative = base.relativize(target);
    return base.resolve(".scratch4j/editor").resolve(relative.toString() + suffix);
  }

  // --- helpers for editors writing their steps ------------------------------------

  /** Bytes written with a {@link DataOutputStream}. */
  public interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  /** Bytes read with a {@link DataInputStream}. */
  public interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }

  public static byte[] encode(Writer writer) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      writer.write(out);
    }
    return bytes.toByteArray();
  }

  public static <T> T decode(byte[] bytes, Reader<T> reader) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      return reader.read(in);
    }
  }
}
