package org.openpatch.scratch4j.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class CoursePackTest {
  @TempDir Path temporary;

  private Path archive(String projectPath) throws Exception {
    Path archive = temporary.resolve("course.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      Map<String, String> files = Map.of(
          ".scratch4j/course.json", "{\"schemaVersion\":1,\"libraryVersion\":\"5.7.0\",\"projects\":[{\"path\":\"" + projectPath + "\",\"flavour\":\"nrw\"}]}",
          "projects/game/.scratch4j/project.json", "{\"version\":1,\"libraryVersion\":\"5.7.0\",\"flavour\":\"nrw\",\"lesson\":\"course\",\"checkpoint\":\"start\"}",
          "projects/game/Main.java", "void main() {}",
          "projects/game/assets/custom.frag", "uniform sampler2D texture;");
      for (var entry : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return archive;
  }

  @Test void importsAnOfflineCourseWithTheChosenBundledFlavorAndAssets() throws Exception {
    Path library = temporary.resolve("scratch-5.7.0-nrw-all.jar");
    Files.write(library, new byte[] {1, 2, 3});
    var imported = CoursePack.importZip(archive("projects/game"), temporary.resolve("import"), Map.of("nrw", library));
    assertThat(imported.projects()).hasSize(1);
    Path project = imported.projects().get(0);
    assertThat(Files.readAllBytes(project.resolve("+libs/" + library.getFileName()))).containsExactly(1, 2, 3);
    assertThat(Files.readString(project.resolve("assets/custom.frag"))).contains("sampler2D");
    assertThat(org.openpatch.scratch4j.core.project.ScratchProject.open(project).settings().checkpoint).isEqualTo("start");
  }

  @Test void invalidManifestOrMissingOfflineLibraryPublishesNoPartialPack() throws Exception {
    Path parent = temporary.resolve("import");
    assertThatThrownBy(() -> CoursePack.importZip(archive("../outside"), parent, Map.of())).isInstanceOf(java.io.IOException.class);
    assertThat(parent.resolve("course")).doesNotExist();
    assertThatThrownBy(() -> CoursePack.importZip(archive("projects/game"), parent, Map.of())).isInstanceOf(java.io.IOException.class);
    try (var files = Files.list(parent)) { assertThat(files.toList()).isEmpty(); }
  }
}
