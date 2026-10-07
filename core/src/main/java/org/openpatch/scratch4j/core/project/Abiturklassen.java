package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The classes of the NRW Zentralabitur (QUA-LiS): {@code List}, {@code Queue},
 * the trees, the graph, the database and the network classes. The IDE
 * downloads the official zip once and copies the classes a student picks into
 * the project, together with the ones they need. Scratch for Java NRW only
 * needs {@code List}. They are downloaded, not bundled: their licence is QUA-LiS's.
 *
 * <p>The database classes come in the SQLite version only (no server to set
 * up; a file is the database). They need the SQLite JDBC driver to run, which
 * goes into {@code +libs} with them ({@link #installSqliteDriver}).
 */
public final class Abiturklassen {

  public static final String URL = "https://lehrplannavigator.nrw.de/system/files/media/"
      + "document/file/2020-03-11_implementationen_von_klassen_fuer_das_zentralabitur_ab_2018.zip";

  /** The class the NRW library itself uses. */
  public static final String LIST = "List";

  /** One class: its group (for the dialog) and the classes it cannot compile without. */
  public record Entry(String className, String group, List<String> requires) {}

  public static final List<Entry> ENTRIES = List.of(
      new Entry("List", "linear", List.of()),
      new Entry("Queue", "linear", List.of()),
      new Entry("Stack", "linear", List.of()),
      new Entry("BinaryTree", "tree", List.of()),
      new Entry("BinarySearchTree", "tree", List.of("ComparableContent")),
      new Entry("ComparableContent", "tree", List.of()),
      new Entry("Graph", "graph", List.of("Vertex", "Edge", "List")),
      new Entry("Vertex", "graph", List.of()),
      new Entry("Edge", "graph", List.of("Vertex")),
      new Entry("DatabaseConnector", "database", List.of("QueryResult", "Queue")),
      new Entry("QueryResult", "database", List.of()),
      new Entry("Client", "network", List.of()),
      new Entry("Server", "network", List.of("List")),
      new Entry("Connection", "network", List.of()));

  /** The SQLite JDBC driver from Maven Central, pinned by its SHA-1. */
  public static final String SQLITE_VERSION = "3.53.4.0";
  static final String SQLITE_SHA1 = "47e21dd53efe1524fe0df6965cb65003ffe17172";
  static final String SQLITE_URL = "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/"
      + SQLITE_VERSION + "/sqlite-jdbc-" + SQLITE_VERSION + ".jar";

  /** Inside the zip: the database version to use. */
  private static final String DATABASE_FOLDER = "Version fuer SQLite/";

  private Abiturklassen() {}

  /** The chosen classes and everything they need, in {@link #ENTRIES} order. */
  public static Set<String> withRequirements(Collection<String> chosen) {
    Set<String> all = new LinkedHashSet<>();
    List<String> todo = new ArrayList<>(chosen);
    while (!todo.isEmpty()) {
      String name = todo.remove(todo.size() - 1);
      if (!all.add(name)) continue;
      ENTRIES.stream().filter(e -> e.className().equals(name)).findFirst()
          .ifPresent(e -> todo.addAll(e.requires()));
    }
    Set<String> ordered = new LinkedHashSet<>();
    for (Entry e : ENTRIES) {
      if (all.contains(e.className())) ordered.add(e.className());
    }
    return ordered;
  }

  /** The Abiturklassen already in the project (in any folder). */
  public static Set<String> present(ScratchProject project) throws IOException {
    Set<String> present = new LinkedHashSet<>();
    for (Path source : project.javaSources()) {
      String name = source.getFileName().toString().replaceFirst("\\.java$", "");
      if (ENTRIES.stream().anyMatch(e -> e.className().equals(name))) present.add(name);
    }
    return present;
  }

  /** The official zip, downloaded into {@code cacheDir} the first time. */
  public static Path download(Path cacheDir) throws IOException {
    Path zip = cacheDir.resolve(URL.substring(URL.lastIndexOf('/') + 1));
    if (Files.isRegularFile(zip)) return zip;
    Path partial = fetch(URL, zip);
    if (read(partial).isEmpty()) {
      Files.deleteIfExists(partial);
      throw new IOException("The download contains no Abiturklassen: " + URL);
    }
    Files.move(partial, zip, StandardCopyOption.REPLACE_EXISTING);
    return zip;
  }

  /**
   * Puts the SQLite JDBC driver into the project's {@code +libs} (run and
   * export take every jar there), downloaded once into {@code cacheDir}.
   *
   * @return the jar in {@code +libs}, or null when the project has a driver already
   */
  public static Path installSqliteDriver(ScratchProject project, Path cacheDir)
      throws IOException {
    for (Path lib : project.libs()) {
      if (lib.getFileName().toString().startsWith("sqlite-jdbc")) return null;
    }
    String name = "sqlite-jdbc-" + SQLITE_VERSION + ".jar";
    Path cached = cacheDir.resolve(name);
    if (!Files.isRegularFile(cached) || !SQLITE_SHA1.equals(sha1(cached))) {
      Path partial = fetch(SQLITE_URL, cached);
      if (!SQLITE_SHA1.equals(sha1(partial))) {
        Files.deleteIfExists(partial);
        throw new IOException("The SQLite driver download is damaged: " + SQLITE_URL);
      }
      Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
    }
    Files.createDirectories(project.libsDir());
    Path target = project.libsDir().resolve(name);
    Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  /** Downloads {@code url} next to {@code target} ({@code .part}); the caller moves it. */
  private static Path fetch(String url, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    Path partial = target.resolveSibling(target.getFileName() + ".part");
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(120)).header("User-Agent", "scratch4j-studio").build();
    try {
      HttpResponse<InputStream> response = client.send(request,
          HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        response.body().close();
        throw new IOException("HTTP " + response.statusCode() + " for " + url);
      }
      try (InputStream in = response.body()) {
        Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Download interrupted", e);
    }
    return partial;
  }

  static String sha1(Path file) throws IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-1");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  /**
   * The class sources in the zip (or an unpacked folder of it) by class name.
   * Of the database classes, only the SQLite version.
   */
  public static Map<String, String> read(Path zipOrFolder) throws IOException {
    Map<String, String> sources = new LinkedHashMap<>();
    if (Files.isDirectory(zipOrFolder)) {
      try (Stream<Path> files = Files.walk(zipOrFolder, 4)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          String path = zipOrFolder.relativize(file).toString().replace('\\', '/');
          add(sources, path, Files.readString(file, StandardCharsets.UTF_8));
        }
      }
      return sources;
    }
    try (ZipFile zip = new ZipFile(zipOrFolder.toFile())) {
      for (ZipEntry entry : zip.stream().filter(e -> e.getName().endsWith(".java")).toList()) {
        try (InputStream in = zip.getInputStream(entry)) {
          add(sources, entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    } catch (java.util.zip.ZipException e) {
      throw new IOException("Not a zip file: " + zipOrFolder, e);
    }
    return sources;
  }

  private static void add(Map<String, String> sources, String path, String text) {
    String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.java$", "");
    boolean database = name.equals("DatabaseConnector") || name.equals("QueryResult");
    if (database && !path.contains(DATABASE_FOLDER)) return;
    if (ENTRIES.stream().anyMatch(e -> e.className().equals(name))) {
      sources.putIfAbsent(name, text);
    }
  }

  /**
   * Copies the chosen classes and what they need into the project's main
   * folder; classes the project already has stay as they are.
   *
   * @return the files written
   */
  public static List<Path> install(ScratchProject project, Map<String, String> sources,
      Collection<String> chosen) throws IOException {
    Set<String> present = present(project);
    List<Path> written = new ArrayList<>();
    for (String name : withRequirements(chosen)) {
      if (present.contains(name)) continue;
      String text = sources.get(name);
      if (text == null) {
        throw new IOException(name + ".java is missing in the Abiturklassen download");
      }
      if (name.equals("DatabaseConnector")) {
        // the network classes have a Connection too: in the same folder it would hide
        // java.sql.Connection (the MySQL version of this file already says java.sql)
        text = text.replaceAll("(?m)^(\\s*private\\s+)Connection(\\s+connection\\s*;)",
            "$1java.sql.Connection$2");
      }
      Path target = project.root().resolve(name + ".java");
      Files.writeString(target, text, StandardCharsets.UTF_8);
      written.add(target);
    }
    return written;
  }
}
