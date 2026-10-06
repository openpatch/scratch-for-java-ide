package org.openpatch.scratch4j.runner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Java that runs student programs. Hot reload swaps method bodies with
 * any Java; adding or removing attributes and methods while the program runs
 * needs a runtime with enhanced class redefinition (the JetBrains Runtime,
 * {@code -XX:+AllowEnhancedClassRedefinition}). Found in this order:
 *
 * <ol>
 *   <li>{@code -Dscratch4j.programRuntime} / {@code SCRATCH4J_PROGRAM_RUNTIME} (a Java home),</li>
 *   <li>the IDE's own runtime, when it is a JetBrains Runtime,</li>
 *   <li>{@code <app>/jbr} - for older packages with a separate runtime,</li>
 *   <li>the user cache - downloaded by the IDE ({@link #download}),</li>
 *   <li>a JetBrains Runtime in {@code ~/.jdks}.</li>
 * </ol>
 * Without one, programs run on the IDE's own Java.
 */
public final class ProgramRuntime {

  /** The JetBrains Runtime the IDE downloads (pinned: tested with the library). */
  public static final String JBR_VERSION = "25.0.4.1";
  public static final String JBR_BUILD = "b635.70";

  private static final Map<Path, Boolean> SUPPORTS = new ConcurrentHashMap<>();

  private ProgramRuntime() {}

  /** The java executable for student programs. */
  public static Path java() {
    Path enhanced = enhanced();
    return enhanced != null ? enhanced
        : Path.of(System.getProperty("java.home"), "bin", executable());
  }

  /** A java with enhanced class redefinition, or null. */
  public static Path enhanced() {
    for (Path home : candidates()) {
      Path java = home.resolve("bin").resolve(executable());
      if (Files.isRegularFile(java) && supportsEnhancedRedefinition(java)) {
        return java;
      }
    }
    return null;
  }

  static List<Path> candidates() {
    List<Path> out = new ArrayList<>();
    String explicit = System.getProperty("scratch4j.programRuntime",
        System.getenv("SCRATCH4J_PROGRAM_RUNTIME"));
    if (explicit != null && !explicit.isBlank()) out.add(Path.of(explicit));
    out.add(Path.of(System.getProperty("java.home")));
    String appDir = System.getProperty("scratch4j.appDir");
    if (appDir != null) out.add(home(Path.of(appDir, "jbr")));
    out.addAll(homesIn(cacheDir()));
    out.addAll(homesIn(Path.of(System.getProperty("user.home"), ".jdks")).stream()
        .filter(p -> p.toString().toLowerCase(Locale.ROOT).contains("jbr")).toList());
    return out;
  }

  /** Whether {@code java} accepts {@code -XX:+AllowEnhancedClassRedefinition} (cached). */
  public static boolean supportsEnhancedRedefinition(Path java) {
    return SUPPORTS.computeIfAbsent(java.toAbsolutePath().normalize(), j -> {
      try {
        Process p = new ProcessBuilder(j.toString(), "-XX:+AllowEnhancedClassRedefinition",
            "-version").redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
      } catch (IOException | InterruptedException e) {
        return false;
      }
    });
  }

  /** The JVM arguments that go with this java for a connected (hot reload) run. */
  public static List<String> hotReloadArgs(Path java) {
    return supportsEnhancedRedefinition(java)
        ? List.of("-XX:+AllowEnhancedClassRedefinition") : List.of();
  }

  /** Where the IDE puts a downloaded runtime. */
  public static Path cacheDir() {
    return Path.of(System.getProperty("user.home"), ".cache", "scratch4j-ide", "program-runtime");
  }

  /** The download URL of the pinned JetBrains Runtime for this OS and CPU. */
  public static String downloadUrl() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String platform = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
    String arch = System.getProperty("os.arch").matches("aarch64|arm64") ? "aarch64" : "x64";
    return "https://cache-redirector.jetbrains.com/intellij-jbr/jbr-" + JBR_VERSION + "-"
        + platform + "-" + arch + "-" + JBR_BUILD + ".tar.gz";
  }

  /**
   * Downloads and unpacks the JetBrains Runtime into the user cache (about
   * 100 MB); {@code progress} gets 0..1. Returns its java.
   */
  public static Path download(Consumer<Double> progress) throws IOException {
    Path dir = cacheDir();
    Files.createDirectories(dir);
    Path archive = dir.resolve("jbr.tar.gz.part");
    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    try {
      HttpResponse<InputStream> response = client.send(
          HttpRequest.newBuilder(URI.create(downloadUrl())).build(),
          HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new IOException("Download failed: HTTP " + response.statusCode());
      }
      long total = response.headers().firstValueAsLong("content-length").orElse(-1);
      try (InputStream in = response.body();
          var out = Files.newOutputStream(archive)) {
        byte[] buffer = new byte[1 << 16];
        long read = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
          out.write(buffer, 0, n);
          read += n;
          if (total > 0) progress.accept(Math.min(1.0, read / (double) total));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Download interrupted", e);
    }
    // tar is part of Linux, macOS and Windows 10+
    Process tar = new ProcessBuilder("tar", "xzf", archive.toString(), "-C", dir.toString())
        .redirectErrorStream(true).start();
    try {
      tar.getInputStream().transferTo(OutputStream.nullOutputStream());
      if (!tar.waitFor(5, TimeUnit.MINUTES) || tar.exitValue() != 0) {
        throw new IOException("Could not unpack the runtime");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Unpacking interrupted", e);
    } finally {
      Files.deleteIfExists(archive);
    }
    SUPPORTS.clear();
    Path java = enhanced();
    if (java == null) {
      throw new IOException("The downloaded runtime does not support enhanced hot reload");
    }
    return java;
  }

  /** A Java home inside {@code dir} (macOS keeps it in Contents/Home). */
  private static Path home(Path dir) {
    Path mac = dir.resolve("Contents").resolve("Home");
    return Files.isDirectory(mac) ? mac : dir;
  }

  private static List<Path> homesIn(Path dir) {
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> children = Files.list(dir)) {
      return children.filter(Files::isDirectory).sorted().map(ProgramRuntime::home).toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static String executable() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
        ? "java.exe" : "java";
  }
}
