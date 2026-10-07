package org.openpatch.scratch4j.runner;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where the self-contained library jar ({@code scratch-<v>-all.jar}) comes from.
 *
 * <p>The jar on Maven Central is *slim* — at runtime it needs
 * Processing/JOGL from its transitive dependencies, so a student project must
 * carry the {@code -all.jar} from the library's GitHub releases (exactly what
 * the tutorial projects' {@code +libs} folders contain). The IDE ships that jar
 * (M6 bundles it in the installer); in dev and CI it is downloaded once and
 * cached. Set {@code SCRATCH4J_ALL_JAR} to point at an existing file for
 * fully-offline use.
 */
public final class LibraryJarSource {

  public static final String SCRATCH_VERSION = "5.7.0";

  private static final String RELEASE_URL =
      "https://github.com/openpatch/scratch-for-java/releases/download/v"
          + SCRATCH_VERSION + "/scratch-" + SCRATCH_VERSION + "-all.jar";

  private LibraryJarSource() {}

  /**
   * Returns the all-jar, downloading it into {@code cacheDir} if needed.
   * Never re-downloads when the file already exists.
   */
  /**
   * The jar for a flavour: the standard all-jar, or the NRW one
   * ({@code scratch-<v>-nrw-all.jar}, same release) whose collections are the
   * Abiturklassen {@code List}. Override the NRW jar with {@code SCRATCH4J_NRW_ALL_JAR}.
   */
  public static Path jar(org.openpatch.scratch4j.core.project.LibraryFlavour flavour,
      Path cacheDir) throws IOException {
    if (flavour != org.openpatch.scratch4j.core.project.LibraryFlavour.NRW) {
      return allJar(cacheDir);
    }
    String override = System.getenv("SCRATCH4J_NRW_ALL_JAR");
    if (override != null && !override.isBlank() && Files.isRegularFile(Path.of(override))) {
      return Path.of(override);
    }
    String name = "scratch-" + SCRATCH_VERSION + "-nrw-all.jar";
    Path jar = cacheDir.resolve(name);
    if (Files.isRegularFile(jar)) {
      return jar;
    }
    String url = "https://github.com/openpatch/scratch-for-java/releases/download/v"
        + SCRATCH_VERSION + "/" + name;
    Files.createDirectories(cacheDir);
    Path partial = jar.resolveSibling(name + ".part");
    try (InputStream in = URI.create(url).toURL().openStream()) {
      Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IOException("Could not download " + url
          + " (offline? set SCRATCH4J_NRW_ALL_JAR to an existing nrw jar): " + e.getMessage(), e);
    }
    Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
    return jar;
  }

  /**
   * The newest released library version on GitHub ({@code 5.6.0}), for the
   * "newer library" check. Needs the network; throws when offline.
   */
  public static String latestVersion() throws IOException {
    String api = "https://api.github.com/repos/openpatch/scratch-for-java/releases/latest";
    String json;
    try (InputStream in = URI.create(api).toURL().openStream()) {
      json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    return parseTag(json);
  }

  /** {@code "tag_name": "v5.6.0"} -> {@code 5.6.0}. */
  static String parseTag(String releaseJson) throws IOException {
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"").matcher(releaseJson);
    if (!m.find()) {
      throw new IOException("No release tag in the GitHub answer");
    }
    return m.group(1);
  }

  /** Downloads a specific release's jar ({@code -all} or {@code -nrw-all}) into {@code dir}. */
  public static Path download(String version, boolean nrw, Path dir) throws IOException {
    String name = "scratch-" + version + (nrw ? "-nrw" : "") + "-all.jar";
    Path jar = dir.resolve(name);
    if (Files.isRegularFile(jar)) {
      return jar;
    }
    Files.createDirectories(dir);
    Path partial = jar.resolveSibling(name + ".part");
    String url = "https://github.com/openpatch/scratch-for-java/releases/download/v" + version
        + "/" + name;
    try (InputStream in = URI.create(url).toURL().openStream()) {
      Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
    }
    Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
    return jar;
  }

  public static Path allJar(Path cacheDir) throws IOException {
    String override = System.getenv("SCRATCH4J_ALL_JAR");
    if (override != null && !override.isBlank()) {
      Path p = Path.of(override);
      if (Files.isRegularFile(p)) {
        return p;
      }
      throw new IOException("SCRATCH4J_ALL_JAR does not point at a file: " + override);
    }
    Path jar = cacheDir.resolve("scratch-" + SCRATCH_VERSION + "-all.jar");
    if (Files.isRegularFile(jar)) {
      return jar;
    }
    Files.createDirectories(cacheDir);
    Path partial = jar.resolveSibling(jar.getFileName() + ".part");
    try (InputStream in = URI.create(RELEASE_URL).toURL().openStream()) {
      Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IOException("Could not download " + RELEASE_URL
          + " (offline? set SCRATCH4J_ALL_JAR to an existing scratch-all jar): " + e.getMessage(), e);
    }
    Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
    return jar;
  }
}
