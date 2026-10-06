package org.openpatch.scratch4j.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Fetches a target platform's JDK jmods for cross-OS exports, from the
 * Adoptium API. Files are cached; the archive's SHA-256 is verified against
 * the checksum the API reports. School admins can pre-seed the cache for
 * fully offline use.
 *
 * <p>JEP 493 lets vendors ship runtimes without jmods. When jmods are absent,
 * export bundles the target JRE instead.
 */
public final class JmodsFetcher {

  /** A target platform Adoptium can serve jmods for. */
  public record Target(String os, String arch) {

    /** The platform the IDE runs on. */
    public static Target current() {
      String name = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
      String os = name.contains("win") ? "windows" : name.contains("mac") ? "mac" : "linux";
      String arch = System.getProperty("os.arch").matches("aarch64|arm64") ? "aarch64" : "x64";
      return new Target(os, arch);
    }

    /** Adoptium's file-name spelling: {@code x64_linux}, {@code aarch64_mac}. */
    String archiveTag() {
      return arch + "_" + os;
    }
  }

  public static final Target LINUX_X64 = new Target("linux", "x64");
  public static final Target WINDOWS_X64 = new Target("windows", "x64");
  public static final Target MACOS_ARM64 = new Target("mac", "aarch64");
  public static final Target MACOS_X64 = new Target("mac", "x64");

  private JmodsFetcher() {}

  /**
   * Returns the jmods directory of the given target's Temurin JDK, downloading
   * and unpacking it into {@code cacheDir} if needed.
   */
  public static Path jmods(Target target, String featureVersion, Path cacheDir)
      throws IOException {
    Path jdkDir = cacheDir.resolve("temurin-" + featureVersion + "-" + target.os()
        + "-" + target.arch());
    Path jmods = jdkDir.resolve("jmods");
    if (Files.isDirectory(jmods)) {
      return jmods;
    }
    if (javaHome(jdkDir) != null) {
      // unpacked before and found without jmods: do not download it again
      throw new IOException("The cached Temurin JDK " + featureVersion + " for " + target.os()
          + "/" + target.arch() + " ships no jmods (JEP 493)");
    }
    Release release = latestRelease(target, featureVersion, "jdk");
    Path archive = download(release, cacheDir.resolve(release.fileName));
    unpack(archive, jdkDir);
    if (!Files.isDirectory(jdkDir.resolve("jmods"))) {
      throw new IOException("The downloaded Temurin JDK " + featureVersion
          + " for " + target.os() + "/" + target.arch()
          + " ships no jmods (JEP 493); the fallback is bundling the target JRE");
    }
    return jdkDir.resolve("jmods");
  }

  /**
   * Returns the Java home of the target's Temurin JRE — the plan's fallback
   * when JDKs ship no jmods: the export bundles the target's own runtime.
   * Downloaded once, SHA-256-verified and cached. Offline, an archive an
   * admin put into {@code cacheDir} ({@code OpenJDK25U-jre_x64_windows_*.zip},
   * as downloaded from adoptium.net) is used instead.
   */
  public static Path jre(Target target, String featureVersion, Path cacheDir)
      throws IOException {
    Path jreDir = cacheDir.resolve("temurin-jre-" + featureVersion + "-" + target.os()
        + "-" + target.arch());
    Path home = javaHome(jreDir);
    if (home != null) {
      return home;
    }
    Path archive;
    try {
      Release release = latestRelease(target, featureVersion, "jre");
      archive = download(release, cacheDir.resolve(release.fileName));
    } catch (IOException offline) {
      archive = seeded(cacheDir, "jre", featureVersion, target);
      if (archive == null) {
        throw new IOException("Cannot download the Java runtime for " + target.os() + "/"
            + target.arch() + " (" + offline.getMessage() + "). Offline, put "
            + "OpenJDK" + featureVersion + "U-jre_" + target.archiveTag()
            + "_*.zip or .tar.gz from adoptium.net into " + cacheDir, offline);
      }
    }
    unpack(archive, jreDir);
    home = javaHome(jreDir);
    if (home == null) {
      throw new IOException("The runtime archive " + archive.getFileName() + " has no bin/java");
    }
    return home;
  }

  /**
   * A target JRE that needs no network: unpacked earlier, or an archive an
   * admin seeded into the cache (unpacked now). Null when neither exists.
   */
  public static Path cachedJre(Target target, String featureVersion, Path cacheDir)
      throws IOException {
    Path jreDir = cacheDir.resolve("temurin-jre-" + featureVersion + "-" + target.os()
        + "-" + target.arch());
    Path home = javaHome(jreDir);
    if (home != null) {
      return home;
    }
    Path archive = seeded(cacheDir, "jre", featureVersion, target);
    if (archive == null) {
      return null;
    }
    unpack(archive, jreDir);
    return javaHome(jreDir);
  }

  /** An admin-seeded archive for offline use, or null. */
  static Path seeded(Path cacheDir, String imageType, String featureVersion, Target target)
      throws IOException {
    if (!Files.isDirectory(cacheDir)) {
      return null;
    }
    String prefix = "OpenJDK" + featureVersion + "U-" + imageType + "_" + target.archiveTag() + "_";
    try (var files = Files.list(cacheDir)) {
      return files.filter(f -> f.getFileName().toString().startsWith(prefix)
              && f.getFileName().toString().matches(".*\\.(zip|tar\\.gz)$"))
          .sorted().reduce((a, b) -> b).orElse(null);
    }
  }

  /**
   * The Java home inside an unpacked runtime: {@code jdk-25+36-jre/} on
   * Linux/Windows, {@code .../Contents/Home} in a macOS bundle.
   */
  static Path javaHome(Path unpacked) throws IOException {
    if (!Files.isDirectory(unpacked)) {
      return null;
    }
    try (var files = Files.walk(unpacked, 4)) {
      return files.filter(dir -> Files.isRegularFile(dir.resolve("bin/java"))
              || Files.isRegularFile(dir.resolve("bin/java.exe")))
          .findFirst().orElse(null);
    }
  }

  private record Release(String fileName, String url, String sha256) {}

  private static Release latestRelease(Target target, String featureVersion, String imageType)
      throws IOException {
    String api = "https://api.adoptium.net/v3/assets/feature_releases/"
        + featureVersion + "/ga?architecture=" + target.arch()
        + "&image_type=" + imageType + "&os=" + target.os() + "&page_size=1";
    String json = fetch(api);
    int binary = json.indexOf("\"package\": {");
    int link = json.indexOf("\"link\": \"", binary);
    int checksum = json.indexOf("\"checksum\": \"", binary);
    int name = json.indexOf("\"name\": \"", binary);
    if (link < 0 || checksum < 0 || name < 0) {
      throw new IOException("Unexpected Adoptium API response for " + api);
    }
    String url = json.substring(link + 9, json.indexOf('"', link + 9));
    String sha = json.substring(checksum + 13, json.indexOf('"', checksum + 13));
    String fileName = json.substring(name + 9, json.indexOf('"', name + 9));
    return new Release(fileName, url, sha);
  }

  private static String fetch(String url) throws IOException {
    try (InputStream in = URI.create(url).toURL().openStream()) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private static Path download(Release release, Path archive) throws IOException {
    if (Files.isRegularFile(archive) && shaMatches(archive, release.sha256())) {
      return archive;
    }
    Files.createDirectories(archive.getParent());
    Path partial = archive.resolveSibling(archive.getFileName() + ".part");
    try (InputStream in = URI.create(release.url()).toURL().openStream()) {
      Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
    }
    if (!shaMatches(partial, release.sha256())) {
      Files.deleteIfExists(partial);
      throw new IOException("Checksum mismatch downloading " + release.url());
    }
    Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
    return archive;
  }

  private static boolean shaMatches(Path file, String expected) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder actual = new StringBuilder();
      for (byte b : digest.digest()) {
        actual.append(String.format("%02x", b));
      }
      return actual.toString().equalsIgnoreCase(expected);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 unavailable", e);
    }
  }

  private static void unpack(Path archive, Path targetDir) throws IOException {
    Files.createDirectories(targetDir);
    String name = archive.getFileName().toString();
    if (name.endsWith(".zip")) {
      Zip.unzip(archive, targetDir);
    } else if (name.endsWith(".tar.gz")) {
      ProcessBuilder builder = new ProcessBuilder("tar", "-xzf",
          archive.toString(), "-C", targetDir.toString());
      builder.inheritIO();
      try {
        int code = builder.start().waitFor();
        if (code != 0) {
          throw new IOException("tar failed with code " + code);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("tar interrupted", e);
      }
    } else {
      throw new IOException("Unknown archive: " + name);
    }
    // Temurin archives contain a top-level jdk<version>/ folder; flatten it
    try (var dirs = Files.list(targetDir)) {
      var top = dirs.filter(Files::isDirectory).findFirst();
      if (top.isPresent() && !Files.exists(targetDir.resolve("jmods"))) {
        try (var inside = Files.list(top.get())) {
          for (Path p : inside.toList()) {
            Files.move(p, targetDir.resolve(p.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
  }
}
