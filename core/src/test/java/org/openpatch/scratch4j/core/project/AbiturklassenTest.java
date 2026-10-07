package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AbiturklassenTest {

  @TempDir
  Path tmp;

  /** The layout of the QUA-LiS zip (2020-03-11), with tiny classes. */
  private Path officialZip() throws IOException {
    Path zip = tmp.resolve("abitur.zip");
    String data = "01 Datenstrukturklassen/";
    String db = "02 Datenbankklassen/";
    try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (String c : List.of("01 linear/List", "01 linear/Queue", "01 linear/Stack",
          "02 baum/BinarySearchTree", "02 baum/BinaryTree", "02 baum/ComparableContent",
          "03 graph/Edge", "03 graph/Graph", "03 graph/Vertex")) {
        put(out, data + c + ".java", "public class " + c.substring(c.indexOf('/') + 1) + " {}\n");
      }
      for (String version : List.of("01 Version fuer MySQL", "02 Version fuer SQLite",
          "03 Versionfuer MSAccess")) {
        put(out, db + version + "/DatabaseConnector.java", """
            import java.sql.*;
            public class DatabaseConnector {
              // %s
              private Connection connection;
            }
            """.formatted(version));
        put(out, db + version + "/QueryResult.java", "public class QueryResult {}\n");
        put(out, db + version + "/Wichtig.rtf", "{\\rtf1}");
      }
      for (String c : List.of("Client", "Connection", "Server")) {
        put(out, "03 Netzklassen/" + c + ".java", "public class " + c + " {}\n");
      }
    }
    return zip;
  }

  private static void put(ZipOutputStream out, String name, String text) throws IOException {
    out.putNextEntry(new ZipEntry(name));
    out.write(text.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }

  private ScratchProject project() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "abi",
        NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class));
    return ScratchProject.open(tmp.resolve("abi"));
  }

  @Test
  void readsEveryClassAndTheSqliteDatabaseVersion() throws IOException {
    var sources = Abiturklassen.read(officialZip());
    assertThat(sources.keySet()).containsExactlyInAnyOrderElementsOf(
        Abiturklassen.ENTRIES.stream().map(Abiturklassen.Entry::className).toList());
    assertThat(sources.get("DatabaseConnector")).contains("02 Version fuer SQLite");
  }

  @Test
  void copiesTheChosenClassesWithWhatTheyNeed() throws IOException {
    ScratchProject project = project();
    var written = Abiturklassen.install(project, Abiturklassen.read(officialZip()),
        List.of("Graph"));
    assertThat(written).extracting(p -> p.getFileName().toString())
        .containsExactly("List.java", "Graph.java", "Vertex.java", "Edge.java");
    assertThat(Abiturklassen.present(project)).contains("List", "Graph", "Vertex", "Edge");
    // a second import keeps what is there
    assertThat(Abiturklassen.install(project, Abiturklassen.read(officialZip()),
        List.of("List", "Queue"))).extracting(p -> p.getFileName().toString())
        .containsExactly("Queue.java");
  }

  @Test
  void theDatabaseAndTheNetworkConnectionLiveSideBySide() throws IOException {
    ScratchProject project = project();
    Abiturklassen.install(project, Abiturklassen.read(officialZip()),
        List.of("DatabaseConnector", "Connection"));
    String connector = Files.readString(project.root().resolve("DatabaseConnector.java"));
    assertThat(connector).contains("private java.sql.Connection connection;")
        .contains("02 Version fuer SQLite");
    assertThat(project.root().resolve("QueryResult.java")).exists();
    assertThat(project.root().resolve("Queue.java")).exists();
  }

  @Test
  void aProjectWithASqliteDriverGetsNoSecondOne() throws IOException {
    ScratchProject project = project();
    Files.writeString(project.libsDir().resolve("sqlite-jdbc-3.45.0.0.jar"), "");
    // no download: the project has a driver
    assertThat(Abiturklassen.installSqliteDriver(project, tmp.resolve("cache"))).isNull();
    assertThat(tmp.resolve("cache")).doesNotExist();
  }

  @Test
  void checksDownloadsByTheirSha1() throws IOException {
    Path file = Files.writeString(tmp.resolve("abc.txt"), "abc");
    assertThat(Abiturklassen.sha1(file)).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
  }

  @Test
  void anUnpackedFolderWorksToo() throws IOException {
    Path folder = Files.createDirectories(tmp.resolve("unpacked/01 linear"));
    Files.writeString(folder.resolve("List.java"), "public class List<T> {}");
    assertThat(Abiturklassen.read(tmp.resolve("unpacked"))).containsOnlyKeys("List");
  }
}
