package org.openpatch.scratch4j.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Writes {@code .tar.gz} archives (ustar, no external dependencies) that keep
 * the execute bit: a zip written by {@code java.util.zip} loses it, so an
 * unpacked {@code run.sh} or {@code .app} launcher would not start on Linux or
 * macOS.
 */
public final class Tar {

  private static final int BLOCK = 512;

  private Tar() {}

  /** Archives {@code dir} so it unpacks into {@code prefix/...}; returns the file. */
  public static Path gzipDirectory(Path dir, String prefix, Path archive) throws IOException {
    Files.createDirectories(archive.toAbsolutePath().getParent());
    try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
      try (var files = Files.walk(dir)) {
        for (Path file : files.sorted().toList()) {
          String name = prefix + (file.equals(dir) ? "" : "/"
              + dir.relativize(file).toString().replace('\\', '/'));
          if (Files.isDirectory(file)) {
            header(out, name + "/", 0, 0755, '5');
          } else if (Files.isRegularFile(file)) {
            long size = Files.size(file);
            header(out, name, size, executable(file, name) ? 0755 : 0644, '0');
            try (InputStream in = Files.newInputStream(file)) {
              in.transferTo(out);
            }
            pad(out, size);
          }
        }
      }
      out.write(new byte[BLOCK * 2]); // end of archive
    }
    return archive;
  }

  /**
   * Executable on disk, or a launcher/runtime binary by its place (an archive
   * built on Windows has no execute bits to copy).
   */
  static boolean executable(Path file, String name) {
    return Files.isExecutable(file) && !System.getProperty("os.name").startsWith("Windows")
        || name.matches(".*/runtime/(bin/[^/]+|lib/jspawnhelper|lib/[^/]*\\.dylib|Contents/Home/bin/[^/]+)$")
        || name.matches(".*/(run\\.sh|run\\.command|install\\.sh)$")
        || name.matches(".*\\.app/Contents/MacOS/[^/]+$");
  }

  private static void header(OutputStream out, String name, long size, int mode, char type)
      throws IOException {
    byte[] header = new byte[BLOCK];
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    String prefixPart = "";
    if (nameBytes.length > 100) {
      // ustar splits long names at a slash: prefix (155) + name (100)
      int split = name.lastIndexOf('/', Math.min(name.length() - 1, 155));
      while (split > 0 && name.substring(split + 1).getBytes(StandardCharsets.UTF_8).length > 100) {
        split = name.lastIndexOf('/', split - 1);
      }
      if (split <= 0) {
        throw new IOException("Path too long for a tar archive: " + name);
      }
      prefixPart = name.substring(0, split);
      nameBytes = name.substring(split + 1).getBytes(StandardCharsets.UTF_8);
    }
    System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
    octal(header, 100, 8, mode);
    octal(header, 108, 8, 0);
    octal(header, 116, 8, 0);
    octal(header, 124, 12, size);
    octal(header, 136, 12, System.currentTimeMillis() / 1000);
    for (int i = 148; i < 156; i++) {
      header[i] = ' ';
    }
    header[156] = (byte) type;
    byte[] magic = "ustar\u000000".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, header, 257, magic.length);
    byte[] prefixBytes = prefixPart.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(prefixBytes, 0, header, 345, prefixBytes.length);
    long checksum = 0;
    for (byte b : header) {
      checksum += b & 0xff;
    }
    octal(header, 148, 7, checksum);
    header[155] = ' ';
    out.write(header);
  }

  private static void octal(byte[] header, int offset, int length, long value) {
    String text = Long.toOctalString(value);
    while (text.length() < length - 1) {
      text = "0" + text;
    }
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
    header[offset + length - 1] = 0;
  }

  private static void pad(OutputStream out, long size) throws IOException {
    long rest = size % BLOCK;
    if (rest != 0) {
      out.write(new byte[(int) (BLOCK - rest)]);
    }
  }
}
